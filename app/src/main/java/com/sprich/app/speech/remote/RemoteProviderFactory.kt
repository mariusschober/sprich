package com.sprich.app.speech.remote

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One production factory that accepts a frozen provider config → exact RemoteSttProvider.
 * Same factory/path used by production transcription, Settings connection test, integration tests.
 * A Settings PASS must prove the path production will actually execute.
 */
object RemoteProviderFactory {

    fun create(
        config: RemoteSttConfig,
        sharedClient: OkHttpClient? = null,
        deadlinePolicy: DeadlinePolicy = DeadlinePolicy.DEFAULT,
        injectedMocks: Map<String, RemoteSttProvider> = emptyMap(),
    ): RemoteSttProvider {
        // Check injected mocks first (tests)
        injectedMocks[config.providerId]?.let { mock ->
            // Allow MockRemoteSttProvider injection for tests regardless of endpoint
            return mock
        }
        require(ProviderAvailability.isEnabled(config.providerId)) { "Provider is not enabled in this release" }
        // Create fresh provider reflecting frozen endpoint/model/deadline from config
        return when (config.providerId) {
            "meta-muse", "meta-muse-voice-transcribe" -> {
                val client = (sharedClient?.newBuilder() ?: OkHttpClient.Builder())
                    .connectTimeout(deadlinePolicy.socketConnectMs, TimeUnit.MILLISECONDS)
                    .readTimeout(deadlinePolicy.socketReadMs, TimeUnit.MILLISECONDS)
                    .writeTimeout(deadlinePolicy.socketWriteMs, TimeUnit.MILLISECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
                // Config endpoint is baseUrl (https://api.meta.ai) — provider appends path
                MetaMuseSttProvider(config.endpoint, config.model, client, config.preferStreaming)
            }
            "gemini", "gemini-3.5-transcribe", "gemini-3.5-transcribe-live" -> {
                val client = (sharedClient?.newBuilder() ?: OkHttpClient.Builder())
                    .connectTimeout(deadlinePolicy.socketConnectMs, TimeUnit.MILLISECONDS)
                    .readTimeout(deadlinePolicy.socketReadMs, TimeUnit.MILLISECONDS)
                    .writeTimeout(deadlinePolicy.socketWriteMs, TimeUnit.MILLISECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
                GeminiSttProvider(config.endpoint, config.model, client, config.preferStreaming)
            }
            else -> {
                // OpenAI-compatible custom
                val client = (sharedClient?.newBuilder() ?: OkHttpClient.Builder())
                    .connectTimeout(deadlinePolicy.socketConnectMs, TimeUnit.MILLISECONDS)
                    .readTimeout(deadlinePolicy.socketReadMs, TimeUnit.MILLISECONDS)
                    .writeTimeout(deadlinePolicy.socketWriteMs, TimeUnit.MILLISECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
                OpenAiCompatibleSttProvider(config.endpoint, config.model, client)
            }
        }
    }
}
