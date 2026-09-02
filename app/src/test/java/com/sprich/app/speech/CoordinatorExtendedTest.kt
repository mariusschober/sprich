package com.sprich.app.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.speech.remote.*
import com.sprich.app.storage.ApiSecretStore
import com.sprich.app.storage.FakeSecretCryptoBackend
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechLanguage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoordinatorExtendedTest {

    @Test
    fun apiPrimarySuccessIncrementsRemoteNotLocal() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val mock = MockRemoteSttProvider(nextResult = Result.success(RemoteSttResult("hello", ResolvedUtteranceLanguage.Known(Language.DE), TranscriptionSourceId.API_MOCK)))
        val local = CountingLocal(ctx)
        val secretStore = com.sprich.app.storage.ApiSecretStore(ctx, com.sprich.app.storage.FakeSecretCryptoBackend())
        val credentialRef = secretStore.saveBoundSecret("mock", "https://api.example.com/v1", "test-credential")!!
        // Ensure secret is stored

        val cfg = RemoteSttConfig("mock", "https://api.example.com/v1", "whisper", LanguagePolicy.Automatic, 1000L, credentialRef)
        val coord = TranscriptionCoordinator(local, mapOf("mock" to mock), secretStore, DeadlinePolicy.TEST_SHORT)
        val plan = UtterancePlan(TranscriptionPlan.ApiPrimary(cfg, LocalAsrRoute.AutomaticFastConformer), RefinementPlan.Off, SpeechSessionConfig(speechLanguage = SpeechLanguage.Auto))
        val result = coord.transcribe(ShortArray(16000), plan, utteranceId = 42L)
        assertEquals("hello", result.text)
        // Local should not have been called
        assertEquals(0, local.transcribeCalls)
        // Verify language propagation: remote detected DE -> effectiveConfig should be DE (P1-16)
        assertEquals(Language.DE, result.effectiveConfig.language)
        assertEquals("de", result.effectiveConfig.speechLanguage.toBcp47())
        assertTrue(result.resolvedLanguage is ResolvedUtteranceLanguage.Known)
        // Verify utteranceId propagated: mock should have received 42
        assertEquals(42L, mock.lastRequest?.utteranceId)
        // Verify no credential leak in request toString
        assertFalse(mock.lastRequest.toString().contains("k"))
    }

    @Test
    fun apiPrimaryFallbackRemainsIdleOnSuccess() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val mock = MockRemoteSttProvider(nextResult = Result.success(RemoteSttResult("remote ok", ResolvedUtteranceLanguage.Unknown, TranscriptionSourceId.API_MOCK)))
        val local = CountingLocal(ctx)
        val secretStore = com.sprich.app.storage.ApiSecretStore(ctx, com.sprich.app.storage.FakeSecretCryptoBackend())
        val credentialRef = secretStore.saveBoundSecret("mock", "https://api.example.com/v1", "test-credential")!!
        val cfg = RemoteSttConfig("mock", "https://api.example.com/v1", "whisper", LanguagePolicy.Automatic, 1000L, credentialRef)
        val coord = TranscriptionCoordinator(local, mapOf("mock" to mock), secretStore, DeadlinePolicy.TEST_SHORT)
        val plan = UtterancePlan(TranscriptionPlan.ApiPrimary(cfg, LocalAsrRoute.AccurateCanary(Language.EN)), RefinementPlan.Off, SpeechSessionConfig())
        val result = coord.transcribe(ShortArray(16000), plan, utteranceId = 100L)
        assertEquals("remote ok", result.text)
        assertEquals(0, local.transcribeCalls) // fallback must stay idle when remote succeeds (P0-8)
    }

    @Test
    fun apiPrimaryFailureFallbackLazyUsesSamePcm() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val failing = FailingMockProvider(ApiFailure.ProviderUnavailable)
        val local = CountingLocal(ctx, "fallback text")
        val secretStore = com.sprich.app.storage.ApiSecretStore(ctx, com.sprich.app.storage.FakeSecretCryptoBackend())
        val credentialRef = secretStore.saveBoundSecret("mock", "https://api.example.com/v1", "test-credential")!!
        val cfg = RemoteSttConfig("mock", "https://api.example.com/v1", "whisper", LanguagePolicy.Automatic, 500L, credentialRef)
        val coord = TranscriptionCoordinator(local, mapOf("mock" to failing), secretStore, DeadlinePolicy.TEST_SHORT)
        val pcm = ShortArray(8000) { (it % 100).toShort() }
        val plan = UtterancePlan(TranscriptionPlan.ApiPrimary(cfg, LocalAsrRoute.AutomaticFastConformer), RefinementPlan.Off, SpeechSessionConfig())
        val result = coord.transcribe(pcm, plan, utteranceId = 200L)
        assertEquals("fallback text", result.text)
        assertEquals(1, failing.calls)
        assertEquals(1, local.transcribeCalls)
        // Verify same PCM was passed to fallback (size same)
        assertEquals(pcm.size, local.lastPcm?.size)
        assertTrue(pcm.contentEquals(local.lastPcm!!))
    }

    @Test
    fun secretStorageRedaction() {
        val req = RemoteSttRequest(ShortArray(10), 16000, LanguagePolicy.Automatic, utteranceId = 1, credential = "supersecret")
        assertFalse(req.toString().contains("supersecret"))
        val cfg = RemoteSttConfig("openai-compatible", "https://api.example.com/v1", "whisper", LanguagePolicy.Automatic, 1000L, "stt_default")
        assertFalse(cfg.toString().contains("https://api.example.com/v1/audio"))
        assertTrue(cfg.toString().contains("[REDACTED"))
        val refineReq = com.sprich.app.speech.refinement.RefinementRequest("my private transcript", "en", com.sprich.app.speech.refinement.RefinementMode.CORRECT, listOf("secretTerm"))
        assertFalse(refineReq.toString().contains("my private transcript"))
    }

    @Test fun httpsValidationRejectsHttp() {
        assertFalse(com.sprich.app.core.security.EndpointValidator.isValidHttpsUrl("http://api.example.com/v1"))
        assertFalse(com.sprich.app.core.security.EndpointValidator.isValidHttpsUrl("https://user:pass@api.example.com/v1"))
        assertTrue(com.sprich.app.core.security.EndpointValidator.isValidHttpsUrl("https://api.example.com/v1"))
    }

    class CountingLocal(private val ctx: Context, private val textToReturn: String = "local") : LocalTranscriptionCoordinator(
        lid = com.sprich.app.speech.lid.WhisperLidEngine(ctx, com.sprich.app.models.manager.ModelManager(ctx)),
        fast = com.sprich.app.speech.fastconformer.FastConformerEngine(ctx),
        canary = com.sprich.app.speech.canary.CanaryEngine(ctx, com.sprich.app.models.manager.ModelManager(ctx))
    ) {
        var transcribeCalls = 0
        var lastPcm: ShortArray? = null
        var lastRoute: LocalAsrRoute? = null
        override suspend fun transcribe(pcm: ShortArray, route: LocalAsrRoute, baseConfig: SpeechSessionConfig): LocalTranscriptionResult {
            transcribeCalls++
            lastPcm = pcm.copyOf()
            lastRoute = route
            return LocalTranscriptionResult(textToReturn, ResolvedUtteranceLanguage.Unknown, baseConfig, "fake")
        }
    }
}
