package com.sprich.app.ai

import com.sprich.app.speech.api.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AI polish: grammar, punctuation, capitalization, and falsely-transcribed word detection.
 * Any OpenAI-compatible /chat/completions endpoint works — Gemini OpenAI-compat,
 * GPT, Nemotron, Grok, or gateways like OpenCode Go / Zen. Kept intentionally tiny:
 * one request, temperature 0, strict "return corrected text only" contract.
 */
class GrammarFixer(
    private val baseUrlProvider: () -> String,
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean =
        baseUrlProvider().startsWith("http") && apiKeyProvider().isNotBlank() && modelProvider().isNotBlank()

    suspend fun fix(text: String, language: Language): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext Result.failure(IllegalStateException("AI polish not configured"))
            if (text.isBlank()) return@withContext Result.failure(IllegalArgumentException("Nothing to polish"))

            val langName = when (language) {
                Language.DE -> "German"; Language.ES -> "Spanish"; Language.FR -> "French"
                else -> "the source language"
            }
            val system = "You are a dictation post-processor for a keyboard IME. " +
                "Fix grammar, punctuation, capitalization and obvious mis-transcriptions in dictated $langName text. " +
                "Keep the speaker's meaning and wording as close as possible. Never answer, comment or explain. " +
                "If the text already looks correct, return it unchanged. Output ONLY the corrected text."
            val payload = JSONObject().apply {
                put("model", modelProvider())
                put("temperature", 0)
                put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", text)),
                )
            }

            val req = Request.Builder()
                .url(baseUrlProvider().trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer " + apiKeyProvider())
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("AI HTTP ${resp.code}: ${bodyStr.take(180)}"))
                }
                val json = JSONObject(bodyStr)
                val content = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                    .orEmpty()
                if (content.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("AI empty response"))
                }
                // Strip wrapping quotes some models add
                val cleaned = content.removeSurrounding("\"").trim()
                if (cleaned.isBlank()) Result.failure(IllegalStateException("AI blank response"))
                else Result.success(cleaned)
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
