package com.sprich.app.speech.refinement

/**
 * Transcript refinement modes (S03).
 *
 * Two user-facing writing behaviors, both protecting critical facts:
 * - FAITHFUL (default automatic cleanup): punctuation, casing, spacing, plus carefully
 *   validated filler/repetition/explicit self-correction removal. Any other content edit
 *   falls back to the original deterministic transcript.
 * - POLISHED (explicitly selected broader style): clearer written phrasing is permitted
 *   within the user's style choice, still under critical-fact protections (numbers+units,
 *   named/learned terms, email/URL/identifiers, negation/modality/uncertainty scope).
 * - OFF: no LLM call.
 *
 * CORRECT and CLEAN_DICTATION are legacy aliases retained for stored preferences:
 * CORRECT behaves as FAITHFUL, CLEAN_DICTATION as POLISHED.
 */
enum class RefinementMode {
    OFF,
    CORRECT,
    CLEAN_DICTATION,
    FAITHFUL,
    POLISHED;

    /** True for the conservative default policy (FAITHFUL and its legacy alias CORRECT). */
    val isFaithful: Boolean get() = this == FAITHFUL || this == CORRECT

    /** True for the broader explicitly-selected policy (POLISHED and alias CLEAN_DICTATION). */
    val isPolished: Boolean get() = this == POLISHED || this == CLEAN_DICTATION

    companion object {
        fun fromRaw(raw: String): RefinementMode = when (raw.lowercase().trim()) {
            "correct" -> CORRECT
            "clean", "clean_dictation", "clean-dictation" -> CLEAN_DICTATION
            "faithful" -> FAITHFUL
            "polished" -> POLISHED
            else -> OFF
        }
        fun toRaw(mode: RefinementMode): String = when (mode) {
            OFF -> "off"
            CORRECT -> "correct"
            CLEAN_DICTATION -> "clean_dictation"
            FAITHFUL -> "faithful"
            POLISHED -> "polished"
        }
    }
}
