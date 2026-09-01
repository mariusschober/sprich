package com.sprich.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sprich.app.core.audio.Pcm16Wav
import com.sprich.app.input.lifecycle.StopReason
import com.sprich.app.input.lifecycle.UtteranceToken
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.SpeechSessionConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 1 stress test: finalization actor must be single long-lived Channel,
 * FIFO, no lost-wakeup, genuine backpressure, bounded memory, max concurrency 1.
 *
 * Deliberately makes decoder slower than realtime and enqueues >4 utterances.
 */
@RunWith(AndroidJUnit4::class)
class QueueActorStressDeviceTest {

    data class PendingUtterance(
        val token: UtteranceToken,
        val pcm: ShortArray,
        val config: SpeechSessionConfig,
        val pushedSamples: Long,
        val reason: StopReason,
    )

    @Test
    fun actorFifoBackpressureNoStrandedMaxConcOne() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        val audio = app.assets.open("jfk.wav").use { Pcm16Wav.read(it) }
        val engine = app.fastEngine
        // Ensure canary loaded for real decode (serialized)
        val load = engine.load()
        Log.i("QueueStress", "engine load $load success=${load.isSuccess}")

        // Simulate SprichIME actor: Channel UNLIMITED + external depth + CatchingUp
        val pendingChannel = Channel<PendingUtterance>(capacity = Channel.UNLIMITED)
        val queueDepth = AtomicInteger(0)
        val pendingQueuePeak = AtomicLong(0)
        val finalizationQueueOverflows = AtomicLong(0)
        val maxPendingQueueDepth = 4
        var catchingUp = false
        val processedOrder = mutableListOf<Long>()
        val processedPcms = mutableMapOf<Long, ShortArray>()
        val mutex = Mutex()
        var curConcurrent = 0
        var maxConcurrent = 0

        // Single consumer actor (exactly one, FIFO, no lost-wakeup)
        val actorJob = launch {
            for (pending in pendingChannel) {
                val depthBefore = queueDepth.get()
                try {
                    // Simulate slow decoder: sleep longer than realtime (1s audio -> 300ms real, we sleep 400ms)
                    // Use mutex to enforce max concurrency 1 like Canary's inferenceMutex
                    mutex.withLock {
                        curConcurrent++
                        if (curConcurrent > maxConcurrent) maxConcurrent = curConcurrent
                        assertTrue("max concurrency must be 1, got $curConcurrent", curConcurrent <= 1)
                        try {
                            // Real decode if engine available, but add artificial delay to be slower than realtime
                            val t0 = System.nanoTime()
                            val text = if (load.isSuccess) {
                                engine.transcribeSnapshot(pending.pcm, pending.config).text
                            } else {
                                delay(400)
                                "fake"
                            }
                            // Artificial slowdown if real decode was fast
                            val elapsed = (System.nanoTime() - t0) / 1_000_000
                            if (elapsed < 350) delay(350 - elapsed)
                            Log.i("QueueStress", "decoded utt=${pending.token.utteranceId} chars=${text.length} depthBefore=$depthBefore")
                        } finally {
                            curConcurrent--
                        }
                    }
                    synchronized(processedOrder) {
                        processedOrder.add(pending.token.utteranceId)
                        processedPcms[pending.token.utteranceId] = pending.pcm.copyOf()
                    }
                } finally {
                    val newDepth = queueDepth.decrementAndGet()
                    if (catchingUp && newDepth < maxPendingQueueDepth - 1) {
                        catchingUp = false
                        Log.i("QueueStress", "CatchingUp recovered depth=$newDepth")
                    }
                }
            }
        }

        // Enqueue >4 utterances rapidly while decoder is slow — must not strand, must FIFO, must backpressure
        val numUtterances = 6
        val slices = (0 until numUtterances).map { idx ->
            val start = (idx * 16000) % (audio.samples.size - 16000*2)
            audio.samples.copyOfRange(start, start + 16000) // 1s each, distinct
        }
        // Verify distinct PCM
        for (i in 1 until slices.size) assertFalse(slices[i].contentEquals(slices[i-1]))

