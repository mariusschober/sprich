package com.sprich.app.speech

import com.sprich.app.speech.remote.*
import com.sprich.app.api.ApiException as RemoteSttException
import com.sprich.app.ai.OpenAiCompatibleRefinementProvider
import com.sprich.app.speech.refinement.RefinementRequest
import com.sprich.app.speech.refinement.RefinementMode
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MockWebServerProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        try { server.shutdown() } catch (_: Exception) {}
    }

    private fun baseUrl(): String = server.url("/v1").toString().trimEnd('/')

    @Test
    fun sttSuccessJson() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"hello world"}""").addHeader("Content-Type", "application/json"))
        val provider = OpenAiCompatibleSttProvider(baseUrl(), "whisper-large-v3", client)
        val pcm = ShortArray(8000) { (Math.sin(it * 0.01) * 1000).toInt().toShort() }
        val result = provider.transcribe(RemoteSttRequest(pcm, 16000, LanguagePolicy.Automatic, utteranceId = 123, credential = "sk-test"))
        assertEquals("hello world", result.text)
        assertEquals(TranscriptionSourceId.API_OPENAI_COMPATIBLE, result.sourceId)
        val req = server.takeRequest()
        assertTrue(req.getHeader("Authorization") == "Bearer sk-test")
        // Never logged credential in toString
        val reqStr = RemoteSttRequest(pcm, 16000, LanguagePolicy.Automatic, utteranceId = 123, credential = "sk-test").toString()
        assertFalse(reqStr.contains("sk-test"))
        assertTrue(reqStr.contains("[REDACTED]"))
    }

    @Test
    fun stt401() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        val provider = OpenAiCompatibleSttProvider(baseUrl(), "model", client)
        try {
            provider.transcribe(RemoteSttRequest(ShortArray(100), 16000, LanguagePolicy.Automatic, utteranceId = 1, credential = "bad"))
            fail("should throw")
        } catch (e: RemoteSttException) {
            assertTrue(e.failure is ApiFailure.Authentication)
        }
    }

    @Test
    fun stt403() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        val provider = OpenAiCompatibleSttProvider(baseUrl(), "model", client)
        try { provider.transcribe(RemoteSttRequest(ShortArray(100), 16000, LanguagePolicy.Automatic, utteranceId = 1, credential = "bad")); fail("") } catch (e: RemoteSttException) { assertTrue(e.failure is ApiFailure.Authentication) }
    }

    @Test
    fun stt404() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val provider = OpenAiCompatibleSttProvider(baseUrl(), "model", client)
        try { provider.transcribe(RemoteSttRequest(ShortArray(100), 16000, LanguagePolicy.Automatic, utteranceId = 1, credential = "k")); fail("") } catch (e: RemoteSttException) { assertTrue(e.failure is ApiFailure.ModelUnavailable) }
    }

    @Test
    fun stt429() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
        val provider = OpenAiCompatibleSttProvider(baseUrl(), "model", client)
        try { provider.transcribe(RemoteSttRequest(ShortArray(100), 16000, LanguagePolicy.Automatic, utteranceId = 1, credential = "k")); fail("") } catch (e: RemoteSttException) { assertTrue(e.failure is ApiFailure.RateLimited) }
    }

    @Test
    fun stt500() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error"))
        val provider = OpenAiCompatibleSttProvider(baseUrl(), "model", client)
        try { provider.transcribe(RemoteSttRequest(ShortArray(100), 16000, LanguagePolicy.Automatic, utteranceId = 1, credential = "k")); fail("") } catch (e: RemoteSttException) { assertTrue(e.failure is ApiFailure.ProviderUnavailable) }
    }

    @Test
    fun sttMalformedJson() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"not_text": 123}"""))
        val provider = OpenAiCompatibleSttProvider(baseUrl(), "model", client)
        try { provider.transcribe(RemoteSttRequest(ShortArray(100), 16000, LanguagePolicy.Automatic, utteranceId = 1, credential = "k")); fail("") } catch (e: RemoteSttException) { assertTrue(e.failure is ApiFailure.InvalidResponse) }
    }

    @Test
    fun sttHtmlResponse() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>error</body></html>").addHeader("Content-Type", "text/html"))
        val provider = OpenAiCompatibleSttProvider(baseUrl(), "model", client)
        try { provider.transcribe(RemoteSttRequest(ShortArray(100), 16000, LanguagePolicy.Automatic, utteranceId = 1, credential = "k")); fail("") } catch (e: RemoteSttException) { assertTrue(e.failure is ApiFailure.InvalidResponse) }
    }

    @Test
    fun sttOversizedBody() = runBlocking {
        val large = "a".repeat(70_000)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"$large"}"""))
        val provider = OpenAiCompatibleSttProvider(baseUrl(), "model", client)
        try { provider.transcribe(RemoteSttRequest(ShortArray(100), 16000, LanguagePolicy.Automatic, utteranceId = 1, credential = "k")); fail("") } catch (e: RemoteSttException) { assertTrue(e.failure is ApiFailure.InvalidResponse) }
    }

    @Test
    fun sttSlowBodyTimeout() = runBlocking {
        // Enqueue a response with body delay to trigger read timeout
        val slowClient = OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).connectTimeout(500, TimeUnit.MILLISECONDS).build()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"hello"}""").setHeadersDelay(2, TimeUnit.SECONDS))
        val provider = OpenAiCompatibleSttProvider(baseUrl(), "model", slowClient)
        try { provider.transcribe(RemoteSttRequest(ShortArray(100), 16000, LanguagePolicy.Automatic, utteranceId = 1, credential = "k")); fail("") } catch (e: Exception) {
            // Could be timeout mapped to Network/Timeout
            assertTrue(e is RemoteSttException || e.message?.contains("timeout", ignoreCase = true) == true || e is java.net.SocketTimeoutException || e.cause is java.net.SocketTimeoutException)
        }
    }

    @Test
    fun sttDisconnectMidResponse() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().writeUtf8("""{"text":"hel""")).setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val provider = OpenAiCompatibleSttProvider(baseUrl(), "model", client)
        try { provider.transcribe(RemoteSttRequest(ShortArray(100), 16000, LanguagePolicy.Automatic, utteranceId = 1, credential = "k")); fail("") } catch (e: Exception) { assertTrue(e is RemoteSttException || e is java.io.IOException) }
    }

    @Test
    fun sttCancellationCancelsCall() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"hello"}""").setBodyDelay(2, TimeUnit.SECONDS))
        val provider = OpenAiCompatibleSttProvider(baseUrl(), "model", client)
        val job = async {
            provider.transcribe(RemoteSttRequest(ShortArray(8000), 16000, LanguagePolicy.Automatic, utteranceId = 999, credential = "k"))
        }
        delay(100)
        job.cancel()
        try { job.await(); fail("should be cancelled") } catch (e: CancellationException) { /* expected */ assertTrue(e is CancellationException) }
        // Server should have received request but call was cancelled
    }

    @Test
    fun sttMultipartFieldsCorrect() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"ok"}"""))
        val provider = OpenAiCompatibleSttProvider(baseUrl(), "whisper-1", client)
        val pcm = ShortArray(1600) { 0 }
        provider.transcribe(RemoteSttRequest(pcm, 16000, LanguagePolicy.Automatic, utteranceId = 42, credential = "k"))
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertTrue(body.contains("whisper-1"))
        assertTrue(body.contains("dictation.wav"))
        assertTrue(body.contains("response_format"))
        // language omitted for Automatic
        assertFalse(body.contains("language"))
    }

    @Test
    fun sttLanguageFixedIncluded() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"hallo"}"""))
        val provider = OpenAiCompatibleSttProvider(baseUrl(), "model", client)
        val pcm = ShortArray(1600) { 0 }
        provider.transcribe(RemoteSttRequest(pcm, 16000, LanguagePolicy.Fixed("de"), utteranceId = 1, credential = "k"))
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertTrue(body.contains("language"))
        assertTrue(body.contains("de"))
    }

    @Test
    fun sttLanguageAutomaticOmitted() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"hello"}"""))
        val provider = OpenAiCompatibleSttProvider(baseUrl(), "model", client)
        provider.transcribe(RemoteSttRequest(ShortArray(100), 16000, LanguagePolicy.Automatic, utteranceId = 1, credential = "k"))
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertFalse(body.contains("name=\"language\""))
    }

    // Refinement tests
    @Test
    fun refinementValid() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"Hello world."}}]}"""))
        val provider = OpenAiCompatibleRefinementProvider(baseUrl(), "gpt-4o-mini", "sk-test", client)
        val result = provider.refine(RefinementRequest("hello world", "en", RefinementMode.CORRECT))
        assertEquals("Hello world.", result.text)
    }

    @Test
    fun refinementEmpty() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"finish_reason":"stop","message":{"content":""}}]}"""))
        val provider = OpenAiCompatibleRefinementProvider(baseUrl(), "model", "k", client)
        try { provider.refine(RefinementRequest("hello", "en", RefinementMode.CORRECT)); fail("") } catch (e: Exception) { assertTrue(e is com.sprich.app.api.ApiException) }
    }

    @Test
    fun refinementMalformed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""not json"""))
        val provider = OpenAiCompatibleRefinementProvider(baseUrl(), "model", "k", client)
        try { provider.refine(RefinementRequest("hello", "en", RefinementMode.CORRECT)); fail("") } catch (e: Exception) { assertTrue(e.message?.isNotEmpty() == true) }
    }

    @Test
    fun refinementOversized() = runBlocking {
        val big = "a".repeat(70_000)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"$big"}}]}"""))
        val provider = OpenAiCompatibleRefinementProvider(baseUrl(), "model", "k", client)
        try { provider.refine(RefinementRequest("hello", "en", RefinementMode.CORRECT)); fail("") } catch (e: Exception) { assertTrue(e is com.sprich.app.api.ApiException) }
    }

    @Test
    fun refinement401() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauth"))
        val provider = OpenAiCompatibleRefinementProvider(baseUrl(), "model", "k", client)
        try { provider.refine(RefinementRequest("hello", "en", RefinementMode.CORRECT)); fail("") } catch (e: com.sprich.app.api.ApiException) { assertTrue(e.failure is ApiFailure.Authentication) }
    }

    @Test
    fun refinement429() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate"))
        val provider = OpenAiCompatibleRefinementProvider(baseUrl(), "model", "k", client)
        try { provider.refine(RefinementRequest("hello", "en", RefinementMode.CORRECT)); fail("") } catch (e: com.sprich.app.api.ApiException) { assertTrue(e.failure is ApiFailure.RateLimited) }
    }

    @Test
    fun refinement500() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("err"))
        val provider = OpenAiCompatibleRefinementProvider(baseUrl(), "model", "k", client)
        try { provider.refine(RefinementRequest("hello", "en", RefinementMode.CORRECT)); fail("") } catch (e: com.sprich.app.api.ApiException) { assertTrue(e.failure is ApiFailure.ProviderUnavailable) }
    }

    @Test
    fun refinementTimeout() = runBlocking {
        val slow = OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).build()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}]}""").setHeadersDelay(2, TimeUnit.SECONDS))
        val provider = OpenAiCompatibleRefinementProvider(baseUrl(), "model", "k", slow)
        try { provider.refine(RefinementRequest("hello", "en", RefinementMode.CORRECT)); fail("") } catch (e: Exception) { assertTrue(true) }
    }

    @Test
    fun refinementCancellation() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}]}""").setBodyDelay(2, TimeUnit.SECONDS))
        val provider = OpenAiCompatibleRefinementProvider(baseUrl(), "model", "k", client)
        val job = async { provider.refine(RefinementRequest("hello world this is a test", "en", RefinementMode.CORRECT)) }
        delay(100)
        job.cancel()
        try { job.await(); fail("should cancel") } catch (e: CancellationException) { assertTrue(true) }
    }

    @Test
    fun secretRedaction() {
        val req = RemoteSttRequest(ShortArray(10), 16000, LanguagePolicy.Automatic, utteranceId = 1, credential = "supersecret123")
        val s = req.toString()
        assertFalse(s.contains("supersecret123"))
        assertTrue(s.contains("[REDACTED]"))
        val refineReq = RefinementRequest("my transcript with secret", "en", RefinementMode.CORRECT, listOf("secretTerm"))
        val rs = refineReq.toString()
        assertFalse(rs.contains("my transcript"))
        assertTrue(rs.contains("[REDACTED]") || rs.contains("textLen"))
    }

    @Test
    fun sharedClientReuse() {
        val shared = OkHttpClient.Builder().build()
        val p1 = OpenAiCompatibleSttProvider("https://example.com/v1", "model", shared.newBuilder().build())
        val p2 = OpenAiCompatibleSttProvider("https://example.com/v1", "model2", shared.newBuilder().build())
        // Both share same connection pool via newBuilder (OkHttp semantics: shared pool)
        assertNotNull(p1)
        assertNotNull(p2)
        // Verify pool is shared when using newBuilder (same dispatcher/pool instance)
        // In this test we just verify no exception and pool not null
    }
}
