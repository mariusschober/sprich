package com.sprich.app.ai

import com.sprich.app.api.ApiException
import com.sprich.app.api.ApiHttp
import com.sprich.app.api.readApiBody
import com.sprich.app.core.security.EndpointValidator
import com.sprich.app.speech.refinement.*
import com.sprich.app.speech.remote.ApiFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Completed text only. No tools, conversation history, stored responses or streaming editor writes. */
class OpenAiCompatibleRefinementProvider(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
    private val client: OkHttpClient = ApiHttp.client,
    private val providerId: String = "openai-compatible",
) : TranscriptRefinementProvider {
    override val id = providerId
    override val capabilities = RefinementCapabilities()

    override suspend fun refine(request: RefinementRequest): RefinementProviderResult = withContext(Dispatchers.IO) {
        require(EndpointValidator.isValidHttpsUrl(baseUrl) && model.isNotBlank() && apiKey.isNotBlank()) { "API is not configured" }
        require(request.text.isNotBlank() && request.text.length <= 16_384) { "Transcript is empty or too long" }
        require(request.promptVersion == DictationPrompt.VERSION) { "Unknown prompt version" }
        val officialOpenAi = providerId == "openai"
        val officialMeta = providerId == "meta-muse-voice-transcribe"
        val payload = JSONObject().apply {
            put("model", model)
            // Meta includes internal reasoning in this allowance, even for a short written answer.
            put(if (officialOpenAi) "max_completion_tokens" else "max_tokens", DictationPrompt.outputBudget(request.text) + if (officialMeta) 512 else 0)
            if (officialOpenAi) {
                put("reasoning_effort", "none")
                put("store", false)
            }
            if (officialMeta) put("reasoning_effort", "minimal")
            // Provider defaults are safer than sending unsupported sampling/latency controls to custom APIs.
            put("messages", JSONArray()
                .put(JSONObject().put("role", if (officialMeta) "developer" else "system").put("content", DictationPrompt.system(request.mode, request.promptVariant)))
                .put(JSONObject().put("role", "user").put("content", DictationPrompt.data(request))))
        }
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val start = System.nanoTime()
        val body = client.newCall(req).readApiBody()
        val text = try {
            val choice = JSONObject(body).getJSONArray("choices").getJSONObject(0)
            if (choice.optString("finish_reason") == "length") throw ApiException(ApiFailure.OutputLimit)
            if (choice.optString("finish_reason") != "stop") throw ApiException(ApiFailure.InvalidResponse)
            val message = choice.getJSONObject("message")
            if (message.optJSONArray("tool_calls")?.length()?.let { it > 0 } == true) throw ApiException(ApiFailure.InvalidResponse)
            (message.opt("content") as? String ?: throw ApiException(ApiFailure.InvalidResponse)).trim().also {
                if (it.isBlank() || it.length > 16_384) throw ApiException(ApiFailure.InvalidResponse)
            }
        } catch (e: ApiException) { throw e }
        catch (_: Exception) { throw ApiException(ApiFailure.InvalidResponse) }
        RefinementProviderResult(text, (System.nanoTime() - start) / 1_000_000)
    }
}

class RefinementException(val failure: ApiFailure, message: String) : Exception(message)
