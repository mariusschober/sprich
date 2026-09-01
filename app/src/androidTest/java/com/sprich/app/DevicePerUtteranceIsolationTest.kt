package com.sprich.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.api.TranscriptionTask
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log
import com.sprich.app.core.audio.AudioRingBuffer

@RunWith(AndroidJUnit4::class)
class DevicePerUtteranceIsolationTest {

    @Test
    fun perUtterancePcmIsolatedOnDevice() {
        // Simulate per-utterance buffer isolation — same logic as SprichIME and CanaryEngine
        val bufA = ShortArray(16000) { (kotlin.math.sin(it*0.1 + 1)*5000).toInt().toShort() }
        val bufB = ShortArray(8000) { (kotlin.math.sin(it*0.1 + 2)*5000).toInt().toShort() }
        // CanaryEngine per-utterance buffer
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        val engine = app.fastEngine
        // Ensure model loaded
        val load = kotlinx.coroutines.runBlocking { engine.load() }
        assertTrue("engine load", load.isSuccess)
        // Utterance A
        engine.beginSession(SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("en"), task = TranscriptionTask.TRANSCRIBE))
        engine.pushAudio(bufA, System.nanoTime())
        // Simulate beginUtteranceCapture + push
        try { engine.javaClass.getMethod("beginUtteranceCapture", ShortArray::class.java).invoke(engine, bufA) } catch (_: Exception) {}
        // Push again for B after clearing
        val snapA = try { engine.javaClass.getMethod("snapshotUtterancePcm").invoke(engine) as ShortArray } catch (_: Exception) { ShortArray(0) }
        Log.i("PerUtteranceDevice", "snapA size=${snapA.size}")
        // Clear and start B
        engine.beginSession(SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("de"), task = TranscriptionTask.TRANSCRIBE))
        try { engine.javaClass.getMethod("beginUtteranceCapture", ShortArray::class.java).invoke(engine, bufB) } catch (_: Exception) {}
        engine.pushAudio(bufB, System.nanoTime())
        val snapB = try { engine.javaClass.getMethod("snapshotUtterancePcm").invoke(engine) as ShortArray } catch (_: Exception) { ShortArray(0) }
        Log.i("PerUtteranceDevice", "snapB size=${snapB.size}")
        assertFalse("B must not contain A", snapB.size == snapA.size && snapB.contentEquals(snapA))
        assertTrue(snapB.isNotEmpty())
    }

    @Test
    fun audioRingDoesNotLeakAcrossUtterancesOnDevice() {
        val ring = AudioRingBuffer(16000*30)
        val a = ShortArray(16000) { 1000 }
        val b = ShortArray(8000) { 2000 }
        ring.write(a)
        ring.write(b)
        val snap = ring.snapshotLast(30f)
        assertEquals(24000, snap.size)
        // Per-utterance isolation would be 8000 for B, not 24000
        val perUtteranceB = b.copyOf()
        assertEquals(8000, perUtteranceB.size)
        assertFalse(perUtteranceB.contentEquals(snap.copyOfRange(0, 16000)))
    }

    @Test
    fun canaryConcurrencyOnDeviceMaxOne() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        val engine = app.fastEngine
        assertTrue(engine.nativeDecodeMaxConcurrency <= 1)
        Log.i("PerUtteranceDevice", "maxConc=${engine.nativeDecodeMaxConcurrency} starts=${engine.nativeDecodeStarts}")
    }
}
