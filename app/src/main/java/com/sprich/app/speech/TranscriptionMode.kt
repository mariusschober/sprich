package com.sprich.app.speech

/**
 * Typed product semantics replacing raw STT mode strings ("local","fallback","remote").
 * - ON_DEVICE: local ASR only, zero STT API calls
 * - API_PRIMARY: remote provider first, optional local fallback on failure
 * - LOCAL_API_FALLBACK: local first, remote only on objective failure (exception/unavailable/blank)
 */
enum class TranscriptionMode {
    ON_DEVICE,
    API_PRIMARY,
    LOCAL_API_FALLBACK;

    companion object {
        fun fromRaw(raw: String): TranscriptionMode = when (raw.lowercase().trim()) {
            "local" -> ON_DEVICE
            "remote" -> API_PRIMARY
            "fallback" -> LOCAL_API_FALLBACK
            else -> ON_DEVICE
        }
        fun toRaw(mode: TranscriptionMode): String = when (mode) {
            ON_DEVICE -> "local"
            API_PRIMARY -> "remote"
            LOCAL_API_FALLBACK -> "fallback"
        }
    }
}
