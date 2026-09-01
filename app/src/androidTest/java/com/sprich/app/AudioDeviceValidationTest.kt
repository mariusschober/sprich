package com.sprich.app

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sprich.app.core.audio.AudioCapture
import com.sprich.app.core.audio.AudioRingBuffer
import com.sprich.app.core.audio.Resampler
import com.sprich.app.core.vad.Vad
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AudioDeviceValidationTest {

    @Before
    fun ensurePermissions() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        try { InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${app.packageName} android.permission.RECORD_AUDIO").close() } catch (_: Exception) {}
        try { InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant com.sprich.app.debug.test android.permission.RECORD_AUDIO").close() } catch (_: Exception) {}
        // Give system a moment to grant
        Thread.sleep(200)
    }

    @Test
    fun audioCaptureIs16kMonoAndBoundsAndMicReleased() {
        val capture = AudioCapture(sampleRate = 16000, ringSeconds = 4)
        var chunks = 0
        var startOk = false
        val t0 = System.nanoTime()
        startOk = capture.start(
            onChunk = { samples, ts ->
                chunks++
                // Verify chunk is 1024 samples (64ms) typical, and RMS finite
                assertTrue(samples.isNotEmpty())
                assertTrue(samples.size <= 2048)
                val rms = Resampler.rms(samples)
                assertTrue(rms.isFinite())
            },
            onFailure = { fail(it) }
        )
        // If emulator without mic, start may fail; on real device it must succeed
        if (!startOk) {
            // On this device, fail the test to surface hardware issue
            fail("AudioCapture failed to start on device T807D - check RECORD_AUDIO permission and mic availability")
        }
        assertTrue("capture should be active", capture.isActive())
        // Let it run for 500ms to collect chunks
        Thread.sleep(600)
        assertTrue("should have received chunks, got $chunks", chunks >= 4)
        // Verify ring buffer bounded (4s = 64000 samples)
        assertTrue(capture.ringBuffer.available() <= 16000 * 4)
        assertTrue(capture.ringBuffer.capacity() == 16000 * 4)
        // Measure mic release latency <1s
        val tStop0 = System.nanoTime()
        capture.stop()
        val releaseMs = (System.nanoTime() - tStop0) / 1_000_000
        assertTrue("mic release $releaseMs ms should be <1000", releaseMs < 1000)
        assertFalse(capture.isActive())
        // Verify ring buffer can snapshot pre-roll
        capture.ringBuffer.clear()
        val speech = ShortArray(4000) { (sin(it * 0.05) * 8000).toInt().toShort() }
        capture.ringBuffer.write(speech)
        val snap = capture.ringBuffer.snapshotLast(0.25f, 16000)
        assertEquals(4000, snap.size)
        capture.release()
    }

    @Test
    fun vadOnDeviceHandlesImmediateAndWhisper() {
        val vad = Vad()
        // Simulate immediate speech after focus (calibration must not learn speech as noise)
        var state = Vad.State.SILENCE
        val quietSpeech = ShortArray(1024) { (sin(it * 0.1) * 500).toInt().toShort() }
        repeat(6) { state = vad.process(quietSpeech, 64).state }
        assertTrue(state == Vad.State.SPEECH || state == Vad.State.HESITATION)
        vad.reset()
        // Normal voice
        val normalSpeech = ShortArray(1024) { (sin(it * 0.1) * 12000).toInt().toShort() }
        repeat(3) { vad.process(ShortArray(1024) { 0 }, 64) } // calibrate silence
        state = vad.process(normalSpeech, 64).state
        repeat(2) { state = vad.process(normalSpeech, 64).state }
        assertTrue(state == Vad.State.SPEECH || state == Vad.State.HESITATION)
        // Endpoint
        var ended = false
        repeat(15) {
            val r = vad.process(ShortArray(1024) { 0 }, 64)
            if (r.state == Vad.State.UTTERANCE_END) ended = true
        }
        assertTrue("VAD should emit UTTERANCE_END after silence", ended)
    }

    @Test
    fun resamplerQualityOnDevice() {
        val src48000 = ShortArray(4800) { (sin(it * 0.1) * 10000).toInt().toShort() }
        val dst = Resampler.resampleTo16k(src48000, srcRate = 48000, dstRate = 16000)
        assertEquals(1600, dst.size)
        assertTrue(Resampler.rms(dst).isFinite())
        assertTrue(Resampler.peak(dst) <= 32767)
        // 16k->16k should copy
        val same = Resampler.resampleTo16k(src48000, 16000, 16000)
        assertArrayEquals(src48000, same)
    }

    @Test
    fun ringBufferPreRollRetainsFirstPhonemeOnDevice() {
        val rb = AudioRingBuffer(16000 * 4)
        val preRoll = ShortArray(4000) { (sin(it * 0.05) * 8000).toInt().toShort() }
        var retained = 0
        repeat(100) {
            rb.clear()
            rb.write(ShortArray(500) { 0 })
            rb.write(preRoll)
            val snap = rb.snapshotLast(0.25f, 16000)
            if (snap.size == 4000 && snap.any { it != 0.toShort() }) retained++
        }
        assertTrue("first phoneme retained $retained/100", retained >= 99)
    }
}
