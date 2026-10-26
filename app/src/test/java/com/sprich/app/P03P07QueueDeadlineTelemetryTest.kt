package com.sprich.app

import com.sprich.app.core.perf.OverrunCounter
import com.sprich.app.core.perf.SoftEvaluation
import com.sprich.app.core.perf.evaluateSoftTarget
import com.sprich.app.core.perf.isOverHardTimeout
import com.sprich.app.core.perf.remainingBudgetMs
import com.sprich.app.diagnostics.TelemetryEvent
import com.sprich.app.diagnostics.TelemetryEventType
import com.sprich.app.diagnostics.UtteranceOutcome
import com.sprich.app.diagnostics.UtteranceTelemetry
import com.sprich.app.diagnostics.TelemetryStageGuard
import com.sprich.app.input.lifecycle.FinalizationClaims
import com.sprich.app.input.lifecycle.QueueAdmission
import com.sprich.app.input.lifecycle.QueuedUtterance
import com.sprich.app.input.lifecycle.RejectionReason
import com.sprich.app.input.lifecycle.UtteranceQueue
import com.sprich.app.input.lifecycle.UtteranceQueueLimits
import com.sprich.app.speech.remote.DeadlinePolicy
import com.sprich.app.storage.RecoveryDraftStore
import org.junit.Assert.*
import org.junit.Test

/**
 * Host-runnable JVM-pure tests for plan P03/P07 + §6 (queue, deadlines,
 * telemetry, Finish/Cancel, recovery draft). No Android, no network.
 */
class P03P07QueueDeadlineTelemetryTest {

    private fun entry(seq: Long, bytes: Int = 32000, endpointMs: Long = 1000L) =
        QueuedUtterance(seq = seq, utteranceId = seq, byteSize = bytes, endpointMonoMs = endpointMs)

    // --- Queue bounds (§6.2) ---

    @Test
    fun fourOutstandingBound_fifthRejected() {
        val q = UtteranceQueue()
        for (seq in 1L..4L) assertTrue(q.admit(entry(seq)) is QueueAdmission.Admitted)
        val rejected = q.admit(entry(5))
        assertTrue(rejected is QueueAdmission.Rejected)
        assertEquals(RejectionReason.QUEUE_FULL, (rejected as QueueAdmission.Rejected).reason)
    }

    @Test
    fun globalAudioCeiling_rejectsOverBudget() {
        val q = UtteranceQueue()
        assertTrue(q.admit(entry(1, bytes = UtteranceQueueLimits.GLOBAL_AUDIO_CEILING_BYTES)) is QueueAdmission.Admitted)
        val rejected = q.admit(entry(2, bytes = 2))
        assertTrue(rejected is QueueAdmission.Rejected)
        assertEquals(RejectionReason.OVER_AUDIO_BUDGET, (rejected as QueueAdmission.Rejected).reason)
    }

    @Test
    fun optionalCleanupShedBeforeRejectingCapture() {
        val q = UtteranceQueue()
        for (seq in 1L..4L) q.admit(entry(seq))
        q.markCleanupPending(4)
        val admitted = q.admit(entry(5))
        assertTrue(admitted is QueueAdmission.Admitted)
        assertEquals(4L, (admitted as QueueAdmission.Admitted).shedCleanupForSeq)
        val shed = q.peekHead()
        // Shed entry converted to faithful-ready; lane still ordered at head 1.
        assertEquals(1L, shed?.seq)
    }

    @Test
    fun nothingSheddable_rejects() {
        val q = UtteranceQueue()
        for (seq in 1L..4L) q.admit(entry(seq))
        assertTrue(q.admit(entry(5)) is QueueAdmission.Rejected)
    }

    // --- Single ordered commit lane (§6.2) ---

    @Test
    fun readyLaterResultWaitsForHead() {
        val q = UtteranceQueue()
        q.admit(entry(1))
        q.admit(entry(2))
        q.markRecognitionDone(2)
        q.markCleanupDone(2)
        assertNull(q.pollCommittable()) // head 1 not ready: no overtake
        q.markRecognitionDone(1)
        q.markCleanupDone(1)
        assertEquals(1L, q.pollCommittable()?.seq)
        assertEquals(2L, q.pollCommittable()?.seq)
        assertNull(q.pollCommittable())
    }

