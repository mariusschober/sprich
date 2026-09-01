package com.sprich.app.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.speech.remote.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])

class RemoteSttMatrixTest {

    @Test
    fun apiPrimarySuccessDoesNotCallLocal() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val mock = MockRemoteSttProvider(nextResult = Result.success(RemoteSttResult("hello from api", ResolvedUtteranceLanguage.Unknown, TranscriptionSourceId.API_MOCK)))
        val local = FakeLocal(ctx, "should-not-be-called")
        // Need credential in secret store — provide mock secret store that returns "key"
        val secretStore = object : com.sprich.app.storage.ApiSecretStore(ctx) {
            override fun loadSecret(id: String): String? = "fake-key"
        }
        val remoteCfg = RemoteSttConfig("mock", "http://mock", "whisper", LanguagePolicy.Automatic, 1000L, "ref")
        val coord2 = TranscriptionCoordinator(local, mapOf("mock" to mock), secretStore, DeadlinePolicy.TEST_SHORT)
        val plan = UtterancePlan(TranscriptionPlan.ApiPrimary(remoteCfg, LocalAsrRoute.AutomaticFastConformer), RefinementPlan.Off, com.sprich.app.speech.api.SpeechSessionConfig())
        val result = coord2.transcribe(ShortArray(16000), plan)
        assertEquals("hello from api", result.text)
        assertEquals(1, mock.transcribeCalls)
        assertEquals(0, local.calls) // local final decode count = 0 on success
    }

    @Test
    fun apiPrimaryFailureFallsBackToLocal() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val mock = FailingMockProvider(ApiFailure.ProviderUnavailable)
        val local = FakeLocal(ctx, "local fallback")
        val secretStore = object : com.sprich.app.storage.ApiSecretStore(ctx) {
            override fun loadSecret(id: String): String? = "fake-key"
        }
        val coord = TranscriptionCoordinator(local, mapOf("mock" to mock), secretStore, DeadlinePolicy.TEST_SHORT)
        val remoteCfg = RemoteSttConfig("mock", "http://mock", "whisper", LanguagePolicy.Automatic, 500L, "ref")
        val plan = UtterancePlan(TranscriptionPlan.ApiPrimary(remoteCfg, LocalAsrRoute.AutomaticFastConformer), RefinementPlan.Off, com.sprich.app.speech.api.SpeechSessionConfig())
        val result = coord.transcribe(ShortArray(16000), plan)
        assertEquals("local fallback", result.text)
        assertEquals(1, mock.calls)
        assertEquals(1, local.calls)
    }

    @Test
    fun localApiFallbackLocalSuccessNoRemote() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val mock = MockRemoteSttProvider()
        val local = FakeLocal(ctx, "local success")
        val coord = TranscriptionCoordinator(local, mapOf("mock" to mock), null, DeadlinePolicy.TEST_SHORT)
        val remoteCfg = RemoteSttConfig("mock", "http://mock", "whisper", LanguagePolicy.Automatic, 500L, "ref")
        val plan = UtterancePlan(TranscriptionPlan.LocalApiFallback(LocalAsrRoute.AccurateCanary(com.sprich.app.speech.api.Language.EN), remoteCfg), RefinementPlan.Off, com.sprich.app.speech.api.SpeechSessionConfig())
        val result = coord.transcribe(ShortArray(16000), plan)
        assertEquals("local success", result.text)
        assertEquals(0, mock.transcribeCalls)
        assertEquals(1, local.calls)
    }

    @Test
    fun localApiFallbackBlankTriggersRemote() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val mock = MockRemoteSttProvider(nextResult = Result.success(RemoteSttResult("remote after blank", ResolvedUtteranceLanguage.Unknown, TranscriptionSourceId.API_MOCK)))
        val local = FakeLocal(ctx, "") // blank
        val secretStore = object : com.sprich.app.storage.ApiSecretStore(ctx) {
            override fun loadSecret(id: String): String? = "fake-key"
        }
        val coord = TranscriptionCoordinator(local, mapOf("mock" to mock), secretStore, DeadlinePolicy.TEST_SHORT)
        val remoteCfg = RemoteSttConfig("mock", "http://mock", "whisper", LanguagePolicy.Automatic, 500L, "ref")
        val plan = UtterancePlan(TranscriptionPlan.LocalApiFallback(LocalAsrRoute.AutomaticFastConformer, remoteCfg), RefinementPlan.Off, com.sprich.app.speech.api.SpeechSessionConfig())
        val result = coord.transcribe(ShortArray(16000), plan)
        assertEquals("remote after blank", result.text)
        assertEquals(1, mock.transcribeCalls)
    }

    @Test
    fun typedFailures() {
        assertTrue(ApiFailure.fromHttpCode(401) is ApiFailure.Authentication)
        assertTrue(ApiFailure.fromHttpCode(429) is ApiFailure.RateLimited)
        assertTrue(ApiFailure.fromHttpCode(500) is ApiFailure.ProviderUnavailable)
        assertTrue(ApiFailure.fromHttpCode(404) is ApiFailure.ModelUnavailable)
        assertEquals("Authentication failed (401/403)", ApiFailure.Authentication.toDisplay())
    }

    // Minimal fake local coordinator for tests
    class FakeLocal(private val ctx: Context, private val text: String = "fake") : LocalTranscriptionCoordinator(
        lid = com.sprich.app.speech.lid.WhisperLidEngine(ctx, com.sprich.app.models.manager.ModelManager(ctx)),
        fast = com.sprich.app.speech.fastconformer.FastConformerEngine(ctx),
        canary = com.sprich.app.speech.canary.CanaryEngine(ctx, com.sprich.app.models.manager.ModelManager(ctx))
    ) {
        var calls = 0
        override suspend fun transcribe(pcm: ShortArray, route: LocalAsrRoute, baseConfig: com.sprich.app.speech.api.SpeechSessionConfig): LocalTranscriptionResult {
            calls++
            return LocalTranscriptionResult(text, ResolvedUtteranceLanguage.Unknown, baseConfig, "fake")
        }
    }
}
