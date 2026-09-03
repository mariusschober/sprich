package com.sprich.app.speech

import com.sprich.app.api.ApiException
import com.sprich.app.api.ApiHttp
import com.sprich.app.speech.remote.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Contract fixtures from Meta's published voice schemas. These are not live provider acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetaVoiceContractTest {
    private fun request(options: VoiceApiOptions = VoiceApiOptions()) = RemoteSttRequest(
        shortArrayOf(0, 32767, -32768, -1), 16000, LanguagePolicy.Fixed("de"),
        listOf("Sprich", "Movetrics", "Sprich", "bad\nterm"), 47, "fixture-key", options = options)

    private fun event(type: String, id: Int? = null, text: String? = null, stable: Boolean? = null, label: String? = null) = JSONObject().apply {
        put("type", type); put("audioProcessedMs", 400)
        id?.let { put("turnId", it) }; text?.let { put("transcript", it) }
        stable?.let { put("final", it) }; label?.let { put("label", it) }
    }.toString()

    @Test fun oneBiasContractForBothTransportsWithoutLeakingCredentialsIntoRecordingMetadata() {
        val selected = request(VoiceApiOptions(streaming = true, languageHints = setOf("en", "de")))
        val live = MetaVoiceProtocol.settings("muse-voice-transcribe-1.0", selected, true)
        val batch = MetaVoiceProtocol.settings("muse-voice-transcribe-1.0", selected, false)
        assertEquals("Bearer fixture-key", live.getJSONObject("authorization").getString("accessToken"))
        assertTrue(live.getBoolean("zdrOverride"))
        assertEquals("PCM_16KHZ", live.getString("audioEncoding"))
        assertEquals("WAV", batch.getString("audioEncoding"))
        assertEquals("ENDPOINTING", live.getString("mode"))
        assertEquals("CUMULATIVE", live.getString("partialMode"))
        assertEquals("[\"German\",\"English\"]", live.getJSONArray("languageBias").toString())
        assertEquals(live.getJSONArray("languageBias").toString(), batch.getJSONArray("languageBias").toString())
        assertEquals("[\"Sprich\",\"Movetrics\"]", live.getJSONArray("keywords").toString())
        assertFalse(batch.toString().contains("fixture-key"))
        assertFalse(request().toString().contains("fixture-key"))
    }

    @Test fun revisedPartialsReplaceAndSpeechEndDoesNotAuthorizeFinalText() {
        val events = MetaVoiceProtocol.Events(VoiceApiMode.ENDPOINTING)
        events.accept(event("speechStart", 7))
        events.accept(event("transcript", text = "fifty", stable = false))
        assertEquals("fifteen", events.accept(event("transcript", text = "fifteen", stable = true)).preview)
        events.accept(event("speechEnd", 7))
        assertThrows(ApiException::class.java) { events.finish() }
        events.accept(event("speechComplete", 7, "Fifteen euros."))
        assertEquals("Fifteen euros.", events.finish())
    }

    @Test fun nextTurnCanOpenBeforePriorCompletionWithoutReorderingOrDroppingItsPartial() {
        val events = MetaVoiceProtocol.Events(VoiceApiMode.ENDPOINTING)
        events.accept(event("speechStart", 90))
        events.accept(event("transcript", text = "first", stable = false))
        events.accept(event("speechEnd", 90))
        events.accept(event("speechStart", 2))
        events.accept(event("transcript", text = "second", stable = false))
        val update = events.accept(event("speechComplete", 90, "First."))
        assertEquals("First. second", update.preview)
        assertEquals(1, update.activeTurns)
        events.accept(event("speechComplete", 2, "Second."))
        assertEquals("First. Second.", events.finish())
        events.accept(event("speechComplete", 90, "First.")) // Identical repeats never append twice.
        assertEquals("First. Second.", events.finish())
        assertThrows(ApiException::class.java) { events.accept(event("speechComplete", 90, "Changed.")) }
    }

    @Test fun speakerLabelOnlyDecoratesThePrecedingExactSpan() {
        val events = MetaVoiceProtocol.Events(VoiceApiMode.DIARIZATION)
        events.accept(event("speechStart", 1))
        events.accept(event("transcript", text = "Hello.", stable = false))
        events.accept(event("speaker", label = "A"))
        events.accept(event("speechComplete", 1, "Hello."))
        events.accept(event("speechStart", 2))
        events.accept(event("transcript", text = "Goodbye", stable = false))
        events.accept(event("speaker", label = "B"))
        events.accept(event("speechComplete", 2, "Good morning."))
        assertEquals("A: Hello.\nGood morning.", events.finish()) // Never guess labels for corrected spans.
    }

    @Test fun bufferedSchemaRequiresWholeClipAuthorityAndLabelsOnlyMatchingSpans() {
        val json = """{"sessionId":"fixture","audioDurationMs":1200,"transcript":"Hello. Goodbye.","turns":[{"turnId":1,"startMs":0,"endMs":500,"transcript":"Hello.","speaker":"A"},{"turnId":2,"startMs":600,"endMs":1200,"transcript":"Goodbye.","speaker":"B"}]}"""
        assertEquals("A: Hello.\nB: Goodbye.", MetaVoiceProtocol.buffered(json, VoiceApiMode.DIARIZATION))
        assertEquals("Revised.", MetaVoiceProtocol.buffered(json.replace("Hello. Goodbye.", "Revised."), VoiceApiMode.DIARIZATION))
        assertThrows(ApiException::class.java) { MetaVoiceProtocol.buffered("""{"text":"not the documented contract"}""", VoiceApiMode.PUSH_TO_TALK) }
    }

    @Test fun providerExplanationIsBoundedRedactedAndAbsentFromExceptionMessages() {
        val key = "fixture-credential-".repeat(160)
        val json = JSONObject().put("message", "Unsupported mode for $key.\nTry Bearer another-fixture-key again.")
            .put("transcript", "private dictation must not be displayed as an error")
        val failure = MetaVoiceProtocol.error(json, key)
        assertEquals(ApiFailure.ModelUnavailable, failure.failure)
        assertTrue(failure.publicDetail!!.contains("Unsupported mode"))
        assertFalse(failure.publicDetail!!.contains("fixture-credential"))
        assertFalse(failure.publicDetail!!.contains("another-fixture-key"))
        assertFalse(failure.publicDetail!!.contains("private dictation"))
        assertFalse(failure.toString().contains("Unsupported mode"))
        val long = MetaVoiceProtocol.error(JSONObject().put("message", "x".repeat(1000)), "")
        assertEquals(360, long.publicDetail!!.length)
    }

    @Test fun websocketUsesDocumentedHandshakeRawPcmAndEndStreamBeforeAcceptingNormalClose() = runBlocking {
        val server = MockWebServer()
        val handshake = CompletableDeferred<JSONObject>()
        val audio = CompletableDeferred<ByteArray>()
        val client = ApiHttp.client.newBuilder().readTimeout(4, TimeUnit.SECONDS).build()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                val json = JSONObject(text)
                if (json.optString("type") == "endStream") {
                    socket.send(event("speechEnd", 14))
                    socket.send(event("speechComplete", 14, "Hello."))
                    socket.close(1000, null)
                } else { handshake.complete(json); socket.send("""{"sessionId":"fixture"}""") }
            }
            override fun onMessage(socket: WebSocket, bytes: ByteString) {
                audio.complete(bytes.toByteArray())
                socket.send(event("speechStart", 14))
                socket.send(event("transcript", text = "Hello", stable = false))
            }
        }))
        server.start()
        val provider = MetaMuseSttProvider(server.url("/").toString(), httpClient = client)
        val session = provider.createStreamingSession(request(VoiceApiOptions(streaming = true)))
        try {
            withTimeout(4000) {
                session.start(); session.sendAudio(request().pcm)
                val result = session.finish()
                assertEquals("Hello.", result.text)
                assertEquals(ResolvedUtteranceLanguage.Unknown, result.resolvedLanguage)
                assertEquals("Bearer fixture-key", handshake.await().getJSONObject("authorization").getString("accessToken"))
                assertArrayEquals(byteArrayOf(0, 0, -1, 127, 0, -128, -1, -1), audio.await())
            }
            val http = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/v1/asr/realtime", http.path)
            assertNull(http.getHeader("Authorization"))
        } finally { session.cancel(); server.shutdown() }
    }

    @Test fun websocketEarlyNormalCloseCannotPromoteAPartialToAResult() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                if (JSONObject(text).optString("type") == "endStream") {
                    socket.send(event("transcript", text = "unfinished", stable = false)); socket.close(1000, null)
                } else socket.send("""{"sessionId":"fixture"}""")
            }
        }))
        server.start()
        val session = MetaMuseSttProvider(server.url("/").toString()).createStreamingSession(request(VoiceApiOptions(streaming = true, detectTurns = false)))
        try {
            withTimeout(4000) {
                session.start()
                try { session.finish(); fail("Partial accepted") } catch (e: ApiException) { assertEquals(ApiFailure.InvalidResponse, e.failure) }
            }
        } finally { session.cancel(); server.shutdown() }
    }

    @Test fun revocationCancelsOpenSocketAndPendingResultWithoutRetry() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                if (JSONObject(text).has("authorization")) socket.send("""{"sessionId":"fixture"}""")
            }
        }))
        server.start()
        val session = MetaMuseSttProvider(server.url("/").toString()).createStreamingSession(request(VoiceApiOptions(streaming = true)))
        try {
            withTimeout(4000) {
                session.start()
                ApiHttp.revoke()
                try { session.finish(); fail("Revoked result accepted") } catch (_: CancellationException) { }
            }
            assertEquals(1, server.requestCount)
        } finally { session.cancel(); server.shutdown() }
    }

    @Test fun recordingSseReportsRealProgressAndRejectsTruncatedOrFatalEvents() = runBlocking {
        val server = MockWebServer()
        server.start()
        val updates = mutableListOf<RemoteTranscriptUpdate>()
        val provider = MetaMuseSttProvider(server.url("/").toString())
        val body = listOf(event("speechStart", 1), event("transcript", text = "Hallo", stable = true), event("speechComplete", 1, "Hallo.")).joinToString("") { "data: $it\n\n" }
        try {
            server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream").setBody(body))
            val result = provider.transcribe(request().copy(onProgress = { synchronized(updates) { updates.add(it) } }))
            assertEquals("Hallo.", result.text)
            assertTrue(updates.any { it.stage == VoiceApiStage.UPLOADING && it.uploadedBytes == it.totalUploadBytes })
            assertTrue(updates.any { it.preview == "Hallo" })
            val http = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("text/event-stream", http.getHeader("Accept"))
            assertEquals("Bearer fixture-key", http.getHeader("Authorization"))
            assertTrue(http.body.readUtf8().contains("German"))
            for (invalid in listOf(body.trimEnd(), "data: {\"type\":\"error\",\"message\":\"Unsupported mode\",\"sessionId\":\"fixture\"}\n\n")) {
                server.enqueue(MockResponse().addHeader("Content-Type", "text/event-stream").setBody(invalid))
                try { provider.transcribe(request().copy(onProgress = {})); fail("Incomplete request accepted") } catch (_: ApiException) { }
            }
        } finally { server.shutdown() }
    }
}
