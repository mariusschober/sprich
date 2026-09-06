package com.sprich.app

import com.sprich.app.core.audio.AudioCapture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P01 wiring test (§5.1): the real [AudioCapture] read loop (fake [Recorder];
 * runs under Robolectric so `SystemClock` works) feeds the bounded startup
 * buffer until the pipeline drains it, exactly
 * once; [requestStop] invalidates the stale generation; restart re-arms.
 *
 * Deterministic by construction: the fake feeds an explicit bounded budget
 * (below the 128,000-sample cap, so nothing is ever dropped) then parks;
 * the parked thread is a daemon and never blocks test exit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P01StartupWiringTest {

    private class FakeRecorder(val fed: ShortArray) : AudioCapture.Recorder {
        @Volatile var budget = 0
        @Volatile var fedCount = 0
            private set

        override fun read(buffer: ShortArray): Int {
            if (budget <= 0) {
                // Parked: short sleep so a stopped thread still joins promptly;
                // 100 consecutive empties would end capture after ~5 s, far
                // beyond any phase below (each < 2 s on any sane machine).
                Thread.sleep(50)
                return 0
            }
            val n = minOf(buffer.size, 256, budget)
            for (i in 0 until n) buffer[i] = fed[(fedCount + i) % fed.size]
            fedCount += n
            budget -= n
            return n
        }

        override fun stop() {}
        override fun release() {}
    }

    private fun startWithFake(fed: ShortArray): Pair<AudioCapture, FakeRecorder> {
        val fake = FakeRecorder(fed)
        val cap = AudioCapture(openRecorder = { fake })
        var failed: String? = null
        val started = runBlocking {
            cap.startWithOffset({ _, _, _, _, _ -> }, { failed = it })
        }
        assertTrue("capture started", started)
        assertNull("no mic failure, got: $failed", failed)
        return cap to fake
    }

    private fun awaitFed(fake: FakeRecorder, want: Int) {
        var waited = 0
        while (fake.fedCount < want && waited < 5000) {
            Thread.sleep(50)
            waited += 50
        }
        assertTrue("feeder delivered $want samples, has ${fake.fedCount}", fake.fedCount >= want)
    }

    @Test
    fun readLoopFeedsStartupBufferUntilDrainedOnce() {
        val fed = ShortArray(4096) { (it % 97).toShort() }
        val total = 16384
        val (cap, fake) = startWithFake(fed)
        try {
            fake.budget = total
            awaitFed(fake, total)
            assertTrue(cap.startupBufferedBytes() >= total * 2)
            // Ordered, complete, exactly once (no drops: total is far below cap).
            assertArrayEquals(ShortArray(total) { fed[it % fed.size] }, cap.drainStartupBuffer())
            assertEquals(0, cap.drainStartupBuffer().size)
        } finally {
            cap.requestStop()
        }
    }

    @Test
    fun stopInvalidatesStaleGenerationAndRestartRearms() {
        val fed = ShortArray(4096) { (it % 61).toShort() }
        val (cap, fake) = startWithFake(fed)
        try {
            fake.budget = 8192
            awaitFed(fake, 8192)
            assertTrue(cap.startupBufferedBytes() >= 8192 * 2)
        } finally {
            cap.requestStop()
        }
        // Undrained startup audio dies with the capture.
        assertEquals(0, cap.drainStartupBuffer().size)
        // Restarting the same instance re-arms a new generation and feeds again.
        // Retry: the retired thread may need one parked cycle to join first.
        val before = fake.fedCount
        try {
            var failed: String? = null
            var started = false
            var tries = 0
            while (!started && tries < 60) {
                tries++
                started = runBlocking { cap.startWithOffset({ _, _, _, _, _ -> }, { failed = it }) }
                if (!started) Thread.sleep(50)
            }
            assertTrue("restarted after stop", started)
            assertNull(failed)
            fake.budget = 4096
            awaitFed(fake, before + 4096)
            val drained = cap.drainStartupBuffer()
            assertTrue("re-armed buffer feeds, samples=${drained.size}", drained.isNotEmpty())
            assertEquals(0, cap.drainStartupBuffer().size)
        } finally {
            cap.requestStop()
        }
    }
}
