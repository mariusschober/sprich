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
import com.sprich.app.api.readApiBody
import com.sprich.app.api.ApiException
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
    private val officialOpenAi: Boolean = false,
) : RemoteSttProvider {
    override val id = "openai-compatible"
    override val capabilities = RemoteSttCapabilities(
        streaming = false,
        automaticLanguage = true,
        explicitLanguageHint = true,
        keywordBiasing = officialOpenAi,
        contextBiasing = false,
        endpointing = false,
        partialResults = false,
    )

    companion object {
        const val MAX_RESPONSE_BYTES = 65_536
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
        require(request.pcm.isNotEmpty() && request.pcm.size <= 1_920_000 && request.sampleRate == 16_000) { "Invalid audio" }

        val wav = wavBytes(request.pcm, request.sampleRate)
        val langCode = languageCode(request.languagePolicy)

        val fileBody = wav.toRequestBody("audio/wav".toMediaType())
        var builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "dictation.wav", fileBody)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
        if (langCode.isNotBlank()) builder.addFormDataPart(if (officialOpenAi) "languages[]" else "language", langCode)
        if (officialOpenAi) request.personalVocabularyHints.take(20)
            .filter { it.isNotBlank() && it.length <= 100 && it.none { c -> c in "<>\r\n" } }
            .forEach { builder.addFormDataPart("keywords[]", it) }

        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/audio/transcriptions")
            .header("Authorization", "Bearer ${request.credential}")
            .post(builder.build())
            .build()

        val start = System.nanoTime()
        val body = httpClient.newCall(req).readApiBody(MAX_RESPONSE_BYTES.toLong())
        val json = try { JSONObject(body) } catch (_: Exception) { throw ApiException(ApiFailure.InvalidResponse) }
        val text = (json.opt("text") as? String)?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 16_384 } ?: throw ApiException(ApiFailure.InvalidResponse)
        val detected = json.optJSONArray("languages")?.let { languages ->
            if (languages.length() == 1) languages.optJSONObject(0)?.optString("code") else null
        }
        val effectiveLanguage = langCode.ifBlank { detected.orEmpty() }
        RemoteSttResult(text,
            Language.entries.firstOrNull { it != Language.AUTO && it.code == effectiveLanguage }
                ?.let { ResolvedUtteranceLanguage.Known(it) } ?: ResolvedUtteranceLanguage.Unknown,
            TranscriptionSourceId.API_OPENAI_COMPATIBLE, (System.nanoTime() - start) / 1_000_000)
    }
}

class RemoteSttException(val failure: ApiFailure, message: String, cause: Throwable? = null) : Exception(message, cause)
