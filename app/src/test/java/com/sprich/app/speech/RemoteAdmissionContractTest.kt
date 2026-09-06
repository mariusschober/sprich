package com.sprich.app.speech

import com.sprich.app.api.ApiException
import com.sprich.app.api.ApiHttp
import com.sprich.app.speech.remote.ApiFailure
import com.sprich.app.speech.remote.DeadlinePolicy
import com.sprich.app.speech.remote.GeminiSttProvider
import com.sprich.app.speech.remote.LiveRemoteUtterance
import com.sprich.app.speech.remote.MetaMuseSttProvider
import com.sprich.app.speech.remote.MetaVoiceProtocol
import com.sprich.app.speech.remote.OpenAiCompatibleSttProvider
import com.sprich.app.speech.remote.RemoteProviderFactory
import com.sprich.app.speech.remote.RemoteSttConfig
import com.sprich.app.speech.remote.RemoteSttRequest
import com.sprich.app.speech.remote.VoiceApiMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P04 admission/pacing/lifetime + 8.2/8.3 first-wave adapter conformance.
 * Fixture contracts: no network, no device, no credentials. Runs under Robolectric
 * because the Meta voice protocol fixture uses org.json (android.jar stub on JVM).
 * Live-provider acceptance for every adapter remains BLOCKED (needs device + credentials).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteAdmissionContractTest {

    private fun request(model: String = "fixture") = RemoteSttRequest(
        ShortArray(0), 16000, LanguagePolicy.Automatic, emptyList(), 7, "fixture-key")

    private fun event(type: String, id: Int? = null, text: String? = null) = JSONObject().apply {
        put("type", type); put("audioProcessedMs", 400)
        id?.let { put("turnId", it) }; text?.let { put("transcript", it) }
    }.toString()

    @Test fun streamingAdmissionDeadlineFitsChunkBufferCapacity() {
        // 64-chunk channel of ~1024-sample capture blocks ~= 4.1 s at 16 kHz (16 samples/ms).
        // Admission must stay strictly below it, or capture overflows mid-handshake.
        for (policy in listOf(DeadlinePolicy.DEFAULT, DeadlinePolicy.TEST_SHORT)) {
            val producibleSamples = policy.streamingConnectionMs * 16
            assertTrue("admission produces $producibleSamples samples, must fit 64x1024 buffer",
                producibleSamples < 64 * 1024)
        }
    }

    @Test fun productionFactoryWiresProductDeadlineIntoStreamingAdmission() {
        val config = RemoteSttConfig("meta-muse-voice-transcribe", "https://api.meta.ai",
            "muse-voice-transcribe-1.0", LanguagePolicy.Automatic, 3500L, "bound_test")
        val provider = RemoteProviderFactory.create(config, OkHttpClient(), DeadlinePolicy.TEST_SHORT)
            as MetaMuseSttProvider
        assertEquals(DeadlinePolicy.TEST_SHORT.streamingConnectionMs, provider.handshakeTimeoutMs)
        assertEquals(DeadlinePolicy.DEFAULT.streamingConnectionMs,
            MetaMuseSttProvider("https://api.meta.ai", "m", OkHttpClient()).handshakeTimeoutMs)
    }

    @Test fun audioCapOverflowIsTypedOutputLimitNeverPartialSuccess() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val config = RemoteSttConfig("meta-muse-voice-transcribe", "https://api.meta.ai",
                "muse-voice-transcribe-1.0", LanguagePolicy.Automatic, 3500L, "bound_test")
            // Suspended credential parks the worker before any I/O, so the overflow below is deterministic.
            val live = LiveRemoteUtterance(scope, config, ApiHttp.currentEpoch, 7, emptyList(),
                credential = { awaitCancellation() })
            assertFalse(live.offerAudio(ShortArray(MetaVoiceProtocol.MAX_AUDIO_SAMPLES + 1)))
            try {
                runBlocking { live.finish(ShortArray(0)) }
                fail("overflow must fail the utterance, never complete partially")
            } catch (e: ApiException) {
                assertEquals(ApiFailure.OutputLimit, e.failure)
            }
            live.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test fun batchAdaptersRejectLiveSessionModelsBeforeAnyIo() = runBlocking {
        val client = OkHttpClient()
        // Official OpenAI batch contract must not accept the live-session model id. [C-OPENAI-LIVE]
        val officialLive = OpenAiCompatibleSttProvider("https://api.openai.com/v1",
            OpenAiCompatibleSttProvider.LIVE_TRANSCRIBE_MODEL, client, officialOpenAi = true)
        try {
            officialLive.transcribe(request()); fail("live model id must not enter the batch contract")
        } catch (e: ApiException) {
            assertEquals(ApiFailure.ModelUnavailable, e.failure)
        }
        // Batch model id still reaches normal validation (empty pcm -> audio rejection, not model rejection).
        val officialBatch = OpenAiCompatibleSttProvider("https://api.openai.com/v1",
            "gpt-transcribe", client, officialOpenAi = true)
        try {
            officialBatch.transcribe(request()); fail("empty audio must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid audio"))
        }
        // Custom endpoints keep their own model namespace: same string is not blocked. [8.3]
        val custom = OpenAiCompatibleSttProvider("https://api.example.com/v1",
            OpenAiCompatibleSttProvider.LIVE_TRANSCRIBE_MODEL, client, officialOpenAi = false)
        try {
            custom.transcribe(request()); fail("empty audio must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid audio"))
        }
        // Gemini batch Interactions contract must not accept the live transcription id. [C-GEMINI-LIVE]
        val geminiLive = GeminiSttProvider(GeminiSttProvider.BASE_URL_DEFAULT,
            GeminiSttProvider.LIVE_TRANSCRIBE_MODEL, client)
        try {
            geminiLive.transcribe(request()); fail("live model id must not enter the batch contract")
        } catch (e: ApiException) {
            assertEquals(ApiFailure.ModelUnavailable, e.failure)
        }
        val geminiBatch = GeminiSttProvider(GeminiSttProvider.BASE_URL_DEFAULT,
            GeminiSttProvider.MODEL_DEFAULT, client)
        try {
            geminiBatch.transcribe(request()); fail("empty audio must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid audio"))
        }
    }

    @Test fun streamingEventStateIsPerSessionNeverSharedAcrossFields() {
        // P04 lifetime: one socket/session owns its reducer; a new field session starts empty.
        val first = MetaVoiceProtocol.Events(VoiceApiMode.ENDPOINTING)
        val second = MetaVoiceProtocol.Events(VoiceApiMode.ENDPOINTING)
        first.accept(event("speechStart", 1))
        first.accept(event("speechComplete", 1, "Hello."))
        assertEquals("Hello.", first.finish())
        // A fresh session completes empty on its own state — never with another field's turns.
        assertEquals("", second.finish())
    }
}
