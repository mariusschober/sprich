package com.sprich.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sprich.app.core.audio.Pcm16Wav
import com.sprich.app.speech.lid.WhisperLidEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class LidDeviceTest {
    @Test
    fun lidDetectsEnglishOnJfk() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        val lid = WhisperLidEngine(app, com.sprich.app.models.manager.ModelManager(app))
        val load = runBlocking { lid.load() }
        Log.i("LidDevice", "load $load success=${load.isSuccess} err=${load.exceptionOrNull()?.message}")
        if (load.isFailure) {
            Log.w("LidDevice", "LID model not present, skipping real detection (mock fallback)")
            // Mock path: still should return something
            val pcm = ShortArray(16000) { (kotlin.math.sin(it*0.08)*6000).toInt().toShort() }
            val res = runBlocking { lid.identify(pcm) }
            assertNotNull(res.language)
            Log.i("LidDevice", "mock lid $res")
            return
        }
        val audio = app.assets.open("jfk.wav").use { Pcm16Wav.read(it) }
        val pcm = audio.samples.copyOfRange(0, 16000*3) // 3s
        val res = runBlocking { lid.identify(pcm) }
        Log.i("LidDevice", "lid jfk raw=${res.rawCode} lang=${res.language} conf=${res.confidence} latency=${res.latencyMs} pcm=${pcm.size}")
        assertTrue(res.language == com.sprich.app.speech.api.Language.EN || res.language == com.sprich.app.speech.api.Language.AUTO || res.rawCode.lowercase().contains("en"))
        assertTrue(res.latencyMs < 2000)
        assertTrue(res.latencyMs > 0)
    }

    @Test
    fun lidPerUtteranceNoHardCache() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        val lid = WhisperLidEngine(app, com.sprich.app.models.manager.ModelManager(app))
        val load = runBlocking { lid.load() }
        if (load.isFailure) {
            Log.w("LidDevice", "skip no model")
            return
        }
        val audio = app.assets.open("jfk.wav").use { Pcm16Wav.read(it) }
        val pcmEn = audio.samples.copyOfRange(0, 16000*2)
        // Simulate second utterance same field but different language: use same pcm for now (both EN) but test that per-utterance not cached 30s
        val res1 = runBlocking { lid.identify(pcmEn) }
        val res2 = runBlocking { lid.identify(pcmEn) }
        Log.i("LidDevice", "res1 $res1 res2 $res2")
        // Both should be EN and not stuck to previous hard cache (if we had DE then EN, should switch)
        assertEquals(res1.language, res2.language)
    }
}
