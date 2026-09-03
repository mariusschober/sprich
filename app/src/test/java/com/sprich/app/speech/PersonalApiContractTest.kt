package com.sprich.app.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.api.*
import com.sprich.app.ai.OpenAiCompatibleRefinementProvider
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.refinement.*
import com.sprich.app.speech.remote.*
import com.sprich.app.storage.ApiSecretStore
import com.sprich.app.storage.FakeSecretCryptoBackend
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Local HTTP fixtures prove adapter behavior only. Live provider quality is measured on the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonalApiContractTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().build()
    private val completed = """{"status":"completed","steps":[{"type":"model_output","content":[{"type":"thought","text":"private reasoning"},{"type":"text","text":"Hello world."}]}]}"""
    private val chat = """{"choices":[{"finish_reason":"stop","message":{"content":"Hello world."}}]}"""
    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); server.shutdown() }
    private fun base() = server.url("/").toString().trimEnd('/')
    private fun request() = RefinementRequest("hello world", "en", RefinementMode.CLEAN_DICTATION)

    @Test fun cleanupDeadlineIncludesAStalledResponseBody() = runBlocking {
        server.enqueue(MockResponse().setBody(chat).setBodyDelay(3, TimeUnit.SECONDS))
        val provider = OpenAiCompatibleRefinementProvider(base(), "test", "dummy", client)
        val start = System.nanoTime()
        assertNull(withTimeoutOrNull(250) { provider.refine(request()) })
        assertTrue("Deadline waited for the body", (System.nanoTime() - start) / 1_000_000 < 1000)
    }

    @Test fun openAiUsesStatelessNonReasoningTextAndPreservesLiteralQuotes() = runBlocking {
        server.enqueue(MockResponse().setBody(chat.replace("Hello world.", "\\\"Hello world.\\\"")))
        val provider = OpenAiCompatibleRefinementProvider(base(), "gpt-5.6-luna", "dummy", client, "openai")
        assertEquals("\"Hello world.\"", provider.refine(request()).text)
        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(sent.getBoolean("store"))
        assertEquals("none", sent.getString("reasoning_effort"))
        assertFalse(sent.has("tools"))
        assertFalse(sent.has("stream"))
        assertEquals(2, sent.getJSONArray("messages").length())
        assertEquals("hello world", JSONObject(sent.getJSONArray("messages").getJSONObject(1).getString("content")).getString("transcript"))
    }

    @Test fun geminiUsesTheActualRestStepsAndDoesNotRequestStoredInteractions() = runBlocking {
        server.enqueue(MockResponse().setBody(completed))
        assertEquals("Hello world.", GeminiRefinementProvider(base(), "gemini-3.5-flash-lite", "dummy", client).refine(request()).text)
        val recorded = server.takeRequest()
        val sent = JSONObject(recorded.body.readUtf8())
        assertEquals("/v1beta/interactions", recorded.path)
        assertEquals("dummy", recorded.getHeader("x-goog-api-key"))
        assertFalse(sent.getBoolean("store"))
        assertEquals("minimal", sent.getJSONObject("generation_config").getString("thinking_level"))
        assertEquals("none", sent.getJSONObject("generation_config").getString("thinking_summaries"))
        for (bad in listOf(completed.replace("completed", "incomplete"), """{"status":"completed","output_text":"SDK convenience"}""")) {
            try { GeminiResponse.text(bad); fail("Incomplete/unsupported response accepted") } catch (e: ApiException) { assertEquals(ApiFailure.InvalidResponse, e.failure) }
        }
    }

    @Test fun metaReservesRoomForReasoningAndIdentifiesTruncatedAnswers() = runBlocking {
        server.enqueue(MockResponse().setBody(chat))
        val provider = OpenAiCompatibleRefinementProvider(base(), "muse-spark-1.3", "dummy", client, "meta-muse-voice-transcribe")
        assertEquals("Hello world.", provider.refine(request()).text)
        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("minimal", sent.getString("reasoning_effort"))
        assertTrue(sent.getInt("max_tokens") > DictationPrompt.outputBudget(request().text))
        assertEquals("developer", sent.getJSONArray("messages").getJSONObject(0).getString("role"))
        server.enqueue(MockResponse().setBody(chat.replace("stop", "length")))
        try { provider.refine(request()); fail("Truncated answer accepted") }
        catch (e: ApiException) { assertEquals(ApiFailure.OutputLimit, e.failure) }
    }

    @Test fun geminiVoiceSendsFlatInlineAudioAndTheVerbatimModeObject() = runBlocking {
        server.enqueue(MockResponse().setBody(completed))
        GeminiSttProvider(base(), httpClient = client).transcribe(RemoteSttRequest(ShortArray(1600), 16000, LanguagePolicy.Fixed("de"), listOf("Sprich"), 1, "dummy"))
        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(sent.getBoolean("store"))
        assertEquals("audio", sent.getJSONArray("input").getJSONObject(0).getString("type"))
        val config = sent.getJSONObject("generation_config").getJSONObject("transcription_config")
        assertEquals("verbatim", config.getJSONObject("mode").getString("type"))
        assertEquals("de-DE", config.getJSONArray("language_codes").getString(0))
    }

    @Test fun officialVoiceUsesItsCurrentLanguageAndKeywordFields() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"Hallo","languages":[{"code":"de"}]}"""))
        val result = OpenAiCompatibleSttProvider(base(), "gpt-transcribe", client, true).transcribe(
            RemoteSttRequest(ShortArray(1600), 16000, LanguagePolicy.Fixed("de"), listOf("Sprich", "bad\r\nhint"), 2, "dummy"))
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("name=\"languages[]\""))
        assertTrue(sent.contains("name=\"keywords[]\""))
        assertFalse(sent.contains("name=\"language\""))
        assertFalse(sent.contains("bad\r\nhint"))
        assertTrue(result.resolvedLanguage is ResolvedUtteranceLanguage.Known)
    }

    @Test fun factoryRejectsRedirectsEvenWithAPermissiveInjectedClient() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/elsewhere")))
        val config = RefinementConfig("custom", base(), "test", RefinementMode.CLEAN_DICTATION, 1000, "bound_test")
        try { RefinementProviderFactory.create(config, "dummy", client).refine(request()); fail("Redirect followed") }
        catch (e: ApiException) { assertEquals(ApiFailure.Http(307), e.failure) }
        assertEquals(1, server.requestCount)
    }

    @Test fun revokedApiCannotFallBackToLocalWork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secrets = ApiSecretStore(context, FakeSecretCryptoBackend())
        val ref = secrets.saveBoundSecret("mock", "https://example.test", "dummy")!!
        val local = CoordinatorExtendedTest.CountingLocal(context)
        val remote = object : RemoteSttProvider {
            override val id = "mock"
            override val capabilities = RemoteSttCapabilities()
            override suspend fun transcribe(request: RemoteSttRequest): RemoteSttResult {
                ApiHttp.revoke()
                throw ApiException(ApiFailure.ProviderUnavailable)
            }
        }
        val config = RemoteSttConfig("mock", "https://example.test", "test", LanguagePolicy.Automatic, 1000, ref)
        val plan = UtterancePlan(TranscriptionPlan.ApiPrimary(config, LocalAsrRoute.AutomaticFastConformer), RefinementPlan.Off, SpeechSessionConfig())
        try { TranscriptionCoordinator(local, mapOf("mock" to remote), secrets).transcribe(ShortArray(1600), plan); fail("Revoked work completed") }
        catch (_: CancellationException) { assertEquals(0, local.transcribeCalls) }
    }
}
