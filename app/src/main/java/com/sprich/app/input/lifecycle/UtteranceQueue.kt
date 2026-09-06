package com.sprich.app.input.lifecycle

/**
 * Bounded utterance queue + single ordered commit lane (plan §6.2, §6.5).
 *
 * Pure JVM: no Android imports, no editor authority. The caller (IME commit
 * coordinator) retains Main-thread field/session/permission rechecks and the
 * actual [android.view.inputmethod.InputConnection] mutation; this class only
 * decides admission and commit order.
 *
 * Rules:
 * - At most [UtteranceQueueLimits.MAX_OUTSTANDING_UTTERANCES] outstanding
 *   utterances, and retained audio below
 *   [UtteranceQueueLimits.GLOBAL_AUDIO_CEILING_BYTES] (count AND bytes).
 * - Optional cleanup is shed before new capture is rejected: admission first
 *   converts a pending-cleanup entry (newest first) to faithful-ready, and
 *   only rejects when nothing sheddable remains.
 * - One commit order keyed by utterance sequence: [pollCommittable] returns the
 *   head entry only, so a ready later result never overtakes an earlier one. A
 *   ready later result waits only for the head's remaining budget, never for a
 *   second full cleanup timeout (see [expireCleanup]).
 * - [expireCleanup] releases the lane with the faithful deterministic result
 *   ([useFaithfulFallback]); a late cleanup response must then be discarded by
 *   the caller.
 * - Required-recognition failure is typed ([markFailed]): the lane advances
 *   with a failure state, never with fabricated text.
 * - Finish ("stop recording, finalize accepted speech, drain accepted work in
 *   order", see [finish]) vs Cancel ("stop recording, invalidate pending work
 *   and discard it", see [cancel]).
 */
object UtteranceQueueLimits {
    const val MAX_OUTSTANDING_UTTERANCES = 4
    const val GLOBAL_AUDIO_CEILING_BYTES = 24 * 1024 * 1024
    const val BYTES_PER_SAMPLE_MONO_PCM16 = 2
}

enum class RejectionReason { QUEUE_FULL, OVER_AUDIO_BUDGET }

sealed interface QueueAdmission {
    data class Admitted(val shedCleanupForSeq: Long? = null) : QueueAdmission
    data class Rejected(val reason: RejectionReason, val shedCleanupForSeq: Long? = null) : QueueAdmission
}

data class QueuedUtterance(
    val seq: Long,
    val utteranceId: Long,
    val byteSize: Int,
    val endpointMonoMs: Long,
    var cleanupPending: Boolean = false,
    var recognitionDone: Boolean = false,
    var resultReady: Boolean = false,
    var failed: Boolean = false,
    var useFaithfulFallback: Boolean = false,
)

class UtteranceQueue {
    private val lock = Any()
    private val entries = ArrayDeque<QueuedUtterance>()
    private var intakeClosed = false
    /** Bumped on [cancel]; stale async callbacks carrying an older epoch must be ignored by the caller. */
    var invalidationEpoch: Long = 0L
        private set

    fun outstandingCount(): Int = synchronized(lock) { entries.size }

    fun retainedBytes(): Long = synchronized(lock) { entries.sumOf { it.byteSize.toLong() } }

    /**
     * Admit one endpointed utterance. Sheds one pending optional cleanup
     * (newest first) before rejecting. Returns the shed sequence, if any, so
     * the caller can commit that entry's faithful text first.
     */
    fun admit(entry: QueuedUtterance): QueueAdmission = synchronized(lock) {
        if (intakeClosed) return QueueAdmission.Rejected(RejectionReason.QUEUE_FULL)
        val overCount = entries.size >= UtteranceQueueLimits.MAX_OUTSTANDING_UTTERANCES
        val overBytes = retainedBytesLocked() + entry.byteSize > UtteranceQueueLimits.GLOBAL_AUDIO_CEILING_BYTES
        if (!overCount && !overBytes) {
            entries.addLast(entry)
            return QueueAdmission.Admitted()
        }
        val shed = newestSheddableLocked()
        if (shed != null) {
            shed.cleanupPending = false
            shed.resultReady = true
            shed.useFaithfulFallback = true
            entries.addLast(entry)
            return QueueAdmission.Admitted(shedCleanupForSeq = shed.seq)
        }
        QueueAdmission.Rejected(if (overCount) RejectionReason.QUEUE_FULL else RejectionReason.OVER_AUDIO_BUDGET)
    }

    fun markRecognitionDone(seq: Long) = synchronized(lock) {
        entries.find { it.seq == seq }?.recognitionDone = true
    }

    fun markCleanupPending(seq: Long) = synchronized(lock) {
        entries.find { it.seq == seq }?.cleanupPending = true
    }

    /** Optional cleanup finished in budget: entry becomes committable once it reaches the head. */
    fun markCleanupDone(seq: Long) = synchronized(lock) {
        entries.find { it.seq == seq }?.let {
            it.cleanupPending = false
            it.resultReady = true
        }
    }

    /**
     * Prior optional cleanup expired: choose the faithful result once and
     * release the lane. The caller commits [QueuedUtterance.useFaithfulFallback]
     * text and discards any late cleanup response.
     */
    fun expireCleanup(seq: Long) = synchronized(lock) {
        entries.find { it.seq == seq }?.let {
            it.cleanupPending = false
            it.resultReady = true
            it.useFaithfulFallback = true
        }
    }

    /** Required recognition failed: typed failure advances the lane; never fabricate text. */
    fun markFailed(seq: Long) = synchronized(lock) {
        entries.find { it.seq == seq }?.let {
            it.cleanupPending = false
            it.failed = true
        }
    }

    /** Head-of-line only: null unless the earliest admitted entry is committable or failed. */
    fun pollCommittable(): QueuedUtterance? = synchronized(lock) {
        val head = entries.firstOrNull() ?: return null
        if (head.failed || head.resultReady) {
            entries.removeFirst()
            head
        } else null
    }

    fun peekHead(): QueuedUtterance? = synchronized(lock) { entries.firstOrNull() }

    /**
     * FINISH closes intake and returns accepted work in sequence order for
     * draining; CANCEL invalidates and discards everything. Both clear the lane.
     */
    fun finish(): List<QueuedUtterance> = synchronized(lock) {
        intakeClosed = true
        val drained = entries.sortedBy { it.seq }
        entries.clear()
        drained
    }

    fun cancel(): List<QueuedUtterance> = synchronized(lock) {
        intakeClosed = true
        invalidationEpoch++
        val discarded = entries.toList()
        entries.clear()
        discarded
    }

    fun reset() = synchronized(lock) {
        entries.clear()
        intakeClosed = false
    }

    private fun retainedBytesLocked(): Long = entries.sumOf { it.byteSize.toLong() }

    private fun newestSheddableLocked(): QueuedUtterance? {
        // Newest first. Shedding converts the entry to faithful-ready, which is
        // also how an expired head-of-line cleanup releases the lane.
        return entries.lastOrNull { it.cleanupPending }
    }
}
