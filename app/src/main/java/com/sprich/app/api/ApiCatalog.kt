package com.sprich.app.api

import com.sprich.app.core.security.EndpointValidator
import com.sprich.app.speech.LanguagePolicy
import com.sprich.app.speech.remote.RemoteSttConfig
import com.sprich.app.speech.remote.VoiceApiOptions
import com.sprich.app.speech.refinement.*
import com.sprich.app.storage.RuntimeConfigSnapshot
import java.security.MessageDigest

/** Independent permissions and connection evidence for audio and text. */
enum class ApiUse { VOICE, WRITING }

data class ApiPreset(val id: String, val name: String, val endpoint: String, val voiceModel: String, val writingModel: String,
    val keyUrl: String, val privacyUrl: String, val experimental: Boolean = false)

object ApiCatalog {
    const val REVISION = "2026-09-03.1"
    val presets = listOf(
        ApiPreset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-transcribe", "gpt-5.6-luna",
            "https://platform.openai.com/api-keys", "https://developers.openai.com/api/docs/guides/your-data"),
        ApiPreset("gemini", "Gemini", "https://generativelanguage.googleapis.com", "gemini-3.5-transcribe", "gemini-3.5-flash-lite",
            "https://aistudio.google.com/apikey", "https://ai.google.dev/gemini-api/terms"),
        // Public cookbook contract; live acceptance is still required for this account and use.
        ApiPreset("meta-muse-voice-transcribe", "Meta", "https://api.meta.ai", "muse-voice-transcribe-1.0", "muse-spark-1.3",
            "https://dev.meta.ai/", "https://dev.meta.ai/docs/", experimental = true),
    )
    val custom = ApiPreset("custom", "Custom API", "", "", "", "", "", experimental = true)
    fun preset(id: String) = presets.firstOrNull { it.id == id } ?: custom
    fun supports(id: String) = id in presets.map { it.id } || id in setOf("custom", "openai-compatible")
}

data class ApiChoice(val use: ApiUse, val providerId: String, val endpoint: String, val model: String, val credentialRef: String,
    val verification: String = "", val voiceOptions: VoiceApiOptions = VoiceApiOptions()) {
    val configurationValid: Boolean get() {
        if (!ApiCatalog.supports(providerId) || !EndpointValidator.isValidHttpsUrl(endpoint) || model.isBlank() || model.length > 160 || model.any { it.isISOControl() }) return false
        if (voiceOptions.languageHints.any { it !in setOf("en", "de", "es", "fr") }) return false
        if (voiceOptions.streaming && providerId != "meta-muse-voice-transcribe") return false
        val url = java.net.URI(endpoint.trim())
        if (url.rawQuery != null || url.rawFragment != null) return false
        val preset = ApiCatalog.presets.firstOrNull { it.id == providerId } ?: return true
        return endpoint.trimEnd('/') == preset.endpoint && model == if (use == ApiUse.VOICE) preset.voiceModel else preset.writingModel
    }
    val valid get() = configurationValid && credentialRef.startsWith("bound_")
    fun fingerprint(): String = MessageDigest.getInstance("SHA-256").digest(
        (listOf(ApiCatalog.REVISION, DictationPrompt.VERSION, use.name, providerId, endpoint.trimEnd('/'), model, credentialRef) +
            if (use == ApiUse.VOICE && providerId == "meta-muse-voice-transcribe") listOf(voiceOptions.fingerprint()) else emptyList()).joinToString("\n").toByteArray()
    ).joinToString("") { "%02x".format(it) }
    val verified get() = valid && verification == fingerprint()
    fun remote(language: LanguagePolicy, deadlineMs: Long = 3500) = RemoteSttConfig(providerId, endpoint, model, language, deadlineMs, credentialRef,
        supportsKeywordBiasing = providerId in setOf("openai", "gemini", "meta-muse-voice-transcribe"),
        supportsStreaming = providerId == "meta-muse-voice-transcribe", preferStreaming = voiceOptions.streaming,
        options = voiceOptions.copy(languageHints = voiceOptions.languageHints.toSet()))
    fun refinement(contextEnabled: Boolean = false, deadlineMs: Long = 4000) = RefinementConfig(providerId, endpoint, model,
        RefinementMode.CLEAN_DICTATION, deadlineMs, credentialRef, contextEnabled)
    override fun toString() = "ApiChoice(use=$use, provider=$providerId, verified=$verified)"
}

fun RuntimeConfigSnapshot.apiChoice(use: ApiUse): ApiChoice = when (use) {
    ApiUse.VOICE -> ApiChoice(use, sttProviderId, sttBaseUrl, sttModel, sttCredentialRef, sttVerification, voiceApiOptions)
    ApiUse.WRITING -> ApiChoice(use, refinementProviderId, refinementBaseUrl, refinementModel, refinementCredentialRef, refinementVerification)
}
