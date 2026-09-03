package com.sprich.app.speech.remote

/** Frozen with the utterance. Language hints bias recognition; they do not assert a detected language. */
data class VoiceApiOptions(
    val streaming: Boolean = false,
    val detectTurns: Boolean = true,
    val speakerLabels: Boolean = false,
    val languageHints: Set<String> = emptySet(),
) {
    val mode: VoiceApiMode get() = when {
        speakerLabels -> VoiceApiMode.DIARIZATION
        detectTurns -> VoiceApiMode.ENDPOINTING
        else -> VoiceApiMode.PUSH_TO_TALK
    }
    fun fingerprint() = listOf(streaming, mode.name, languageHints.sorted().joinToString(",")).joinToString(":")
}

enum class VoiceApiMode { PUSH_TO_TALK, ENDPOINTING, DIARIZATION }

enum class VoiceApiStage { CONNECTING, LISTENING, UPLOADING, PROCESSING, FINISHING, COMPLETE, FAILED }

/** Ephemeral UI data. It never authorizes an editor mutation and must not enter logs or saved state. */
data class RemoteTranscriptUpdate(
    val preview: String = "",
    val processedMs: Long = 0,
    val uploadedBytes: Long = 0,
    val totalUploadBytes: Long = 0,
    val stage: VoiceApiStage = VoiceApiStage.CONNECTING,
    val completedTurns: Int = 0,
    val activeTurns: Int = 0,
    val speaker: String? = null,
) {
    override fun toString() = "RemoteTranscriptUpdate(stage=$stage, chars=${preview.length}, processedMs=$processedMs, turns=$completedTurns)"
}
