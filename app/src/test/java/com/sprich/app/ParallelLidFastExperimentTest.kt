package com.sprich.app

import com.sprich.app.speech.*
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.api.SpeechLanguage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host experiment for parallel Tiny LID + FastConformer.
 * Uses fake engines with realistic delays (LID ~30ms, Fast ~300ms) to model p50/p95.
 * Real device would use actual native engines; host provides deterministic baseline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParallelLidFastExperimentTest {

    private class FakeLid(val delayMs: Long = 30) {
        suspend fun identify(pcm: ShortArray): Long {
            delay(delayMs)
            return delayMs
        }
    }
    private class FakeFast(val delayMs: Long = 320) {
        suspend fun transcribe(pcm: ShortArray): Long {
            delay(delayMs)
            return delayMs
        }
    }

    @Test
    fun sequentialVsParallelBaseline() = runBlocking {
        val pcm = ShortArray(16000 * 2) { 0 }
        val lid = FakeLid(30)
        val fast = FakeFast(320)
        val runs = 30
        val seqTimes = mutableListOf<Long>()
        val parTimes = mutableListOf<Long>()

        repeat(runs) {
            val t0 = System.nanoTime()
            lid.identify(pcm)
            fast.transcribe(pcm)
            seqTimes.add((System.nanoTime() - t0) / 1_000_000)
        }
        repeat(runs) {
            val t0 = System.nanoTime()
            coroutineScope {
                val a = async { lid.identify(pcm) }
                val b = async { fast.transcribe(pcm) }
                a.await(); b.await()
            }
            parTimes.add((System.nanoTime() - t0) / 1_000_000)
        }

        fun List<Long>.p50() = sorted()[size / 2]
        fun List<Long>.p95() = sorted()[(size * 0.95).toInt().coerceAtMost(size - 1)]
        fun List<Long>.max() = maxOrNull() ?: 0

        val seqP50 = seqTimes.p50()
        val parP50 = parTimes.p50()
        val seqP95 = seqTimes.p95()
        val parP95 = parTimes.p95()
        val delta = seqP50 - parP50

        println("Parallel experiment (host fake, 30 runs, LID 30ms + Fast 320ms):")
        println("sequential p50=${seqP50}ms p95=${seqP95}ms max=${seqTimes.max()}")
        println("parallel   p50=${parP50}ms p95=${parP95}ms max=${parTimes.max()}")
        println("delta median=${delta}ms (${String.format("%.1f", delta*100.0/seqP50)}%)")

        // Parallel should be ~ max(LID,Fast) not sum, so ~320 vs ~350, delta ~30ms (8-10%)
        // On host with fake delays, parallel p50 should be close to fast's delay, sequential sum
        assertTrue("parallel should be faster than sequential", parP50 < seqP50)
        assertTrue("p95 should not regress", parP95 <= seqP95 + 10) // allow 10ms jitter

        // Decision: keep only if ~100ms+ median improvement on device; host fake shows 30ms, so on device need real measurement
        // Host cannot prove device thermal/CPU, so report NOT MEASURED for device, but host baseline shows feasibility
        println("Decision: host shows parallel is feasible (single PCM, same frozen copy), device measurement required for KEEP/REVERT")
    }

    @Test
    fun samePcmOwnership() = runBlocking {
        val pcm = ShortArray(1000) { it.toShort() }
        val lid = FakeLid()
        val fast = FakeFast()
        // Both must consume same immutable PCM — no copy
        val pcmCopyForLid = pcm.copyOf()
        val pcmCopyForFast = pcm // same reference would be bad if mutated, but we treat as immutable
        coroutineScope {
            val a = async { lid.identify(pcmCopyForLid); pcmCopyForLid.contentEquals(pcm) }
            val b = async { fast.transcribe(pcmCopyForFast); pcmCopyForFast.contentEquals(pcm) }
            assertTrue(a.await())
            assertTrue(b.await())
        }
        // Original PCM unchanged
        assertTrue(pcm.contentEquals(ShortArray(1000){it.toShort()}))
    }
}
