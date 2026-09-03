package com.sprich.app.speech.refinement

import com.sprich.app.api.ApiCatalog
import com.sprich.app.api.ApiHttp
import com.sprich.app.ai.OpenAiCompatibleRefinementProvider
import okhttp3.OkHttpClient

/** Settings checks and dictation use precisely the same adapters. */
object RefinementProviderFactory {
    fun create(config: RefinementConfig, key: String, client: OkHttpClient = ApiHttp.client): TranscriptRefinementProvider {
        require(ApiCatalog.supports(config.providerId)) { "Unsupported API" }
        val safeClient = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        return when (config.providerId) {
            "gemini" -> GeminiRefinementProvider(config.endpoint, config.model, key, safeClient)
            "meta-muse-voice-transcribe" -> OpenAiCompatibleRefinementProvider(config.endpoint.trimEnd('/') + "/v1", config.model, key, safeClient, config.providerId)
            else -> OpenAiCompatibleRefinementProvider(config.endpoint, config.model, key, safeClient, config.providerId)
        }
    }
}
