package com.sprich.app.input.typography

import com.sprich.app.speech.ResolvedUtteranceLanguage
import com.sprich.app.speech.api.Language

/**
 * Deterministic typography normalization — language-aware.
 *
 * Invariants:
 * - remove whitespace immediately before . , ; : ? ! ) ] } (EN/DE/ES/AUTO)
 * - do NOT modify decimal points 3.14 when already without space
 * - do NOT damage URLs/emails example.com (no space, untouched)
 * - do NOT remove newlines
 * - do NOT collapse intentional internal word spaces
 * - punctuation-only utterances remain "." etc. (composition attaches, not normalizer)
 * - FR: conservative — only fix "word ." and ", ) ] }" to avoid imposing DE/EN spacing on
 *   French ": ; ? !" which canonically has thin space before. We still fix obvious "word ." errors for FR.
 */
object TypographyNormalizer {

    private val closingPunctEN = Regex("""[ \t]+([.,;:\?!\)\]\}])""")
    private val closingPunctFR = Regex("""[ \t]+([.,\)\]\}])""")

    /**
     * Normalize punctuation spacing deterministically.
     * Runs after SpokenEditingParser and before final editor insertion.
     * Three stages: RAW_ASR -> POST_PROCESS (this) -> EDITOR_FINAL (composition)
     */
    fun normalize(text: String, language: Language = Language.AUTO): String {
        if (text.isEmpty()) return text
        // Fast path: no closing punctuation char -> return as-is to preserve intentional spaces
        if (language == Language.FR) {
            // FR: only fix . , ) ] } plus obvious, not : ; ? !
            // This preserves French thin-space convention for : ; ? ! if it existed
            return closingPunctFR.replace(text) { it.groupValues[1] }
        }
        // EN, DE, ES, AUTO: full set . , ; : ? ! ) ] }
        // Using [ \t] not \s to preserve newlines
        return closingPunctEN.replace(text) { it.groupValues[1] }
    }

    /** Generic, language-neutral normalization for Unknown LID — safe even when language unresolved. */
    fun normalizeForUnknown(text: String): String {
        if (text.isEmpty()) return text
        // Conservative: only fix obvious "hello ." -> "hello." and "word ," -> "word," etc.
        // Do NOT force French ? ! : ; spacing according to English rules if language is unknown.
        // Use FR pattern (., ) ] }) — subset of EN that is universally safe.
        return closingPunctFR.replace(text) { it.groupValues[1] }
    }

    /** Preferred overload — Known uses language-aware, Unknown uses generic. */
    fun normalize(text: String, resolved: ResolvedUtteranceLanguage): String = when (resolved) {
        is ResolvedUtteranceLanguage.Known -> normalize(text, resolved.language)
        is ResolvedUtteranceLanguage.Unknown -> normalizeForUnknown(text)
    }

    /**
     * Whether text begins with closing punctuation that should attach to previous token.
     * Used by CompositionManager to decide leading space insertion.
     */
    fun startsWithClosingPunctuation(text: String): Boolean {
        if (text.isEmpty()) return false
        val c = text.trimStart().firstOrNull() ?: return false
        return c in setOf('.', ',', ';', ':', '?', '!', ')', ']', '}')
    }

    /**
     * Whether character is closing punctuation that attaches to previous token.
     */
    fun isClosingPunctuation(c: Char): Boolean =
        c == '.' || c == ',' || c == ';' || c == ':' || c == '?' || c == '!' || c == ')' || c == ']' || c == '}'
}
