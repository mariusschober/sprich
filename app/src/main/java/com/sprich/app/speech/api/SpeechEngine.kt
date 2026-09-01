package com.sprich.app.speech.api

import kotlinx.coroutines.flow.Flow

enum class Language(val code: String) { EN("en"), DE("de"), ES("es"), FR("fr"), AUTO("auto") }

/**
 * Typed transcription task. Must be TRANSCRIBE by default; TRANSLATE is a separate
 * explicit feature and must never be inferred from UI locale.
 */
enum class TranscriptionTask { TRANSCRIBE, TRANSLATE }

/**
 * Typed source-speech language: Auto (engine decides) or an explicit BCP-47 tag.
 * Persisted as BCP-47 string, resolved once per session, observable in diagnostics.
 * UI locale is never used as source language except for an explicit first-run suggestion.
 */
sealed class SpeechLanguage {
    object Auto : SpeechLanguage() {
        override fun toString() = "auto"
    }
    data class Fixed(val tag: String) : SpeechLanguage() {
        init { require(tag.matches(Regex("[a-z]{2}(-[A-Z]{2})?"))) { "Invalid BCP-47 tag: $tag" } }
        override fun toString() = tag
    }
    open fun toBcp47(): String = when (this) { is Auto -> "auto"; is Fixed -> tag }
    fun toLegacyLanguage(): Language = when (this) {
        is Auto -> Language.AUTO
        is Fixed -> when (tag.lowercase()) {
            "en", "en-us", "en-gb" -> Language.EN
            "de", "de-de" -> Language.DE
            "es", "es-es" -> Language.ES
            "fr", "fr-fr" -> Language.FR
            else -> Language.AUTO
        }
    }
    companion object {
        fun fromBcp47(tag: String): SpeechLanguage =
            if (tag.lowercase() == "auto") Auto else Fixed(tag.lowercase())
        fun fromLegacy(lang: Language): SpeechLanguage =
            if (lang == Language.AUTO) Auto else Fixed(lang.code)
    }
}

data class SpeechSessionConfig(
    val language: Language = Language.AUTO,
    val speechLanguage: SpeechLanguage = SpeechLanguage.fromLegacy(Language.AUTO),
    val task: TranscriptionTask = TranscriptionTask.TRANSCRIBE,
    val enablePunctuation: Boolean = true,
    val enableCommands: Boolean = true,
    val personalVocab: List<String> = emptyList(),
    val beamSize: Int = 5,
) {
    init {
        require(task == TranscriptionTask.TRANSCRIBE || task == TranscriptionTask.TRANSLATE) { "Invalid task" }
    }
    companion object {
        fun withTypedLanguage(lang: SpeechLanguage, task: TranscriptionTask = TranscriptionTask.TRANSCRIBE): SpeechSessionConfig =
            SpeechSessionConfig(
                language = lang.toLegacyLanguage(),
                speechLanguage = lang,
                task = task,
            )
    }
    /** Resolved once per session; diagnostics should log this, not Locale.getDefault(). */
    fun resolvedLanguageTag(): String = speechLanguage.toBcp47().ifEmpty { language.code }
    fun resolvedTask(): TranscriptionTask = task
}

data class Token(
    val text: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val confidence: Float? = null,
)

data class TranscriptUpdate(
    val stable: String,
    val unstable: String,
    val isFinal: Boolean = false,
    val tokens: List<Token>? = null,
    val lang: Language? = null,
)

data class FinalTranscript(
    val text: String,
    val tokens: List<Token>? = null,
    val language: Language? = null,
)

data class SpeechEngineCapabilities(
    val trueStreaming: Boolean = false,
    val partialResults: Boolean = true,
    val wordTimestamps: Boolean = false,
    val contextBiasing: Boolean = false,
    val languageDetection: Boolean = false,
    val punctuation: Boolean = true,
    val capitalization: Boolean = true,
    val inverseTextNormalization: Boolean = false,
    val confidence: Boolean = false,
)

interface SpeechEngine {
    suspend fun load(): Result<Unit>
    suspend fun unload()
    fun beginSession(config: SpeechSessionConfig)
    fun pushAudio(samples: ShortArray, timestampNanos: Long)
    fun partialTranscript(): Flow<TranscriptUpdate>
    suspend fun endUtterance(): FinalTranscript
    fun cancelSession()
    fun reset()
    fun supportedLanguages(): Set<Language>
    fun capabilities(): SpeechEngineCapabilities
    fun isLoaded(): Boolean
    val engineId: String
    val displayName: String
}

enum class EngineType { FAST, ACCURATE, STREAMING }

data class EngineInfo(
    val type: EngineType,
    val id: String,
    val displayName: String,
    val technicalName: String,
    val sizeBytes: Long,
    val bundled: Boolean,
)
