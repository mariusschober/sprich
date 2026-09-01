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
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Generic adapter for providers that genuinely implement OpenAI-compatible POST /audio/transcriptions.
 * Response parser accepts only supported schema, bounded response size, no body logging.
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
        if (baseUrl.isBlank() || !baseUrl.startsWith("http")) throw IllegalStateException("Invalid baseUrl")
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

        // Structured cancellation: OkHttp call is cancelled when coroutine is cancelled
        val call = httpClient.newCall(req)
        // Tie call cancellation to coroutine cancellation
        try {
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion { cause ->
                if (cause is kotlinx.coroutines.CancellationException) {
                    try { call.cancel() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        call.execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty().take(8192) // bounded
            if (!resp.isSuccessful) {
                val failure = ApiFailure.fromHttpCode(resp.code, bodyStr.take(180))
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

    companion object {
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
                .build()

        fun createWithDefaultClient(baseUrl: String, model: String, policy: DeadlinePolicy = DeadlinePolicy.DEFAULT): OpenAiCompatibleSttProvider =
            OpenAiCompatibleSttProvider(baseUrl, model, createClient(policy))
    }
}

class RemoteSttException(val failure: ApiFailure, message: String, cause: Throwable? = null) : Exception(message, cause)
