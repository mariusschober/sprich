package com.sprich.app.speech.remote

/** Add an ID here only with live evidence from the release production path. */
object ProviderAvailability {
    val productionEnabled: Set<String> = emptySet()
    private val experimental = setOf("openai-compatible", "custom", "meta-muse", "meta-muse-voice-transcribe", "gemini", "gemini-3.5-transcribe", "gemini-3.5-transcribe-live")
    fun isEnabled(id: String) = id in productionEnabled || (com.sprich.app.BuildConfig.DEBUG && id in experimental)
}
