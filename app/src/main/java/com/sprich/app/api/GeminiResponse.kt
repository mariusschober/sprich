package com.sprich.app.api

import com.sprich.app.speech.remote.ApiFailure
import org.json.JSONObject

/** REST Interactions steps, not the SDK-only output_text convenience property. */
object GeminiResponse {
    fun text(body: String): String = try {
        val json = JSONObject(body)
        if (json.optString("status") != "completed") throw ApiException(ApiFailure.InvalidResponse)
        val steps = json.getJSONArray("steps")
        val outputs = buildList {
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                if (step.optString("type") != "model_output") continue
                val parts = step.getJSONArray("content")
                for (j in 0 until parts.length()) {
                    val part = parts.getJSONObject(j)
                    if (part.optString("type") == "text") add(part.opt("text") as? String ?: throw ApiException(ApiFailure.InvalidResponse))
                    else if (part.optString("type") != "thought") throw ApiException(ApiFailure.InvalidResponse)
                }
            }
        }.joinToString("").trim()
        if (outputs.isBlank() || outputs.length > 16_384) throw ApiException(ApiFailure.InvalidResponse)
        outputs
    } catch (_: Exception) { throw ApiException(ApiFailure.InvalidResponse) }
}