    @Test
    fun cleanupExpiry_releasesLaneWithFaithfulFallback() {
        val q = UtteranceQueue()
        q.admit(entry(1))
        q.markRecognitionDone(1)
        q.markCleanupPending(1)
        assertNull(q.pollCommittable())
        q.expireCleanup(1)
        val head = q.pollCommittable()
        assertNotNull(head)
        assertTrue(head!!.useFaithfulFallback)
        assertTrue(head.resultReady)
    }

    @Test
    fun recognitionFailure_advancesLaneWithoutText() {
        val q = UtteranceQueue()
        q.admit(entry(1))
        q.admit(entry(2))
        q.markFailed(1)
        val head = q.pollCommittable()
        assertNotNull(head)
        assertTrue(head!!.failed)
        assertFalse(head.resultReady)
    }

    @Test
    fun stuckHeadCleanup_starvesLane_untilExpiryReleasesInOrder() {
        // Gate C: a slow/failing optional cleanup must not starve subsequent
        // recognition — the lane holds (no overtake) until expiry, then both
        // drain head-first with the faithful result for the expired head.
        val q = UtteranceQueue()
        q.admit(entry(1))
        q.admit(entry(2))
        q.markRecognitionDone(1)
        q.markCleanupPending(1)
        q.markRecognitionDone(2)
        q.markCleanupDone(2)
        assertNull(q.pollCommittable()) // head 1 stuck: 2 waits
        q.expireCleanup(1)
        val first = q.pollCommittable()
        assertNotNull(first)
        assertEquals(1L, first!!.seq)
        assertTrue(first.useFaithfulFallback)
        assertEquals(2L, q.pollCommittable()?.seq)
        assertNull(q.pollCommittable())
    }

    @Test
    fun lateCleanupDone_afterExpiry_keepsFaithfulFallback() {
        // Gate C: a cleanup response arriving after expiry must not resurrect
        // or reorder the entry — the faithful flag survives a late done.
        val q = UtteranceQueue()
        q.admit(entry(1))
        q.markRecognitionDone(1)
        q.markCleanupPending(1)
        q.expireCleanup(1)
        q.markCleanupDone(1) // late arrival: idempotent, keeps faithful
        val head = q.pollCommittable()
        assertNotNull(head)
        assertTrue(head!!.useFaithfulFallback)
        assertTrue(head.resultReady)
    }

