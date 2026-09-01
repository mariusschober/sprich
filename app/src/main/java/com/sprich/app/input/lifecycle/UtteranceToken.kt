package com.sprich.app.input.lifecycle

import android.view.inputmethod.InputConnection

/**
 * Immutable token that uniquely identifies one utterance within one field/session.
 * Every VAD speech onset creates a monotonically increasing utteranceId.
 * Only the holder of the latest token may decode and commit.
 */
data class UtteranceToken(
    val sessionId: Long,
    val generation: Long,
    val utteranceId: Long,
    val fieldId: String?,
    val fieldGeneration: Long,
    val capturedIc: InputConnection?,
) {
    fun isCurrent(
        activeSessionId: Long,
        activeGeneration: Long,
        activeUtteranceId: Long,
        activeFieldId: String?,
        activeFieldGeneration: Long,
    ): Boolean {
        if (sessionId != activeSessionId) return false
        if (generation != activeGeneration) return false
        if (utteranceId != activeUtteranceId) return false
        if (fieldId != activeFieldId) return false
        if (fieldGeneration != activeFieldGeneration) return false
        return true
    }
}
