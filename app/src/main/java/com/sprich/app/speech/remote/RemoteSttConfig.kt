package com.sprich.app.speech.remote

import com.sprich.app.speech.LanguagePolicy

/**
 * Immutable provider configuration snapshot — captured at utterance onset.
 * One utterance must never combine old endpoint + new model + old mode because Settings changed mid-speech.
 */
data class RemoteSttConfig(
    val providerId: String, // e.g. "openai-compatible", "meta-muse-voice-transcribe", "gemini", "mock"
    val endpoint: String, // base URL
    val model: String,
    val languagePolicy: LanguagePolicy,
    val deadlineMs: Long,
    val credentialRef: String, // reference id, not plaintext secret
    // capabilities snapshot
    val supportsStreaming: Boolean = false,
    val supportsKeywordBiasing: Boolean = false,
    val preferStreaming: Boolean = false,
    val options: VoiceApiOptions = VoiceApiOptions(),
) {
    override fun toString(): String {
        // Privacy-safe: never log full endpoint/query or credential ref secret
        val safeEndpoint = try {
            val uri = java.net.URI(endpoint)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: "[host]"
            "$scheme://$host/[REDACTED_PATH]"
        } catch (_: Exception) { "[REDACTED_ENDPOINT]" }
        return "RemoteSttConfig(providerId=$providerId, endpoint=$safeEndpoint, model=$model, languagePolicy=$languagePolicy, deadlineMs=$deadlineMs, credentialRef=[REDACTED])"
    }
}

/**
 * Common request/response for provider abstraction.
 */
data class RemoteSttRequest(
    val pcm: ShortArray,
    val sampleRate: Int,
    val languagePolicy: LanguagePolicy,
    val personalVocabularyHints: List<String> = emptyList(),
    val utteranceId: Long,
    val credential: String, // resolved plaintext at call time from secure store, never persisted in config
    val preferStreaming: Boolean = false,
    val options: VoiceApiOptions = VoiceApiOptions(),
    val onProgress: ((RemoteTranscriptUpdate) -> Unit)? = null,
    val isAuthorized: (() -> Boolean)? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteSttRequest) return false
        return pcm.contentEquals(other.pcm) && sampleRate == other.sampleRate && languagePolicy == other.languagePolicy && utteranceId == other.utteranceId
    }
    override fun hashCode(): Int {
        var r = pcm.contentHashCode()
        r = 31 * r + sampleRate
        r = 31 * r + languagePolicy.hashCode()
        r = 31 * r + utteranceId.hashCode()
        return r
    }
    override fun toString(): String {
        return "RemoteSttRequest(pcmSamples=${pcm.size}, sampleRate=$sampleRate, languagePolicy=$languagePolicy, utteranceId=$utteranceId, credential=[REDACTED], vocabHints=${personalVocabularyHints.size})"
    }
}

data class RemoteSttResult(
    val text: String,
    val resolvedLanguage: com.sprich.app.speech.ResolvedUtteranceLanguage,
    val sourceId: com.sprich.app.speech.TranscriptionSourceId,
    val timingMs: Long = 0,
) {
    override fun toString() = "RemoteSttResult(chars=${text.length}, language=$resolvedLanguage, source=$sourceId, timingMs=$timingMs)"
}

data class RemoteSttCapabilities(
    val streaming: Boolean = false,
    val automaticLanguage: Boolean = true,
    val explicitLanguageHint: Boolean = true,
    val keywordBiasing: Boolean = false,
    val contextBiasing: Boolean = false,
    val endpointing: Boolean = false,
    val partialResults: Boolean = false,
)
