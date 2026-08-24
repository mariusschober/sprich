package com.sprich.app.speech.api

import kotlinx.coroutines.flow.Flow

enum class Language(val code: String) { EN("en"), DE("de"), ES("es"), FR("fr"), AUTO("auto") }

data class SpeechSessionConfig(
    val language: Language = Language.AUTO,
    val enablePunctuation: Boolean = true,
    val enableCommands: Boolean = true,
    val personalVocab: List<String> = emptyList(),
    val beamSize: Int = 5,
)

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
