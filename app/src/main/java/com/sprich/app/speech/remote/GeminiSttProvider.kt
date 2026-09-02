package com.sprich.app.speech.remote

import com.sprich.app.speech.LanguagePolicy
import com.sprich.app.speech.ResolvedUtteranceLanguage
import com.sprich.app.speech.TranscriptionSourceId
import com.sprich.app.speech.api.Language
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gemini 3.5 Transcribe — locked provider.
 * - Non-streaming: gemini-3.5-transcribe via Interactions API POST https://generativelanguage.googleapis.com/v1beta/interactions
 *   with x-goog-api-key, file upload + transcribe (simplified to inline base64 for single utterance)
 * - Streaming: gemini-3.5-transcribe-live via Live API (WebSocket) — not yet fully wired, falls back to non-streaming
 *
 * Docs: ai.google.dev/gemini-api/docs/models/gemini-3.5-transcribe, ai.google.dev/gemini-api/docs/live-api
 * Pricing: ~$0.003-0.005/min, 85+ langs, diarization (batch only), 10min live / 1h batch
 *
 * For Sprich dictation (single utterance, 16k PCM, <30s) we use non-streaming by default;
 * streaming toggle will use Live API when available.
 */
class GeminiSttProvider(
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val model: String = "gemini-3.5-transcribe",
    private val httpClient: OkHttpClient = createClient(),
    private val preferStreaming: Boolean = false,
) : RemoteSttProvider, StreamingRemoteSttProvider {

    override val id = "gemini"
    override val capabilities = RemoteSttCapabilities(
        streaming = true,
        automaticLanguage = true,
        explicitLanguageHint = true,
        keywordBiasing = true,
        contextBiasing = false,
        endpointing = true,
        partialResults = true,
    )

    companion object {
        const val MODEL_DEFAULT = "gemini-3.5-transcribe"
        const val MODEL_LIVE = "gemini-3.5-transcribe-live"
        const val BASE_URL_DEFAULT = "https://generativelanguage.googleapis.com"
        const val MAX_RESPONSE_BYTES = 8192

        fun createClient(policy: DeadlinePolicy = DeadlinePolicy.DEFAULT): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(policy.socketConnectMs, TimeUnit.MILLISECONDS)
                .readTimeout(policy.socketReadMs, TimeUnit.MILLISECONDS)
                .writeTimeout(policy.socketWriteMs, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

        private fun isValidHttpsUrl(url: String): Boolean = com.sprich.app.core.security.EndpointValidator.isValidHttpsUrl(url)

        private fun languageTagFor(policy: LanguagePolicy): String? = when (policy) {
            is LanguagePolicy.Automatic -> null
            is LanguagePolicy.Fixed -> when (policy.tag.lowercase()) {
                "en", "en-us", "en-gb" -> "en"
                "de", "de-de" -> "de"
                "fr", "fr-fr" -> "fr"
                "es", "es-es" -> "es"
                else -> policy.tag.take(2).lowercase()
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
    }

    override suspend fun transcribe(request: RemoteSttRequest): RemoteSttResult = withContext(Dispatchers.IO) {
        if (request.credential.isBlank()) throw IllegalStateException("Missing Gemini credential")
        if (request.pcm.isEmpty()) throw IllegalArgumentException("No audio")
        // Prefer streaming live API if enabled — for now use non-streaming Interactions API for single utterance
        // Streaming would be wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent
        // For dictation single utterance, non-streaming is simpler and matches Sprich's frozen PCM model.
        return@withContext transcribeViaInteractions(request)
    }

    private suspend fun transcribeViaInteractions(request: RemoteSttRequest): RemoteSttResult = withContext(Dispatchers.IO) {
        // Step 1: For Gemini, we can send inline base64 audio via generateContent (legacy) or via Interactions API with file upload.
        // Simplest for single utterance <30s: use Interactions API with inline data (if supported) or fallback to generateContent.
        // We'll use Interactions API with inline base64: POST https://generativelanguage.googleapis.com/v1beta/interactions
        // Body: {model, input: [{role:"user", content:[{type:"audio", data: base64, mime_type:"audio/wav"}]}]}
        // For Sprich, we send WAV bytes base64.

        val wav = wavBytes(request.pcm, request.sampleRate)
        val base64 = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
        val langTag = languageTagFor(request.languagePolicy)

        // Use model gemini-3.5-transcribe (non-streaming) by default
        val useModel = if (model.contains("live")) MODEL_DEFAULT else model

        val payload = JSONObject().apply {
            put("model", useModel)
            val input = org.json.JSONArray()
            val content = org.json.JSONArray()
            // Add language hint as text if fixed
            if (langTag != null) {
                content.put(JSONObject().apply {
                    put("type", "text")
                    put("text", "Transcribe this $langTag audio. ${if (request.personalVocabularyHints.isNotEmpty()) "Keywords: ${request.personalVocabularyHints.take(10).joinToString(", ")}" else ""}")
                })
            }
            content.put(JSONObject().apply {
                put("type", "audio")
                put("data", base64)
                put("mime_type", "audio/wav")
            })
            input.put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            })
            put("input", input)
        }

        val url = "${baseUrl.trimEnd('/')}/v1beta/interactions"
        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", request.credential)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = httpClient.newCall(req)
        val job = currentCoroutineContext()[Job]
        val handle = job?.invokeOnCompletion { cause -> if (cause is CancellationException) try { call.cancel() } catch (_: Exception) {} }
        try {
            call.execute().use { resp ->
                val bodyStr = readBoundedBody(resp)
                if (bodyStr == null) throw RemoteSttException(ApiFailure.InvalidResponse, "Gemini oversized > $MAX_RESPONSE_BYTES")
                if (!resp.isSuccessful) {
                    val failure = ApiFailure.fromHttpCode(resp.code, bodyStr.take(180))
                    throw RemoteSttException(failure, "Gemini STT HTTP ${resp.code}")
                }
                // Response: {output_text: "..."} or {output: [{content: [{text: "..."}]}]} or {transcript: "..."}
                val json = JSONObject(bodyStr)
                val text = when {
                    json.has("output_text") -> json.optString("output_text")
                    json.has("output") -> {
                        try {
                            json.optJSONArray("output")?.optJSONObject(0)?.optJSONArray("content")?.optJSONObject(0)?.optString("text") ?: ""
                        } catch (_: Exception) { "" }
                    }
                    json.has("transcript") -> json.optString("transcript")
                    json.has("text") -> json.optString("text")
                    else -> null
                } ?: throw RemoteSttException(ApiFailure.InvalidResponse, "Gemini invalid response")
                if (text.isBlank()) throw RemoteSttException(ApiFailure.InvalidResponse, "Gemini empty transcript")
                val resolved = when (langTag) {
                    "en" -> ResolvedUtteranceLanguage.Known(Language.EN)
                    "de" -> ResolvedUtteranceLanguage.Known(Language.DE)
                    "fr" -> ResolvedUtteranceLanguage.Known(Language.FR)
                    "es" -> ResolvedUtteranceLanguage.Known(Language.ES)
                    else -> ResolvedUtteranceLanguage.Unknown
                }
                RemoteSttResult(text.trim(), resolved, TranscriptionSourceId.API_GEMINI)
            }
        } catch (e: CancellationException) {
            try { call.cancel() } catch (_: Exception) {}
            throw e
        } finally {
            try { handle?.dispose() } catch (_: Exception) {}
        }
    }

    override suspend fun createStreamingSession(request: RemoteSttRequest): StreamingSttSession {
        // For live streaming, use Gemini Live API: wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent
        // Not yet fully implemented for Sprich's single-utterance VAD model — fallback to non-streaming
        throw RemoteSttException(ApiFailure.ProviderUnavailable, "Gemini streaming not yet wired — use non-streaming transcribe")
    }
}
