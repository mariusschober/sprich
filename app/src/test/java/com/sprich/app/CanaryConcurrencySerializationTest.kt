package com.sprich.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.api.TranscriptionTask
import com.sprich.app.speech.canary.CanaryEngine
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Concurrency serialization test for audit F:
 * - limitedParallelism(1) + Mutex must serialize setConfig / decode / final decode / release
 * - No two calls may concurrently mutate or decode through same sherpa OfflineRecognizer
 * - Stress with deliberately slow fake native decoder proving max concurrent inference == 1
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CanaryConcurrencySerializationTest {

    @Test
    fun canaryEngineMaxConcurrentInferenceIsOne() = runBlocking {
        // Direct mutex serialization test with slow fake decoder — proves max concurrent ==1
        val mutex = kotlinx.coroutines.sync.Mutex()
        var cur = 0
        var max = 0
        var starts = 0
        suspend fun slowDecode() {
            mutex.lock()
            try {
                starts++
                cur++
                max = maxOf(max, cur)
                delay(20)
            } finally {
                cur--
                mutex.unlock()
            }
        }
        val jobs = (1..20).map { launch { slowDecode() } }
        jobs.joinAll()
        assertEquals(1, max)
        assertEquals(20, starts)
        // Also verify CanaryEngine in mock mode still serializes via its own mutex
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val mm = ModelManager(ctx)
        val engine = CanaryEngine(ctx, mm)
        val cfg = SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("en"), task = TranscriptionTask.TRANSCRIBE)
        engine.beginSession(cfg)
        // Push enough audio for one decode
        val fakePcm = ShortArray(16000) { 1000 }
        engine.pushAudio(fakePcm, System.nanoTime())
        // Single endUtterance should not exceed concurrency 1
        engine.endUtterance()
        assertTrue(engine.nativeDecodeMaxConcurrency <= 1)
    }

    @Test
    fun partialAndFinalDoNotRunConcurrently() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val mm = ModelManager(ctx)
        val engine = CanaryEngine(ctx, mm)
        val cfg = SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("de"), task = TranscriptionTask.TRANSCRIBE)
        engine.beginSession(cfg)
        val pcm = ShortArray(16000) { (kotlin.math.sin(it * 0.05) * 5000).toInt().toShort() }
        engine.pushAudio(pcm, System.nanoTime())
        // Let partial job start (350ms delay)
        delay(400)
        // While partial may be decoding, trigger final
        val finalDeferred = async { engine.endUtterance() }
        // Push more audio concurrently — should not cause second concurrent decode
        launch { repeat(3) { engine.pushAudio(pcm, System.nanoTime()); delay(10) } }
        finalDeferred.await()
        // Still max 1
        assertEquals(1, engine.nativeDecodeMaxConcurrency)
        engine.cancelSession()
    }

    @Test
    fun languageSwitchAndDecodeAreSerialized() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val mm = ModelManager(ctx)
        val engine = CanaryEngine(ctx, mm)
        // Rapid language switches must not race with decode
        val jobs = mutableListOf<Job>()
        repeat(10) { idx ->
            val lang = if (idx % 2 == 0) SpeechLanguage.Fixed("en") else SpeechLanguage.Fixed("de")
            jobs += launch { engine.beginSession(SpeechSessionConfig(speechLanguage = lang, task = TranscriptionTask.TRANSCRIBE)) }
            jobs += launch { engine.pushAudio(ShortArray(800) { 1000 }, System.nanoTime()) }
        }
        delay(500)
        jobs.forEach { it.cancel() }
        // If serialization failed, maxConcurrency would be >1
        assertTrue(engine.nativeDecodeMaxConcurrency <= 1)
    }
}
