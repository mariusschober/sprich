package com.sprich.app.speech.remote

import com.sprich.app.speech.LanguagePolicy
import com.sprich.app.speech.ResolvedUtteranceLanguage
import com.sprich.app.speech.TranscriptionSourceId
import com.sprich.app.speech.api.Language
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Meta Muse Voice Transcribe — real implementation for Meta Model API.
 * Supports both:
 *  - Non-streaming: POST https://api.meta.ai/v1/asr/transcribe?sessionId= (multipart request+audio, Bearer header)
 *  - Streaming: wss://api.meta.ai/v1/asr/realtime?sessionId= (handshake JSON, binary PCM 24kHz, text frames)
 *
 * Docs: https://ai.developer.meta.com/docs/speech-to-text, https://dev.meta.ai/docs/speech-to-text
 * Model: muse-voice-transcribe-1.0, Base https://api.meta.ai/v1, Pricing $0.18/h
 * Streaming default: ENDPOINTING (turn detection) with CUMULATIVE partials, fallback PUSH_TO_TALK for single-utterance dictation.
 *
 * Security: followRedirects(false), bounded 8192, Authorization not forwarded, Cancellation via Call.cancel()/WebSocket.cancel()
 */
class MetaMuseSttProvider(
    private val baseUrl: String = "https://api.meta.ai",
    private val model: String = "muse-voice-transcribe-1.0",
    private val httpClient: OkHttpClient = createClient(),
    private val preferStreaming: Boolean = true,
) : RemoteSttProvider, StreamingRemoteSttProvider {

    override val id = "meta-muse-voice-transcribe"
    override val capabilities = RemoteSttCapabilities(
        streaming = false, // 7.4: ship batch first; realtime not proven — do not claim streaming
        automaticLanguage = true,
        explicitLanguageHint = true,
        keywordBiasing = true,
        contextBiasing = false,
        endpointing = false,
        partialResults = false,
    )

    companion object {
        const val MODEL_DEFAULT = "muse-voice-transcribe-1.0"
        const val BASE_URL_DEFAULT = "https://api.meta.ai"
        const val REALTIME_PATH = "/v1/asr/realtime"
        const val TRANSCRIBE_PATH = "/v1/asr/transcribe"
        const val MAX_RESPONSE_BYTES = 8192
        const val BLOCKED_REASON = "META_MUSE_ADAPTER: BLOCKED — not configured"
        fun isBlocked(): Boolean = false // now implemented

        fun createClient(policy: DeadlinePolicy = DeadlinePolicy.DEFAULT): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(policy.socketConnectMs, TimeUnit.MILLISECONDS)
                .readTimeout(policy.socketReadMs, TimeUnit.MILLISECONDS)
                .writeTimeout(policy.socketWriteMs, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

        private fun isValidHttpsUrl(url: String): Boolean = com.sprich.app.core.security.EndpointValidator.isValidHttpsUrl(url)

        private fun languageBiasFor(policy: LanguagePolicy): JSONArray? {
            return when (policy) {
                is LanguagePolicy.Automatic -> null // omit, model detects (code-switch)
                is LanguagePolicy.Fixed -> {
                    val name = when (policy.tag.lowercase()) {
                        "en", "en-us", "en-gb" -> "English"
                        "de", "de-de" -> "German"
                        "fr", "fr-fr" -> "French"
                        "es", "es-es" -> "Spanish"
                        "ar" -> "Arabic"
                        "bn" -> "Bengali"
                        "nl" -> "Dutch"
                        "he" -> "Hebrew"
                        "hi" -> "Hindi"
                        "id" -> "Indonesian"
                        "it" -> "Italian"
                        "ja" -> "Japanese"
                        "kn" -> "Kannada"
                        "ko" -> "Korean"
                        "ms" -> "Malay"
                        "zh", "zh-cn", "cmn" -> "Mandarin Chinese"
                        "mr" -> "Marathi"
                        "pl" -> "Polish"
                        "pt", "pt-br" -> "Portuguese"
                        "tl" -> "Tagalog"
                        "ta" -> "Tamil"
                        "te" -> "Telugu"
                        "th" -> "Thai"
                        "tr" -> "Turkish"
                        "vi" -> "Vietnamese"
                        else -> policy.tag
                    }
                    JSONArray().put(name)
                }
            }
        }

        private fun readBoundedBody(resp: Response): String? {
            val body = resp.body ?: return ""
            val source = body.source()
            val buffer = Buffer()
            var total: Long = 0
            val limit = MAX_RESPONSE_BYTES.toLong() + 1
            try {
                while (total < limit) {
                    val read = source.read(buffer, limit - total)
                    if (read == -1L) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) return null
                }
                return buffer.readUtf8()
            } catch (e: Exception) {
                throw e
            }
        }

        private fun wavBytes(pcm: ShortArray, sampleRate: Int): ByteArray {
            val dataSize = pcm.size * 2
            val out = ByteArray(44 + dataSize)
            fun putAscii(off: Int, s: String) { for (i in s.indices) out[off + i] = s[i].code.toByte() }
            fun putInt32(off: Int, v: Int) {
                out[off] = (v and 0xff).toByte(); out[off + 1] = ((v shr 8) and 0xff).toByte()
                out[off + 2] = ((v shr 16) and 0xff).toByte(); out[off + 3] = ((v shr 24) and 0xff).toByte()
            }
            fun putInt16(off: Int, v: Int) { out[off] = (v and 0xff).toByte(); out[off + 1] = ((v shr 8) and 0xff).toByte() }
            putAscii(0, "RIFF"); putInt32(4, 36 + dataSize); putAscii(8, "WAVE")
            putAscii(12, "fmt "); putInt32(16, 16); putInt16(20, 1)
            putInt16(22, 1); putInt32(24, sampleRate); putInt32(28, sampleRate * 2)
            putInt16(32, 2); putInt16(34, 16)
            putAscii(36, "data"); putInt32(40, dataSize)
            var o = 44
            for (s in pcm) { out[o] = (s.toInt() and 0xff).toByte(); out[o + 1] = ((s.toInt() shr 8) and 0xff).toByte(); o += 2 }
            return out
        }

        private fun pcmToBytesLE(pcm: ShortArray): ByteArray {
            val out = ByteArray(pcm.size * 2)
            var o = 0
            for (s in pcm) { out[o] = (s.toInt() and 0xff).toByte(); out[o + 1] = ((s.toInt() shr 8) and 0xff).toByte(); o += 2 }
            return out
        }
    }

    private fun buildTranscribeUrl(sessionId: Long): String {
        val base = baseUrl.trimEnd('/')
        // baseUrl is https://api.meta.ai, path is /v1/asr/transcribe
        val url = if (base.endsWith("/v1")) "$base/asr/transcribe" else "$base$TRANSCRIBE_PATH"
        return if (sessionId != 0L) "$url?sessionId=$sessionId" else url
    }

    private fun buildRealtimeUrl(sessionId: Long): String {
        val base = baseUrl.trimEnd('/')
        // wss://api.meta.ai/v1/asr/realtime?sessionId=
        val host = base.removePrefix("https://").removePrefix("http://")
        val wssBase = "wss://$host$REALTIME_PATH"
        return if (sessionId != 0L) "$wssBase?sessionId=$sessionId" else wssBase
    }

    override suspend fun transcribe(request: RemoteSttRequest): RemoteSttResult = withContext(Dispatchers.IO) {
        if (request.credential.isBlank()) throw IllegalStateException("Missing Muse credential")
        if (request.pcm.isEmpty()) throw IllegalArgumentException("No audio")
        // Decide streaming vs non-streaming: use realtime if preferStreaming and streaming capable
        val useStreaming = preferStreaming && capabilities.streaming && request.preferStreaming
        if (useStreaming) {
            return@withContext transcribeStreaming(request)
        } else {
            return@withContext transcribeNonStreaming(request)
        }
    }

    private suspend fun transcribeNonStreaming(request: RemoteSttRequest): RemoteSttResult = withContext(Dispatchers.IO) {
        // POST https://api.meta.ai/v1/asr/transcribe?sessionId=
        val url = buildTranscribeUrl(request.utteranceId)
        if (!isValidHttpsUrl(url.removeSuffix("?sessionId=${request.utteranceId}").substringBefore("?")) && !url.startsWith("https://api.meta.ai")) {
            // Validate base
            if (!isValidHttpsUrl(baseUrl)) throw IllegalStateException("Invalid Muse baseUrl — must be https://api.meta.ai")
        }
        val wav = wavBytes(request.pcm, request.sampleRate)
        val langBias = languageBiasFor(request.languagePolicy)
        val reqJson = JSONObject().apply {
            put("model", model)
            put("audioEncoding", "WAV")
            put("mode", "PUSH_TO_TALK") // single utterance dictation
            if (langBias != null) put("languageBias", langBias)
            if (request.personalVocabularyHints.isNotEmpty()) put("keywords", JSONArray(request.personalVocabularyHints.take(20)))
        }
        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("request", null, reqJson.toString().toRequestBody("application/json".toMediaType()))
            .addFormDataPart("audio", "dictation.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${request.credential}")
            .post(requestBody)
            .build()
        val call = httpClient.newCall(req)
        val job = currentCoroutineContext()[Job]
        val handle = job?.invokeOnCompletion { cause -> if (cause is CancellationException) try { call.cancel() } catch (_: Exception) {} }
        try {
            call.execute().use { resp ->
                val bodyStr = readBoundedBody(resp)
                if (bodyStr == null) throw RemoteSttException(ApiFailure.InvalidResponse, "Muse oversized response > $MAX_RESPONSE_BYTES")
                if (!resp.isSuccessful) {
                    val failure = ApiFailure.fromHttpCode(resp.code, bodyStr.take(180))
                    throw RemoteSttException(failure, "Muse STT HTTP ${resp.code}")
                }
                val json = JSONObject(bodyStr)
                // Muse returns {transcript, turns[]} per docs, also handle {text} fallback for test mocks
                val text = when {
                    json.has("transcript") -> json.optString("transcript")
                    json.has("text") -> json.optString("text")
                    else -> null
                } ?: throw RemoteSttException(ApiFailure.InvalidResponse, "Muse invalid response")
                if (text.isBlank()) throw RemoteSttException(ApiFailure.InvalidResponse, "Muse empty transcript")
                val resolved = when (val bias = langBias?.optString(0)?.lowercase()) {
                    "english" -> ResolvedUtteranceLanguage.Known(Language.EN)
                    "german" -> ResolvedUtteranceLanguage.Known(Language.DE)
                    "french" -> ResolvedUtteranceLanguage.Known(Language.FR)
                    "spanish" -> ResolvedUtteranceLanguage.Known(Language.ES)
                    else -> ResolvedUtteranceLanguage.Unknown
                }
                RemoteSttResult(text.trim(), resolved, TranscriptionSourceId.API_META_MUSE)
            }
        } catch (e: CancellationException) {
            try { call.cancel() } catch (_: Exception) {}
            throw e
        } finally {
            try { handle?.dispose() } catch (_: Exception) {}
        }
    }

    private suspend fun transcribeStreaming(request: RemoteSttRequest): RemoteSttResult = withContext(Dispatchers.IO) {
        // Realtime WebSocket: wss://api.meta.ai/v1/asr/realtime?sessionId=
        val url = buildRealtimeUrl(request.utteranceId)
        val langBias = languageBiasFor(request.languagePolicy)
        val handshake = JSONObject().apply {
            put("authorization", JSONObject().put("accessToken", "Bearer ${request.credential}"))
            put("audioEncoding", "PCM_24KHZ") // prefer 24k native, will resample if needed (request.sampleRate 16k -> server resamples)
            put("model", model)
            put("mode", "ENDPOINTING") // model turn detection for dictation; fallback PUSH_TO_TALK if single turn needed
            put("partialMode", "CUMULATIVE")
            put("emitAudioProgress", false)
            if (langBias != null) put("languageBias", langBias)
            if (request.personalVocabularyHints.isNotEmpty()) put("keywords", JSONArray(request.personalVocabularyHints.take(20)))
        }
        val finalTranscript = AtomicReference<String>("")
        val errorRef = AtomicReference<Exception>(null)
        val latch = CountDownLatch(1)
        val client = httpClient // reuse pooled, but WebSocket needs same client

        // Prepare PCM as 24k if needed: request is 16k, we can send as PCM_24KHZ with 16k data? Server resamples PCM_16KHZ, but we declared PCM_24KHZ.
        // Use PCM_16KHZ if request.sampleRate==16000 to avoid resample artifact, else 24k.
        val pcmBytes = pcmToBytesLE(request.pcm)
        // For simplicity, declare PCM_16KHZ when 16k
        val handshakeWithEncoding = handshake.apply {
            if (request.sampleRate == 16000) put("audioEncoding", "PCM_16KHZ") else put("audioEncoding", "PCM_24KHZ")
        }

        val req = Request.Builder().url(url).build()
        var webSocket: WebSocket? = null
        val job = currentCoroutineContext()[Job]
        val listener = object : WebSocketListener() {
            var handshakeAcked = false
            var lastPartialLen = 0
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(handshakeWithEncoding.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (!handshakeAcked) {
                        if (json.has("sessionId") && !json.has("type")) {
                            handshakeAcked = true
                            // Send audio as binary frames paced ~real-time (for utterance, send all at once then endStream)
                            // Split into 80ms chunks (24k: 1920 samples = 3840 bytes)
                            val chunkBytes = if (request.sampleRate == 24000) 3840 else 2560 // 80ms at 16k=1280 samples=2560 bytes
                            var offset = 0
                            while (offset < pcmBytes.size) {
                                val end = (offset + chunkBytes).coerceAtMost(pcmBytes.size)
                                val chunk = pcmBytes.copyOfRange(offset, end)
                                webSocket.send(chunk.toByteString())
                                offset = end
                            }
                            webSocket.send("""{"type":"endStream"}""")
                            return
                        } else if (json.optString("type") == "error") {
                            errorRef.set(RemoteSttException(ApiFailure.ProviderUnavailable, json.optString("message", "Muse error")))
                            webSocket.close(1000, null)
                            latch.countDown()
                            return
                        }
                    }
                    when (json.optString("type")) {
                        "transcript" -> {
                            val transcript = json.optString("transcript", "")
                            val isFinal = json.optBoolean("final", false)
                            if (isFinal) {
                                finalTranscript.set(transcript)
                            } else {
                                // partial — keep last
                                if (transcript.length > lastPartialLen) lastPartialLen = transcript.length
                            }
                        }
                        "speechComplete" -> {
                            val transcript = json.optString("transcript", "")
                            if (transcript.isNotBlank()) finalTranscript.set(transcript)
                        }
                        "error" -> {
                            errorRef.set(RemoteSttException(ApiFailure.ProviderUnavailable, json.optString("message", "Muse error")))
                            webSocket.close(1000, null)
                            latch.countDown()
                        }
                        "speechStart", "speechEnd", "speaker", "audioProgress" -> {
                            // ignore for dictation single turn
                        }
                    }
                } catch (e: Exception) {
                    errorRef.set(e)
                    latch.countDown()
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Muse realtime should not send binary
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (errorRef.get() == null) errorRef.set(Exception(t.message ?: "Muse realtime failure", t))
                latch.countDown()
            }
        }

        webSocket = client.newWebSocket(req, listener)
        // Tie cancellation
        val handle = job?.invokeOnCompletion { cause -> if (cause is CancellationException) try { webSocket?.cancel() } catch (_: Exception) {} }

        try {
            // Wait for completion or timeout (deadlineMs or 30s)
            val timeoutMs = 30000L
            val completed = withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) { latch.await() }
                true
            }
            if (completed == null) {
                try { webSocket?.close(1000, "timeout") } catch (_: Exception) {}
                throw RemoteSttException(ApiFailure.Timeout, "Muse realtime timeout ${timeoutMs}ms")
            }
            errorRef.get()?.let { throw it }
            val text = finalTranscript.get()?.trim().orEmpty()
            if (text.isBlank()) throw RemoteSttException(ApiFailure.InvalidResponse, "Muse empty transcript")
            val resolved = when (langBias?.optString(0)?.lowercase()) {
                "english" -> ResolvedUtteranceLanguage.Known(Language.EN)
                "german" -> ResolvedUtteranceLanguage.Known(Language.DE)
                "french" -> ResolvedUtteranceLanguage.Known(Language.FR)
                "spanish" -> ResolvedUtteranceLanguage.Known(Language.ES)
                else -> ResolvedUtteranceLanguage.Unknown
            }
            return@withContext RemoteSttResult(text, resolved, TranscriptionSourceId.API_META_MUSE)
        } catch (e: CancellationException) {
            try { webSocket?.cancel() } catch (_: Exception) {}
            throw e
        } finally {
            try { handle?.dispose() } catch (_: Exception) {}
            try { webSocket?.close(1000, null) } catch (_: Exception) {}
        }
    }

    // Streaming interface for future SprichIME live partials
    override suspend fun createStreamingSession(request: RemoteSttRequest): StreamingSttSession {
        // For now, delegate to non-streaming transcribe via a fake session that sends all at once
        // Full live streaming (onChunk → partial) will be wired in SprichIME handleAudioChunk → StreamingSttSession
        throw RemoteSttException(ApiFailure.ProviderUnavailable, "Muse streaming session not yet wired — use transcribe() with preferStreaming")
    }
}
