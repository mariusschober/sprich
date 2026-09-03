package com.sprich.app.speech.remote

import com.sprich.app.api.ApiHttp
import com.sprich.app.api.GeminiResponse
import com.sprich.app.api.readApiBody
import com.sprich.app.core.security.EndpointValidator
import com.sprich.app.speech.LanguagePolicy
import com.sprich.app.speech.ResolvedUtteranceLanguage
import com.sprich.app.speech.TranscriptionSourceId
import com.sprich.app.speech.api.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Completed utterance, inline audio, stateless Interactions request. No Files upload or live-session fallback. */
class GeminiSttProvider(
    private val baseUrl: String = BASE_URL_DEFAULT,
    private val model: String = MODEL_DEFAULT,
    private val httpClient: OkHttpClient = ApiHttp.client,
    @Suppress("UNUSED_PARAMETER") preferStreaming: Boolean = false,
) : RemoteSttProvider {
    override val id = "gemini"
    override val capabilities = RemoteSttCapabilities(keywordBiasing = true)

    companion object {
        const val MODEL_DEFAULT = "gemini-3.5-transcribe"
        const val BASE_URL_DEFAULT = "https://generativelanguage.googleapis.com"
    }

    override suspend fun transcribe(request: RemoteSttRequest): RemoteSttResult = withContext(Dispatchers.IO) {
        require(EndpointValidator.isValidHttpsUrl(baseUrl) && request.credential.isNotBlank()) { "API is not configured" }
        require(request.pcm.isNotEmpty() && request.pcm.size <= 1_920_000 && request.sampleRate == 16_000) { "Invalid audio" }
        val language = (request.languagePolicy as? LanguagePolicy.Fixed)?.tag?.substringBefore('-')
        val languageTag = mapOf("en" to "en-US", "de" to "de-DE", "es" to "es-ES", "fr" to "fr-FR")[language]
        val config = JSONObject().put("mode", JSONObject().put("type", "verbatim"))
        languageTag?.let { config.put("language_codes", JSONArray().put(it)) }
        if (request.personalVocabularyHints.isNotEmpty()) config.put("custom_vocabulary", JSONArray(request.personalVocabularyHints.take(20)))
        val payload = JSONObject().apply {
            put("model", model)
            put("store", false)
            put("input", JSONArray().put(JSONObject().put("type", "audio").put("mime_type", "audio/wav")
                .put("data", android.util.Base64.encodeToString(OpenAiCompatibleSttProvider.wavBytes(request.pcm, request.sampleRate), android.util.Base64.NO_WRAP))))
            put("generation_config", JSONObject().put("transcription_config", config))
        }
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/v1beta/interactions")
            .header("x-goog-api-key", request.credential)
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val start = System.nanoTime()
        val text = GeminiResponse.text(httpClient.newCall(req).readApiBody())
        val resolved = Language.entries.firstOrNull { it != Language.AUTO && it.code == language }
            ?.let { ResolvedUtteranceLanguage.Known(it) } ?: ResolvedUtteranceLanguage.Unknown
        RemoteSttResult(text, resolved, TranscriptionSourceId.API_GEMINI, (System.nanoTime() - start) / 1_000_000)
    }
}
