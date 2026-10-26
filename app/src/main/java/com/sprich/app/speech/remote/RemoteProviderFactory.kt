package com.sprich.app.speech.remote

import com.sprich.app.api.ApiHttp
import okhttp3.OkHttpClient

/** Same production path for the connection check and every completed dictation. */
object RemoteProviderFactory {
    fun create(config: RemoteSttConfig, sharedClient: OkHttpClient? = null,
        deadlinePolicy: DeadlinePolicy = DeadlinePolicy.DEFAULT,
        injectedMocks: Map<String, RemoteSttProvider> = emptyMap(),
    ): RemoteSttProvider {
        injectedMocks[config.providerId]?.let { return it }
        require(ProviderAvailability.isEnabled(config.providerId)) { "Unsupported API" }
        val client = (sharedClient ?: ApiHttp.client).newBuilder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        return when (config.providerId) {
            // P04: streaming admission uses the product connection deadline so it fits the capture buffer.
            "meta-muse", "meta-muse-voice-transcribe" -> MetaMuseSttProvider(config.endpoint, config.model, client, deadlinePolicy.streamingConnectionMs)
            "gemini" -> GeminiSttProvider(config.endpoint, config.model, client)
            else -> OpenAiCompatibleSttProvider(config.endpoint, config.model, client, officialOpenAi = config.providerId == "openai")
        }
    }
}
