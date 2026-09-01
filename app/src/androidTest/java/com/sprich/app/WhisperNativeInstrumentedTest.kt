package com.sprich.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sprich.app.core.audio.Pcm16Wav
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechSessionConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhisperNativeInstrumentedTest {

    @Before
    fun ensureModel() {
        val app = ApplicationProvider.getApplicationContext<SprichApp>()
        ensureCanaryFiles(app)
        // Grant mic permission for audio-related tests that may run in same suite
        try { InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${app.packageName} android.permission.RECORD_AUDIO").close() } catch (_: Exception) {}
    }

    private fun ensureCanaryFiles(app: SprichApp) {
        val destDir = java.io.File(app.filesDir, "canary")
        val enc = java.io.File(destDir, "encoder.int8.onnx")
        val dec = java.io.File(destDir, "decoder.int8.onnx")
        val tok = java.io.File(destDir, "tokens.txt")
        if (enc.exists() && enc.length() > 50_000_000 && dec.exists() && dec.length() > 50_000_000 && tok.exists()) return
        // Try to copy from /data/local/tmp where host pushed the model (persists across reinstalls)
        try {
            destDir.mkdirs()
            val srcEnc = java.io.File("/data/local/tmp/encoder.int8.onnx")
            val srcDec = java.io.File("/data/local/tmp/decoder.int8.onnx")
            val srcTok = java.io.File("/data/local/tmp/tokens.txt")
            if (srcEnc.exists() && srcDec.exists() && srcTok.exists()) {
                srcEnc.copyTo(enc, overwrite = true)
                srcDec.copyTo(dec, overwrite = true)
                srcTok.copyTo(tok, overwrite = true)
                android.util.Log.i("TestModelHelper", "copied canary from /data/local/tmp to ${destDir.absolutePath} enc=${enc.length()} dec=${dec.length()}")
            } else {
                android.util.Log.w("TestModelHelper", "no src model in /data/local/tmp")
            }
        } catch (e: Exception) { android.util.Log.w("TestModelHelper", "copy failed", e) }
    }
    @Test
    fun activeDecodeCanBeCancelled() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SprichApp>()
        val engine = app.fastEngine
        assertTrue(withTimeout(120_000) { engine.load() }.isSuccess)
        val audio = app.assets.open("jfk.wav").use(Pcm16Wav::read)

        engine.cancelSession()
        engine.beginSession(SpeechSessionConfig(language = Language.EN))
        engine.pushAudio(audio.samples, System.nanoTime())
        val final = async { engine.endUtterance() }
        delay(500)
        engine.cancelSession()

        withTimeout(15_000) { final.await() }
        assertTrue(engine.isLoaded())
    }

    @Test
    fun rapidSessionResetKeepsEngineLoaded() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SprichApp>()
        val engine = app.fastEngine
        assertTrue(withTimeout(120_000) { engine.load() }.isSuccess)

        repeat(50) {
            engine.beginSession(SpeechSessionConfig(language = Language.EN))
            engine.pushAudio(ShortArray(1_024), System.nanoTime())
            engine.cancelSession()
        }

        assertTrue(engine.isLoaded())
    }

    @Test
    fun bundledModelTranscribesDeterministicSpeech() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SprichApp>()
        val engine = app.fastEngine
        val load = withTimeout(120_000) { engine.load() }
        assertTrue("Fast engine failed to load: ${load.exceptionOrNull()}", load.isSuccess)

        val audio = app.assets.open("jfk.wav").use(Pcm16Wav::read)
        assertTrue(audio.samples.size > 160_000)

        engine.cancelSession()
        engine.beginSession(SpeechSessionConfig(language = Language.EN))
        try {
            engine.pushAudio(audio.samples, System.nanoTime())
            // Software-emulated arm64 can be far slower than a phone. This gate is
            // about a real non-empty result, not emulator performance.
            val result = withTimeout(300_000) { engine.endUtterance() }
            assertTrue("Expected a non-empty real transcript", result.text.length >= 20)
        } finally {
            engine.cancelSession()
        }
    }
}