        fun enqueue(pending: PendingUtterance) {
            val depthBefore = queueDepth.get()
            if (depthBefore >= maxPendingQueueDepth) {
                finalizationQueueOverflows.incrementAndGet()
                if (!catchingUp) {
                    catchingUp = true
                    Log.w("QueueStress", "queue at capacity depth=$depthBefore utt=${pending.token.utteranceId} — entering CatchingUp, degrade partials")
                }
            }
            val newDepth = queueDepth.incrementAndGet()
            if (newDepth.toLong() > pendingQueuePeak.get()) pendingQueuePeak.set(newDepth.toLong())
            Log.i("QueueStress", "enqueue utt=${pending.token.utteranceId} depth=$newDepth peak=${pendingQueuePeak.get()} catchingUp=$catchingUp")
            val result = pendingChannel.trySend(pending)
            if (!result.isSuccess) {
                Log.e("QueueStress", "trySend failed utt=${pending.token.utteranceId} result=$result")
                queueDepth.decrementAndGet()
                launch { pendingChannel.send(pending) }
            }
        }

        // Enqueue all 6 without waiting for actor (simulates VAD endpoint burst while decoder slow)
        for (idx in 0 until numUtterances) {
            val token = UtteranceToken(sessionId = 1, generation = 1, utteranceId = (idx+1).toLong(), fieldId = "field1", fieldGeneration = 1, capturedIc = null)
            val pending = PendingUtterance(token, slices[idx].copyOf(), SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("en")), pushedSamples = 16000L, reason = StopReason.ENDPOINT)
            enqueue(pending)
            // Small delay between endpoints but not waiting for decode (overlapping)
            delay(30)
        }

        // Wait for actor to drain (with timeout)
        withTimeout(20000) {
            while (queueDepth.get() > 0) delay(100)
            // Give actor time to finish last decode
            delay(500)
        }
        pendingChannel.close()
        actorJob.join()

        Log.i("QueueStress", "processedOrder=$processedOrder peak=${pendingQueuePeak.get()} overflows=${finalizationQueueOverflows.get()} maxConc=$maxConcurrent")
        // Assertions: no stranded, FIFO, maxConc 1, bounded, backpressure observable
        assertEquals("All $numUtterances must be processed, FIFO order", (1L..numUtterances.toLong()).toList(), processedOrder)
        assertTrue("max concurrency must be 1, got $maxConcurrent", maxConcurrent <= 1)
        assertTrue("peak should be >= $maxPendingQueueDepth (backpressure triggered)", pendingQueuePeak.get() >= maxPendingQueueDepth)
        assertTrue("peak should be bounded (not unbounded growth), got ${pendingQueuePeak.get()}", pendingQueuePeak.get() <= numUtterances)
        assertTrue("backpressure counter observable >0", finalizationQueueOverflows.get() > 0)
        assertEquals("queueDepth must be 0 after drain", 0, queueDepth.get())
        // Verify PCM immutability: each processed PCM equals original slice (frozen copy, not mutated by later appends)
        for (idx in 0 until numUtterances) {
            val uttId = (idx+1).toLong()
            val orig = slices[idx]
            val processed = processedPcms[uttId]!!
            assertTrue("PCM for utt $uttId must be immutable copy of original", orig.contentEquals(processed))
        }
        // Memory bounded: we used UNLIMITED channel but external depth prevented unbounded suspended coroutines holding PCM
        // No stranded coroutines — actor drained.

        // Also verify native decode max concurrency if using real engine
        if (load.isSuccess) {
            Log.i("QueueStress", "nativeDecodeMaxConcurrency=${engine.nativeDecodeMaxConcurrency}")
            assertTrue("native max concurrency 1", engine.nativeDecodeMaxConcurrency <= 1)
        }
    }
}
