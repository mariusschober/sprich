package com.sprich.app.speech

import com.sprich.app.speech.api.Language

/**
 * Distinguishes "Automatic requested" from "language resolution failed".
 *
 * Previously Language.AUTO was overloaded to mean both — causing German/Spanish/French
 * transcripts with failed LID to receive English command maps / English ITN.
 *
 * Now:
 * - Successful LID → Known(EN|DE|ES|FR)
 * - Unsupported / Failed / Unavailable → Unknown
 *
 * Unknown must use language-neutral post-processing only (generic typography, no spoken commands, no email ITN).
 */
sealed interface ResolvedUtteranceLanguage {
    data class Known(val language: Language) : ResolvedUtteranceLanguage
    data object Unknown : ResolvedUtteranceLanguage

    fun toLanguageOrNull(): Language? = when (this) {
        is Known -> language
        is Unknown -> null
    }
}
