package com.sprich.app.speech.refinement

import com.sprich.app.api.*
import com.sprich.app.core.security.EndpointValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class GeminiRefinementProvider(
    private val endpoint: String,
    private val model: String,
    private val key: String,
    private val client: OkHttpClient = ApiHttp.client,
) : TranscriptRefinementProvider {
    override val id = "gemini"
    override val capabilities = RefinementCapabilities()
    override suspend fun refine(request: RefinementRequest): RefinementProviderResult = withContext(Dispatchers.IO) {
        require(EndpointValidator.isValidHttpsUrl(endpoint) && key.isNotBlank() && model.isNotBlank()) { "API is not configured" }
        require(request.promptVersion == DictationPrompt.VERSION && request.text.length in 1..16_384) { "Invalid writing request" }
        val payload = JSONObject().apply {
            put("model", model)
            put("store", false)
            put("system_instruction", DictationPrompt.system(request.mode, request.promptVariant, request.whisperMode))
            put("input", DictationPrompt.data(request))
            put("generation_config", JSONObject().put("thinking_level", "minimal").put("thinking_summaries", "none")
                .put("max_output_tokens", DictationPrompt.outputBudget(request.text)))
        }
        val req = Request.Builder().url(endpoint.trimEnd('/') + "/v1beta/interactions")
            .header("x-goog-api-key", key).post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val start = System.nanoTime()
        val text = GeminiResponse.text(client.newCall(req).readApiBody())
        RefinementProviderResult(text, (System.nanoTime() - start) / 1_000_000)
    }
}