    @Test
    fun shedEntry_stillCommitsBeforeNewcomer() {
        // Gate C: shedding converts the newest pending-cleanup entry to
        // faithful-ready, but commit order stays head-first — the shed entry
        // never jumps ahead, the newcomer never overtakes it.
        val q = UtteranceQueue()
        for (seq in 1L..4L) q.admit(entry(seq))
        q.markCleanupPending(4)
        val admitted = q.admit(entry(5))
        assertTrue(admitted is QueueAdmission.Admitted)
        for (seq in 1L..5L) {
            q.markRecognitionDone(seq)
            if (seq != 4L) q.markCleanupDone(seq)
        }
        // Entry 4 was shed to faithful-ready at admission; the rest completed.
        val order = generateSequence { q.pollCommittable() }.map { it.seq }.toList()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), order)
    }

    // --- Finish vs Cancel (§6.5) ---

    @Test
    fun finish_drainsAcceptedInOrder_closesIntake() {
        val q = UtteranceQueue()
        q.admit(entry(2))
        q.admit(entry(1))
        val drained = q.finish()
        assertEquals(listOf(1L, 2L), drained.map { it.seq })
        assertTrue(q.admit(entry(3)) is QueueAdmission.Rejected)
    }

    @Test
    fun cancel_discardsAndInvalidates() {
        val q = UtteranceQueue()
        val epoch = q.invalidationEpoch
        q.admit(entry(1))
        val discarded = q.cancel()
        assertEquals(1, discarded.size)
        assertEquals(epoch + 1, q.invalidationEpoch)
        assertEquals(0, q.outstandingCount())
        assertNull(q.pollCommittable())
    }

    // --- Absolute deadlines (§6.3) ---

    @Test
    fun absoluteBudget_includesQueueAge_noPerStageReset() {
        val policy = DeadlinePolicy.DEFAULT
        assertEquals(8000L, policy.remainingMs(endpointMonoMs = 1000L, nowMonoMs = 1000L))
        assertEquals(0L, policy.remainingMs(1000L, 9000L))
        assertTrue(policy.isOverAbsoluteDeadline(1000L, 9001L))
        assertFalse(policy.isOverAbsoluteDeadline(1000L, 9000L))
    }

    @Test
    fun cleanupGetsOnlyRemainingSoftBudget() {
        val policy = DeadlinePolicy.DEFAULT
        // Fast local transcript: budget remains -> cleanup may run.
        assertTrue(policy.cleanupBudgetMs(1000L, 1200L, cloudAssisted = false) > 0)
        // Slow transcript past the soft target: skip cleanup, commit faithful.
        assertTrue(policy.cleanupBudgetMs(1000L, 2000L, cloudAssisted = false) <= 0)
        // Cloud path uses the wider soft target.
        assertTrue(
            policy.cleanupBudgetMs(1000L, 1500L, cloudAssisted = true) >
                policy.cleanupBudgetMs(1000L, 1500L, cloudAssisted = false),
        )
    }

    @Test
    fun softMissRetainsResult_hardTimeoutFails() {
        // Correct result at 750 ms against a 700 ms soft target is kept.
        assertEquals(SoftEvaluation.OVER_TARGET_RETAIN, evaluateSoftTarget(750L, 700L))
        assertEquals(SoftEvaluation.WITHIN_TARGET, evaluateSoftTarget(700L, 700L))
        assertFalse(isOverHardTimeout(750L, 8000L))
        assertTrue(isOverHardTimeout(8001L, 8000L))
        val counter = OverrunCounter()
        counter.record(750L, softTargetMs = 700L, hardTimeoutMs = 8000L)
        assertEquals(1L, counter.overSoft)
        assertEquals(0L, counter.overHard)
        assertEquals(500L, remainingBudgetMs(deadlineMonoNanos = 1_500_000_000L, nowMonoNanos = 1_000_000_000L))
    }

    // --- Telemetry (§6.4) ---

    @Test
    fun telemetry_recordsBoundariesWithIdentityAndAttempts() {
        var now = 1_000_000L
        val telemetry = UtteranceTelemetry(maxEvents = 64) { now }
        telemetry.record(7L, 1L, TelemetryEventType.FOCUS)
        now += 10_000L
        telemetry.record(7L, 1L, TelemetryEventType.FIRST_PCM)
        now += 10_000L
        telemetry.record(7L, 1L, TelemetryEventType.REMOTE_ACK, outcome = UtteranceOutcome.TRANSPORT_FAILURE, attemptIndex = 0)
        telemetry.record(7L, 1L, TelemetryEventType.RECOGNITION_END, outcome = UtteranceOutcome.SUCCESS, attemptIndex = 1)
        // Both attempts counted, not only the winner.
        assertEquals(1, telemetry.attemptCount(TelemetryEventType.REMOTE_ACK, 7L))
        assertEquals(1, telemetry.attemptCount(TelemetryEventType.RECOGNITION_END, 7L))
        assertEquals(20_000L, telemetry.elapsedNanos(7L, TelemetryEventType.FOCUS, TelemetryEventType.REMOTE_ACK))
        assertEquals(4, telemetry.snapshotFor(7L).size)
        assertTrue(telemetry.snapshotFor(99L).isEmpty())
    }

    @Test
    fun telemetry_carriesNoTranscriptOrSecretByConstruction() {
        val stringFields = TelemetryEvent::class.java.declaredFields.filter { it.type == String::class.java }
        assertTrue("TelemetryEvent must not carry transcript/secret/endpoint strings: $stringFields", stringFields.isEmpty())
    }

    @Test
    fun telemetry_ringIsBounded() {
        val telemetry = UtteranceTelemetry(maxEvents = 8)
        repeat(20) { telemetry.record(it.toLong(), it.toLong(), TelemetryEventType.FIRST_PCM) }
        assertEquals(8, telemetry.size())
    }

    // --- Recovery draft (§6.5) ---

    @Test
    fun recovery_singleDraft_explicitConsume_expiryAndLock() {
        var now = 50_000L
        val store = RecoveryDraftStore(clockMs = { now })
        assertTrue(store.save("hello world"))
        assertEquals("hello world", store.peek())
        // Explicit user action consumes exactly once.
        assertEquals("hello world", store.consume())
        assertNull(store.peek())
        assertFalse(store.hasDraft())
        // Expiry clears.
        store.save("second")
        now += RecoveryDraftStore.TTL_MS + 1
        assertNull(store.peek())
        // Lock clears.
        now = 50_000L
        store.save("third")
        store.onLock()
        assertNull(store.peek())
        // Blank never creates a draft.
        assertFalse(store.save("   "))
    }

    @Test
    fun recovery_truncatesTo16k() {
        val store = RecoveryDraftStore()
        assertTrue(store.save("x".repeat(20_000)))
        assertEquals(RecoveryDraftStore.MAX_CHARS, store.peek()!!.length)
        assertEquals(16_384, RecoveryDraftStore.MAX_CHARS)
    }

    // --- Exactly-once finalization claims (Gate C: at-most-once mutation) ---

    @Test
    fun claims_firstWins_duplicateDrops() {
        val claims = FinalizationClaims()
        assertTrue(claims.claim(7L))
        assertFalse(claims.claim(7L))
        assertTrue(claims.contains(7L))
        assertTrue(claims.claim(8L))
        assertEquals(2, claims.size())
    }

    @Test
    fun claims_bounded_oldestEvicted() {
        val claims = FinalizationClaims(maxClaims = 4)
        for (id in 1L..4L) assertTrue(claims.claim(id))
        assertEquals(4, claims.size())
        assertTrue(claims.claim(5L))
        assertEquals(4, claims.size())
        assertFalse(claims.contains(1L)) // oldest evicted: reclaimable
        assertTrue(claims.claim(1L))
        assertTrue(claims.contains(5L)) // newest retained
    }

    @Test
    fun claims_defaultBound_is128() {
        assertEquals(128, FinalizationClaims.MAX_CLAIMS)
        val claims = FinalizationClaims()
        for (id in 1L..130L) claims.claim(id)
        assertEquals(128, claims.size())
        assertFalse(claims.contains(1L))
        assertFalse(claims.contains(2L))
        assertTrue(claims.contains(130L))
    }

    // --- Stage emission guard (§6.4: boundary events recorded once) ---

    @Test
    fun stageGuard_firstEmitWins_repeatDrops() {
        val guard = TelemetryStageGuard()
        assertTrue(guard.shouldEmit(7L, TelemetryEventType.FIRST_PREVIEW))
        assertFalse(guard.shouldEmit(7L, TelemetryEventType.FIRST_PREVIEW))
        // Independence across utterances and types.
        assertTrue(guard.shouldEmit(8L, TelemetryEventType.FIRST_PREVIEW))
        assertTrue(guard.shouldEmit(7L, TelemetryEventType.PROVIDER_FINAL))
        assertEquals(3, guard.size())
    }

    @Test
    fun stageGuard_bounded_oldestEvicted() {
        val guard = TelemetryStageGuard(maxTracked = 4)
        for (id in 1L..4L) assertTrue(guard.shouldEmit(id, TelemetryEventType.REMOTE_CONNECT))
        assertTrue(guard.shouldEmit(5L, TelemetryEventType.REMOTE_CONNECT))
        assertEquals(4, guard.size())
        // Oldest evicted: re-emits (callers scope by generation, not by guard).
        assertTrue(guard.shouldEmit(1L, TelemetryEventType.REMOTE_CONNECT))
        assertFalse(guard.shouldEmit(5L, TelemetryEventType.REMOTE_CONNECT))
    }
}
