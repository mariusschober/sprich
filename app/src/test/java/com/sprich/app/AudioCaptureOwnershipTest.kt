package com.sprich.app

import com.sprich.app.core.audio.AudioCapture
import org.junit.Assert.*
import kotlinx.coroutines.async
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioCaptureOwnershipTest {
    private class Recorder(private val stopGate: CountDownLatch = CountDownLatch(0)) : AudioCapture.Recorder {
        val reading = CountDownLatch(1)
        val readGate = CountDownLatch(1)
        val stops = AtomicInteger()
        val releases = AtomicInteger()
        override fun read(buffer: ShortArray): Int { reading.countDown(); readGate.await(5, TimeUnit.SECONDS); return -6 }
        override fun stop() { stops.incrementAndGet(); readGate.countDown(); stopGate.await(5, TimeUnit.SECONDS) }
        override fun release() { releases.incrementAndGet() }
    }
    @Test fun delayedOldCleanupCannotStopOrReleaseTheSuccessor() = kotlinx.coroutines.runBlocking {
        val oldStopGate = CountDownLatch(1)
        val old = Recorder(oldStopGate); val next = Recorder()
        val queue = ArrayDeque(listOf(old, next))
        val capture = AudioCapture(openRecorder = { queue.removeFirst() })
        assertTrue(capture.startWithOffset({ _, _, _, _, _ -> }))
        assertTrue(old.reading.await(2, TimeUnit.SECONDS))
        val retired = capture.requestStop()!!
        assertSame("Cleanup after an earlier stop must still await the retired reader", retired, capture.requestStop())
        val successor = async(kotlinx.coroutines.Dispatchers.Default) { capture.startWithOffset({ _, _, _, _, _ -> }) }
        oldStopGate.countDown()
        assertTrue(successor.await())
        assertTrue(next.reading.await(2, TimeUnit.SECONDS))
        assertTrue(retired.awaitStop(2000))
        assertEquals(1, old.releases.get())
        assertEquals(0, next.stops.get())
        assertEquals(0, next.releases.get())
        assertTrue(capture.isActive())
        assertTrue(capture.requestStop()!!.awaitStop(2000))
        assertEquals(1, next.releases.get())
    }
    @Test fun everyNegativeReadStopsOnceWithoutSpinning() = kotlinx.coroutines.runBlocking {
        for (code in listOf(-1, -2, -3, -6)) {
            val reads = AtomicInteger(); val releases = CountDownLatch(1); val failed = CountDownLatch(1)
            val recorder = object : AudioCapture.Recorder {
                override fun read(buffer: ShortArray): Int { reads.incrementAndGet(); return code }
                override fun stop() {}
                override fun release() { releases.countDown() }
            }
            val capture = AudioCapture(openRecorder = { recorder })
            assertTrue(capture.startWithOffset({ _, _, _, _, _ -> }, { failed.countDown() }))
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertTrue(releases.await(2, TimeUnit.SECONDS))
            assertEquals(1, reads.get())
            assertFalse(capture.isActive())
        }
    }
}
