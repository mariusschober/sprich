package com.sprich.app.speech.refinement

/**
 * Transcript refinement modes.
 * OFF: no LLM call
 * CORRECT: grammar/punctuation/capitalization/obvious contextual substitution fix, preserve meaning
 * CLEAN_DICTATION: CORRECT plus cautious removal of fillers/false starts/accidental immediate repetition
 */
enum class RefinementMode {
    OFF,
    CORRECT,
    CLEAN_DICTATION;

    companion object {
        fun fromRaw(raw: String): RefinementMode = when (raw.lowercase().trim()) {
            "correct" -> CORRECT
            "clean", "clean_dictation", "clean-dictation" -> CLEAN_DICTATION
            else -> OFF
        }
        fun toRaw(mode: RefinementMode): String = when (mode) {
            OFF -> "off"
            CORRECT -> "correct"
            CLEAN_DICTATION -> "clean_dictation"
        }
    }
}
