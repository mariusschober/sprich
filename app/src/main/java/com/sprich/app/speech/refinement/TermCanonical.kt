package com.sprich.app.speech.refinement

import java.text.Normalizer
import java.util.Locale

/**
 * Canonical term comparison shared by relevant-term discovery and enforcement (S04).
 *
 * The original spelling is always preserved separately; only matching uses the canonical key:
 * Unicode NFKC (so composed/decomposed forms compare equal) plus explicit case handling.
 * Deliberately NO blanket accent stripping: "José" and "Jose" are distinct names and must
 * not conflate. Matching is token/span-boundary aware so short names do not match inside
 * unrelated words ("Al" must not match inside "also").
 */
object TermCanonical {

    /** Canonical matching key. Preserves accents; folds case and compatibility forms. */
    fun key(term: String): String =
        Normalizer.normalize(term, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /**
     * True when [term] occurs in [haystack] as a whole token span (boundaries on both ends),
     * compared canonically. Multiword terms match across any single whitespace run.
     */
    fun containsTerm(haystack: String, term: String): Boolean {
        if (term.isBlank()) return false
        val hay = key(haystack)
        val needle = key(term).trim().replace(Regex("\\s+"), " ")
        if (needle.isEmpty()) return false
        // Tokenize the haystack on non-word chars, keep offsets for boundary checks.
        val tokens = Regex("[\\p{L}\\p{N}_]+").findAll(hay).toList()
        val needleParts = needle.split(' ')
        if (needleParts.size == 1) {
            return tokens.any { it.value == needle }
        }
        // Multiword: consecutive tokens must equal each part in order.
        if (tokens.size < needleParts.size) return false
        for (i in 0..tokens.size - needleParts.size) {
            var ok = true
            for (j in needleParts.indices) {
                if (tokens[i + j].value != needleParts[j]) { ok = false; break }
            }
            if (ok) return true
        }
        return false
    }

    /**
     * Relevant-term discovery: which vocabulary entries actually occur in [transcript].
     * Uses the same [containsTerm] comparison as enforcement so discovery and the validator
     * can never disagree about a term's presence.
     */
    fun relevantTerms(transcript: String, entries: Collection<String>, limit: Int = 20): List<String> {
        if (entries.isEmpty()) return emptyList()
        return entries.asSequence()
            .filter { it.isNotBlank() }
            .filter { containsTerm(transcript, it) }
            .take(limit)
            .toList()
    }
}
