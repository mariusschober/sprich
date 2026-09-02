package com.sprich.app.speech.remote

import com.sprich.app.speech.ResolvedUtteranceLanguage
import com.sprich.app.speech.TranscriptionSourceId
import com.sprich.app.speech.api.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Generic adapter for providers that genuinely implement OpenAI-compatible POST /audio/transcriptions.
 * Response parser accepts only supported schema, bounded response size, no body logging.
 * P0-19: strictly bounded reads (MAX_RESPONSE_BYTES+1), P1-20 strict HTTPS, P1-22 clean cancellation.
 */
class OpenAiCompatibleSttProvider(
    private val baseUrl: String,
    private val model: String,
    private val httpClient: OkHttpClient,
) : RemoteSttProvider {
    override val id = "openai-compatible"
    override val capabilities = RemoteSttCapabilities(
        streaming = false,
        automaticLanguage = true,
        explicitLanguageHint = true,
        keywordBiasing = false,
        contextBiasing = false,
        endpointing = false,
        partialResults = false,
    )

    companion object {
        const val MAX_RESPONSE_BYTES = 8192
        fun wavBytes(pcm: ShortArray, sampleRate: Int): ByteArray {
            val dataSize = pcm.size * 2
            val out = ByteArray(44 + dataSize)
            fun putAscii(offset: Int, s: String) { for (i in s.indices) out[offset + i] = s[i].code.toByte() }
            fun putInt32(offset: Int, v: Int) {
                out[offset] = (v and 0xff).toByte(); out[offset+1] = ((v shr 8) and 0xff).toByte()
                out[offset+2] = ((v shr 16) and 0xff).toByte(); out[offset+3] = ((v shr 24) and 0xff).toByte()
            }
            fun putInt16(offset: Int, v: Int) {
                out[offset] = (v and 0xff).toByte(); out[offset+1] = ((v shr 8) and 0xff).toByte()
            }
            putAscii(0, "RIFF"); putInt32(4, 36 + dataSize); putAscii(8, "WAVE")
            putAscii(12, "fmt "); putInt32(16, 16); putInt16(20, 1)
            putInt16(22, 1); putInt32(24, sampleRate); putInt32(28, sampleRate * 2)
            putInt16(32, 2); putInt16(34, 16)
            putAscii(36, "data"); putInt32(40, dataSize)
            var o = 44
            for (s in pcm) { out[o] = (s.toInt() and 0xff).toByte(); out[o+1] = ((s.toInt() shr 8) and 0xff).toByte(); o += 2 }
            return out
        }

        fun createClient(policy: DeadlinePolicy = DeadlinePolicy.DEFAULT): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(policy.socketConnectMs, TimeUnit.MILLISECONDS)
                .readTimeout(policy.socketReadMs, TimeUnit.MILLISECONDS)
                .writeTimeout(policy.socketWriteMs, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

        fun createWithDefaultClient(baseUrl: String, model: String, policy: DeadlinePolicy = DeadlinePolicy.DEFAULT): OpenAiCompatibleSttProvider =
            OpenAiCompatibleSttProvider(baseUrl, model, createClient(policy))

        private fun isValidHttpsUrl(url: String): Boolean = com.sprich.app.core.security.EndpointValidator.isValidHttpsUrl(url)

        private fun readBoundedBody(resp: okhttp3.Response): String? {
            val body = resp.body ?: return ""
            val source = body.source()
            // Bounded read: at most MAX_RESPONSE_BYTES+1 to detect oversize without allocating huge body
            val buffer = Buffer()
            var total: Long = 0
            val limit = MAX_RESPONSE_BYTES.toLong() + 1
            try {
                while (total < limit) {
                    val read = source.read(buffer, limit - total)
                    if (read == -1L) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) {
                        return null // oversized -> caller will throw InvalidResponse
                    }
                }
                return buffer.readUtf8()
            } catch (e: Exception) {
                // If timeout/disconnect, propagate as IOException for typed mapping
                throw e
            }
        }
    }

    private fun languageCode(policy: com.sprich.app.speech.LanguagePolicy): String = when (policy) {
        is com.sprich.app.speech.LanguagePolicy.Automatic -> ""
        is com.sprich.app.speech.LanguagePolicy.Fixed -> when (policy.tag.lowercase()) {
            "en", "en-us", "en-gb" -> "en"
            "de", "de-de" -> "de"
            "es", "es-es" -> "es"
            "fr", "fr-fr" -> "fr"
            else -> policy.tag.take(2).lowercase()
        }
    }

    override suspend fun transcribe(request: RemoteSttRequest): RemoteSttResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || !isValidHttpsUrl(baseUrl)) throw IllegalStateException("Invalid baseUrl — must be https://")
        if (request.credential.isBlank()) throw IllegalStateException("Missing credential")
        if (request.pcm.isEmpty()) throw IllegalArgumentException("No audio")

        val wav = wavBytes(request.pcm, request.sampleRate)
        val langCode = languageCode(request.languagePolicy)

        val fileBody = wav.toRequestBody("audio/wav".toMediaType())
        var builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "dictation.wav", fileBody)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
        if (langCode.isNotBlank()) builder = builder.addFormDataPart("language", langCode)
        // Keyword biasing not supported for generic OpenAI compat; ignore hints

        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/audio/transcriptions")
            .header("Authorization", "Bearer ${request.credential}")
            .post(builder.build())
            .build()

        // P1-22: structured cancellation — tie OkHttp Call to coroutine Job with disposable handle to avoid retention
        val call = httpClient.newCall(req)
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val handle = job?.invokeOnCompletion { cause ->
            if (cause is kotlinx.coroutines.CancellationException) {
                try { call.cancel() } catch (_: Exception) {}
            }
        }
        try {
            call.execute().use { resp ->
                val bodyStr = readBoundedBody(resp)
                if (bodyStr == null) {
                    throw RemoteSttException(ApiFailure.InvalidResponse, "Oversized response > $MAX_RESPONSE_BYTES")
                }
                if (!resp.isSuccessful) {
                    val failure = ApiFailure.fromHttpCode(resp.code)
                    throw RemoteSttException(failure, "STT HTTP ${resp.code}")
                }
                val text = parseText(bodyStr) ?: throw RemoteSttException(ApiFailure.InvalidResponse, "Invalid response")
                if (text.isBlank()) throw RemoteSttException(ApiFailure.InvalidResponse, "Empty transcript")
                RemoteSttResult(
                    text = text.trim(),
                    resolvedLanguage = when (languageCode(request.languagePolicy)) {
                        "en" -> ResolvedUtteranceLanguage.Known(Language.EN)
                        "de" -> ResolvedUtteranceLanguage.Known(Language.DE)
                        "es" -> ResolvedUtteranceLanguage.Known(Language.ES)
                        "fr" -> ResolvedUtteranceLanguage.Known(Language.FR)
                        else -> ResolvedUtteranceLanguage.Unknown
                    },
                    sourceId = TranscriptionSourceId.API_OPENAI_COMPATIBLE,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            try { call.cancel() } catch (_: Exception) {}
            throw e
        } finally {
            try { handle?.dispose() } catch (_: Exception) {}
        }
    }

    private fun parseText(body: String): String? = try {
        val json = JSONObject(body)
        when {
            json.has("text") -> json.optString("text")
            json.has("transcript") -> json.optString("transcript")
            else -> null
        }
    } catch (_: Exception) {
        // Only accept plain-text bodies that don't look like JSON/HTML
        if (body.isNotBlank() && !body.trimStart().startsWith("<") && !body.trimStart().startsWith("{")) {
            body.trim().takeIf { it.length in 1..4096 }
        } else null
    }
}

class RemoteSttException(val failure: ApiFailure, message: String, cause: Throwable? = null) : Exception(message, cause)
