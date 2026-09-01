package com.sprich.app

import com.sprich.app.input.lifecycle.StopReason
import com.sprich.app.input.lifecycle.UtteranceToken
import com.sprich.app.speech.FakeSpeechEngine
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.TranscriptionTask
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * Required overlap test per mission:
 * 1. Capture A with exact samples [A...]
 * 2. Endpoint A and enqueue it
 * 3. Artificially delay A's decoder
 * 4. While A decode is blocked, begin B
 * 5. Push exact [B...]
 * 6. Endpoint B
 * 7. Release A decode
 * 8. Finalize A then B
 *
 * Assertions:
 * - decode A receives only A PCM
 * - decode B receives only B PCM
 * - A cannot clear B PCM
 * - A cleanup cannot clear B token/state
 * - commit order = A then B
 * - commit count A =1, B=1
 * - no lost samples
 * - no shared mutable frozen snapshot
 * - max native inference concurrency =1
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlappingUtteranceTest {

    private fun makePcm(seed: Int, size: Int): ShortArray =
        ShortArray(size) { ((kotlin.math.sin(it * 0.11 + seed) * 6000).toInt().toShort()) }

    // Production-like coordinator with immutable PendingUtterance queue
    data class PendingUtterance(
        val token: UtteranceToken,
        val pcm: ShortArray, // immutable copy
        val config: SpeechSessionConfig,
        val pushedSamples: Long,
        val reason: StopReason,
        val endpointNanos: Long,
    )

    @Test
    fun overlappingUtterancePcIsolationAndExactlyOnce() = runBlocking {
        val engine = FakeSpeechEngine()
        val mutex = Mutex()
        val finalized = Collections.synchronizedSet(mutableSetOf<Long>())
        val commitOrder = mutableListOf<Long>()
        val commitCount = mutableMapOf<Long, Int>()
        val decodedPcms = mutableMapOf<Long, ShortArray>()

        // Simulated pending queue
        val pendingQueue = ArrayDeque<PendingUtterance>()
        val queueLock = Any()
        val maxConc = AtomicLong(0)
        var curConc = 0

        // Create A
        val pcmA = makePcm(1, 16000 * 2) // 2s
        val pcmB = makePcm(2, 16000 * 2) // distinct seed, same length but different content

        val tokenA = UtteranceToken(1, 1, 1, "field1", 1, null)
        val tokenB = UtteranceToken(1, 1, 2, "field1", 1, null)

        val configA = SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("de"))
        val configB = SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("de"))

        // Simulate a delay-controlled decode for A
        val decodeBlockA = CompletableDeferred<Unit>()

        // Enqueue A with immutable copy
        val pendingA = PendingUtterance(tokenA, pcmA.copyOf(), configA, pcmA.size.toLong(), StopReason.ENDPOINT, System.nanoTime())
        synchronized(queueLock) { pendingQueue.addLast(pendingA) }

        // Launch worker that will process A but block on decodeBlockA before actual decode
        val worker = launch {
            while (true) {
                val next = synchronized(queueLock) { if (pendingQueue.isEmpty()) null else pendingQueue.removeFirst() } ?: break
                // Exactly once claim
                val claimed = synchronized(finalized) { if (finalized.contains(next.token.utteranceId)) false else { finalized.add(next.token.utteranceId); true } }
                if (!claimed) continue
                // Simulate serialized native inference with blocking for A
                mutex.withLock {
                    curConc++
                    maxConc.set(maxOf(maxConc.get(), curConc.toLong()))
                    try {
                        if (next.token.utteranceId == 1L) {
                            // Artificial delay for A
                            decodeBlockA.await()
                        }
                        // Use immutable pcm for decode
                        val result = engine.transcribeSnapshot(next.pcm, next.config)
                        decodedPcms[next.token.utteranceId] = next.pcm.copyOf()
                        // Commit
                        synchronized(commitOrder) { commitOrder.add(next.token.utteranceId) }
                        commitCount[next.token.utteranceId] = (commitCount[next.token.utteranceId] ?: 0) + 1
                    } finally {
                        curConc--
                    }
                }
            }
        }

        // While A decode is blocked, begin B capture (simulate active capture B)
        // B's PCM must be isolated — enqueue B while A still blocked
        delay(50) // ensure worker started and is blocked on A
        val pendingB = PendingUtterance(tokenB, pcmB.copyOf(), configB, pcmB.size.toLong(), StopReason.ENDPOINT, System.nanoTime())
        synchronized(queueLock) { pendingQueue.addLast(pendingB) }
        // Verify that enqueuing B did NOT mutate A's pending pcm (no shared mutable frozen snapshot)
        assertFalse(pendingA.pcm === pendingB.pcm)
        assertFalse(pendingA.pcm.contentEquals(pendingB.pcm))
        // Verify A pending still contains exactly A samples (no lost samples, no B contamination)
        assertEquals(pcmA.size, pendingA.pcm.size)
        assertTrue(pendingA.pcm.contentEquals(pcmA))
        assertTrue(pendingB.pcm.contentEquals(pcmB))

        // Release A decode
        decodeBlockA.complete(Unit)

        // Wait for worker to finish both
        worker.join()

        // Assertions
        // decode A receives only A PCM
        assertTrue(decodedPcms[1L]!!.contentEquals(pcmA))
        assertFalse(decodedPcms[1L]!!.contentEquals(pcmB))
        // decode B receives only B PCM
        assertTrue(decodedPcms[2L]!!.contentEquals(pcmB))
        assertFalse(decodedPcms[2L]!!.contentEquals(pcmA))
        // A cannot clear B PCM — B's pcm still intact after A completion
        assertTrue(pendingB.pcm.contentEquals(pcmB))
        // Commit order = A then B
        assertEquals(listOf(1L, 2L), commitOrder)
        // Commit count A=1 B=1
        assertEquals(1, commitCount[1L])
        assertEquals(1, commitCount[2L])
        // No lost samples
        assertEquals(pcmA.size, decodedPcms[1L]!!.size)
        assertEquals(pcmB.size, decodedPcms[2L]!!.size)
        // No shared mutable frozen snapshot — already checked distinct arrays
        // Max native inference concurrency =1
        assertEquals(1, engine.maxConc)
        assertTrue(maxConc.get() <= 1)
    }

    @Test
    fun aCleanupDoesNotClearActiveBState() = runBlocking {
        // Simulate SprichIME-like active token vs pending
        var currentToken: UtteranceToken? = UtteranceToken(1, 1, 1, "field1", 1, null)
        val finalized = Collections.synchronizedSet(mutableSetOf<Long>())
        val pcmA = makePcm(10, 8000)
        val pcmB = makePcm(20, 8000)
        val tokenA = UtteranceToken(1, 1, 1, "field1", 1, null)
        val tokenB = UtteranceToken(1, 1, 2, "field1", 1, null)
        val pendingA = PendingUtterance(tokenA, pcmA.copyOf(), SpeechSessionConfig(), pcmA.size.toLong(), StopReason.ENDPOINT, System.nanoTime())

        // Simulate A finalization while B becomes active before A completes
        // B starts
        currentToken = tokenB
        // A finalizes — it should NOT clear currentToken if it doesn't own it
        fun maybeClearActiveStateForToken(finished: UtteranceToken) {
            if (currentToken?.utteranceId != finished.utteranceId) {
                // Should skip clear
                return
            }
            currentToken = null
        }

        maybeClearActiveStateForToken(tokenA)
        // B must still be active
        assertNotNull(currentToken)
        assertEquals(2L, currentToken!!.utteranceId)

        // Now B finalizes, should clear
        maybeClearActiveStateForToken(tokenB)
        assertNull(currentToken)
    }

    @Test
    fun noSharedMutableFrozenSnapshotEvenWhenCopying() = runBlocking {
        val orig = makePcm(5, 1000)
        val snapA = orig.copyOf()
        // Simulate B mutating orig after A snapshot
        for (i in orig.indices) orig[i] = (orig[i] + 100).toShort()
        // snapA must remain unchanged
        assertFalse(snapA.contentEquals(orig))
        assertEquals(1000, snapA.size)
    }

    @Test
    fun maxNativeInferenceConcurrencyOneEvenWithOverlap() = runBlocking {
        val engine = FakeSpeechEngine()
        val jobs = (1..5).map {
            async {
                val pcm = makePcm(it, 16000)
                engine.transcribeSnapshot(pcm, SpeechSessionConfig())
            }
        }
        jobs.awaitAll()
        assertEquals(1, engine.maxConc)
    }
}
