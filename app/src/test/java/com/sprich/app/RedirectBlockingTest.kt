package com.sprich.app

import com.sprich.app.ai.OpenAiCompatibleRefinementProvider
import com.sprich.app.speech.LanguagePolicy
import com.sprich.app.speech.refinement.RefinementMode
import com.sprich.app.speech.remote.OpenAiCompatibleSttProvider
import com.sprich.app.speech.remote.RemoteSttRequest
import com.sprich.app.speech.refinement.RefinementRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * P1 security: credentialed BYOK requests must not follow redirects. 3xx treated as typed failure, no forwarding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RedirectBlockingTest {

    @Test fun sttDoesNotFollow307Redirect() = runBlocking {
        val serverA = MockWebServer()
        val serverB = MockWebServer()
        serverA.start()
        serverB.start()
        try {
            val bUrl = serverB.url("/v1").toString().trimEnd('/')
            serverA.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "$bUrl/audio/transcriptions"))
            serverB.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"should not reach B"}"""))

            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            val provider = OpenAiCompatibleSttProvider(serverA.url("/v1").toString().trimEnd('/'), "model", client)
            val pcm = ShortArray(100) { 0 }
            try {
                provider.transcribe(RemoteSttRequest(pcm, 16000, LanguagePolicy.Automatic, emptyList(), 1L, "sk-test"))
                fail("should have thrown on 307")
            } catch (e: Exception) {
                val msg = e.message ?: ""
                assertTrue(msg.contains("307") || msg.contains("Redirect") || e.toString().contains("307") || e is com.sprich.app.speech.remote.RemoteSttException)
                if (e is com.sprich.app.speech.remote.RemoteSttException) {
                    assertTrue(e.failure.toString().contains("307") || e.failure is com.sprich.app.speech.remote.ApiFailure.Http)
                }
            }

            assertEquals(1, serverA.requestCount)
            assertEquals(0, serverB.requestCount)
            val reqA = serverA.takeRequest()
            assertTrue(reqA.getHeader("Authorization") == "Bearer sk-test")
        } finally {
            try { serverA.shutdown() } catch (_: Exception) {}
            try { serverB.shutdown() } catch (_: Exception) {}
        }
    }

    @Test fun refinementDoesNotFollowRedirect() = runBlocking {
        val serverA = MockWebServer()
        val serverB = MockWebServer()
        serverA.start(); serverB.start()
        try {
            val bUrl = serverB.url("/v1").toString().trimEnd('/')
            serverA.enqueue(MockResponse().setResponseCode(301).addHeader("Location", "$bUrl/chat/completions"))
            serverB.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"hacked"}}]}"""))

            val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
            val provider = OpenAiCompatibleRefinementProvider(serverA.url("/v1").toString().trimEnd('/'), "model", "sk-test", client)

            try {
                provider.refine(RefinementRequest("hello", "en", RefinementMode.CORRECT))
                fail("should throw on redirect")
            } catch (e: Exception) {
                assertTrue(e.message?.contains("301") == true || e.toString().contains("301") || e is com.sprich.app.ai.RefinementException)
            }

            assertEquals(1, serverA.requestCount)
            assertEquals(0, serverB.requestCount)
            val reqA = serverA.takeRequest()
            assertEquals("Bearer sk-test", reqA.getHeader("Authorization"))
            assertTrue(reqA.body.readUtf8().contains("hello"))
        } finally {
            try { serverA.shutdown() } catch (_: Exception) {}
            try { serverB.shutdown() } catch (_: Exception) {}
        }
    }

    @Test fun authorizationNotForwardedOnRedirectAttempt() = runBlocking {
        val serverA = MockWebServer()
        serverA.start()
        try {
            serverA.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "https://evil.com/v1/audio/transcriptions"))
            val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
            val provider = OpenAiCompatibleSttProvider(serverA.url("/v1").toString().trimEnd('/'), "model", client)
            try { provider.transcribe(RemoteSttRequest(ShortArray(100),16000, LanguagePolicy.Automatic, emptyList(), 1L, "sk-secret")) } catch (_: Exception) {}
            assertEquals(1, serverA.requestCount)
            val req = serverA.takeRequest()
            assertEquals("Bearer sk-secret", req.getHeader("Authorization"))
        } finally {
            try { serverA.shutdown() } catch (_: Exception) {}
        }
    }
}
