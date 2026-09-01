package com.sprich.app.speech

import com.sprich.app.speech.api.SpeechSessionConfig

/**
 * Common result type above local/remote providers. No secrets, no fake confidence.
 */
data class TranscriptionResult(
    val text: String,
    val resolvedLanguage: ResolvedUtteranceLanguage,
    val effectiveConfig: SpeechSessionConfig,
    val source: TranscriptionSourceId,
    val timingMs: Long = 0,
)

enum class TranscriptionSourceId(val id: String) {
    LOCAL_FAST("local-fast"),
    LOCAL_CANARY("local-canary"),
    API_OPENAI_COMPATIBLE("api-openai-compatible"),
    API_META_MUSE("api-meta-muse"),
    API_MOCK("api-mock"),
}
