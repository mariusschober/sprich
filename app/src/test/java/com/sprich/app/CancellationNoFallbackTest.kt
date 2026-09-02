package com.sprich.app

import com.sprich.app.speech.*
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.remote.RemoteSttProvider
import com.sprich.app.speech.remote.RemoteSttRequest
import com.sprich.app.speech.remote.RemoteSttResult
import com.sprich.app.storage.ApiSecretStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CancellationNoFallbackTest {

    private fun makePcm(): ShortArray = ShortArray(8000) { (kotlin.math.sin(it*0.01)*1000).toInt().toShort() }

    private class CountingRemote : RemoteSttProvider {
        override val id = "openai-compatible"
        override val capabilities = com.sprich.app.speech.remote.RemoteSttCapabilities()
        val calls = AtomicInteger(0)
        override suspend fun transcribe(request: RemoteSttRequest): RemoteSttResult {
            calls.incrementAndGet()
            delay(200)
            return RemoteSttResult("remote hello", ResolvedUtteranceLanguage.Unknown, TranscriptionSourceId.API_OPENAI_COMPATIBLE)
        }
    }

    private fun fakeLocal(delayMs: Long = 50): LocalTranscriptionCoordinator {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val mm = com.sprich.app.models.manager.ModelManager(ctx)
        val lid = com.sprich.app.speech.lid.WhisperLidEngine(ctx, mm)
        val fast = com.sprich.app.speech.fastconformer.FastConformerEngine(ctx)
        val canary = com.sprich.app.speech.canary.CanaryEngine(ctx, mm)
        return object : LocalTranscriptionCoordinator(lid, fast, canary) {
            override suspend fun transcribe(pcm: ShortArray, route: LocalAsrRoute, baseConfig: SpeechSessionConfig): LocalTranscriptionResult {
                delay(delayMs)
                if (!currentCoroutineContext().isActive) throw CancellationException("cancelled")
                return LocalTranscriptionResult("local hello", ResolvedUtteranceLanguage.Unknown, baseConfig, "fastconformer-ctc-en-de-es-fr-14288-int8")
            }
        }
    }

    private fun cancellingLocal(reason: String): LocalTranscriptionCoordinator {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val mm = com.sprich.app.models.manager.ModelManager(ctx)
        val lid = com.sprich.app.speech.lid.WhisperLidEngine(ctx, mm)
        val fast = com.sprich.app.speech.fastconformer.FastConformerEngine(ctx)
        val canary = com.sprich.app.speech.canary.CanaryEngine(ctx, mm)
        return object : LocalTranscriptionCoordinator(lid, fast, canary) {
            override suspend fun transcribe(pcm: ShortArray, route: LocalAsrRoute, baseConfig: SpeechSessionConfig): LocalTranscriptionResult {
                throw CancellationException(reason)
            }
        }
    }

    @Test fun cancelLocalFirst_noRemoteCall() = runBlocking {
        val pcm = makePcm()
        val remote = CountingRemote()
        val local = fakeLocal(delayMs = 200)
        val secretStore = object : ApiSecretStore(org.robolectric.RuntimeEnvironment.getApplication()) {
            override fun loadSecret(id: String): String? = "sk-test"
        }
        val coord = TranscriptionCoordinator(local, mapOf("openai-compatible" to remote), secretStore)
        val plan = UtterancePlan(
            transcription = TranscriptionPlan.LocalApiFallback(
                LocalAsrRoute.AutomaticFastConformer,
                com.sprich.app.speech.remote.RemoteSttConfig("openai-compatible","https://example.com","model", com.sprich.app.speech.LanguagePolicy.Automatic,3500,"stt_default")
            ),
            refinement = RefinementPlan.Off,
            speechConfig = SpeechSessionConfig()
        )

        val job = async { coord.transcribe(pcm, plan, utteranceId = 1) }
        delay(20)
        job.cancel()
        try { job.await(); fail("should be cancelled") } catch (e: CancellationException) { }
        delay(50)
        assertEquals("Remote must not be called after local cancellation", 0, remote.calls.get())
    }

    @Test fun cancelApiPrimary_noLateLocalOrEditor() = runBlocking {
        val pcm = makePcm()
        val remote = object : RemoteSttProvider {
            override val id = "openai-compatible"
            override val capabilities = com.sprich.app.speech.remote.RemoteSttCapabilities()
            val calls = AtomicInteger(0)
            override suspend fun transcribe(request: RemoteSttRequest): RemoteSttResult {
                calls.incrementAndGet()
                try { delay(500) } catch (e: CancellationException) { throw e }
                return RemoteSttResult("remote", ResolvedUtteranceLanguage.Unknown, TranscriptionSourceId.API_OPENAI_COMPATIBLE)
            }
        }
        val local = fakeLocal()
        val secretStore = object : ApiSecretStore(org.robolectric.RuntimeEnvironment.getApplication()) {
            override fun loadSecret(id: String): String? = "sk-test"
        }
        val coord = TranscriptionCoordinator(local, mapOf("openai-compatible" to remote), secretStore)
        val plan = UtterancePlan(
            transcription = TranscriptionPlan.ApiPrimary(
                com.sprich.app.speech.remote.RemoteSttConfig("openai-compatible","https://example.com","model", com.sprich.app.speech.LanguagePolicy.Automatic,3500,"stt_default"),
                LocalAsrRoute.AutomaticFastConformer
            ),
            refinement = RefinementPlan.Off,
            speechConfig = SpeechSessionConfig()
        )
        val job = async { coord.transcribe(pcm, plan, 1) }
        delay(20)
        job.cancel()
        try { job.await(); fail("should cancel") } catch (e: CancellationException) {}
        assertTrue(true)
    }

    @Test fun cancellationDoesNotFallbackToRemote_forWindowHidden() = runBlocking {
        val pcm = makePcm()
        val remote = CountingRemote()
        val local = cancellingLocal("WINDOW_HIDDEN")
        val secretStore = object : ApiSecretStore(org.robolectric.RuntimeEnvironment.getApplication()) {
            override fun loadSecret(id: String): String? = "sk-test"
        }
        val coord = TranscriptionCoordinator(local, mapOf("openai-compatible" to remote), secretStore)
        val plan = UtterancePlan(
            transcription = TranscriptionPlan.LocalApiFallback(
                LocalAsrRoute.AutomaticFastConformer,
                com.sprich.app.speech.remote.RemoteSttConfig("openai-compatible","https://example.com","model", com.sprich.app.speech.LanguagePolicy.Automatic,3500,"stt_default")
            ),
            refinement = RefinementPlan.Off, speechConfig = SpeechSessionConfig()
        )
        try { coord.transcribe(pcm, plan, 1); fail("should throw cancellation") } catch (e: CancellationException) {}
        assertEquals(0, remote.calls.get())
    }

    @Test fun fieldLostNoRemoteUpload() = runBlocking {
        val remote = CountingRemote()
        val local = cancellingLocal("FIELD_LOST")
        val coord = TranscriptionCoordinator(local, mapOf("openai-compatible" to remote), object : ApiSecretStore(org.robolectric.RuntimeEnvironment.getApplication()) { override fun loadSecret(id: String): String? = "sk-test" })
        val plan = UtterancePlan(TranscriptionPlan.LocalApiFallback(LocalAsrRoute.AutomaticFastConformer, com.sprich.app.speech.remote.RemoteSttConfig("openai-compatible","https://example.com","model", com.sprich.app.speech.LanguagePolicy.Automatic,3500,"stt_default")), RefinementPlan.Off, SpeechSessionConfig())
        try { coord.transcribe(makePcm(), plan, 1); fail("") } catch (e: CancellationException) {}
        assertEquals(0, remote.calls.get())
    }

    @Test fun serviceDestroyedNoRemoteUpload() = runBlocking {
        val remote = CountingRemote()
        val local = cancellingLocal("SERVICE_DESTROYED")
        val coord = TranscriptionCoordinator(local, mapOf("openai-compatible" to remote), object : ApiSecretStore(org.robolectric.RuntimeEnvironment.getApplication()) { override fun loadSecret(id: String): String? = "sk-test" })
        val plan = UtterancePlan(TranscriptionPlan.LocalApiFallback(LocalAsrRoute.AutomaticFastConformer, com.sprich.app.speech.remote.RemoteSttConfig("openai-compatible","https://example.com","model", com.sprich.app.speech.LanguagePolicy.Automatic,3500,"stt_default")), RefinementPlan.Off, SpeechSessionConfig())
        try { coord.transcribe(makePcm(), plan, 1); fail("") } catch (e: CancellationException) {}
        assertEquals(0, remote.calls.get())
    }

    @Test fun inputRestartedNoRemoteUpload() = runBlocking {
        val remote = CountingRemote()
        val local = cancellingLocal("INPUT_RESTARTED")
        val coord = TranscriptionCoordinator(local, mapOf("openai-compatible" to remote), object : ApiSecretStore(org.robolectric.RuntimeEnvironment.getApplication()) { override fun loadSecret(id: String): String? = "sk-test" })
        val plan = UtterancePlan(TranscriptionPlan.LocalApiFallback(LocalAsrRoute.AutomaticFastConformer, com.sprich.app.speech.remote.RemoteSttConfig("openai-compatible","https://example.com","model", com.sprich.app.speech.LanguagePolicy.Automatic,3500,"stt_default")), RefinementPlan.Off, SpeechSessionConfig())
        try { coord.transcribe(makePcm(), plan, 1); fail("") } catch (e: CancellationException) {}
        assertEquals(0, remote.calls.get())
    }
}
