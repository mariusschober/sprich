package com.sprich.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sprich.app.core.audio.Pcm16Wav
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechSessionConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhisperNativeInstrumentedTest {
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
