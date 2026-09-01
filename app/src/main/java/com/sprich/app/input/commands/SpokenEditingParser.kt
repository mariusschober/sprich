package com.sprich.app.input.commands

import com.sprich.app.input.typography.TypographyNormalizer
import com.sprich.app.speech.ResolvedUtteranceLanguage
import com.sprich.app.speech.api.Language

/**
 * Deterministic spoken editing & ITN. No LLM.
 * Conservative: false positive worse than miss.
 */
object SpokenEditingParser {

    data class EditResult(val text: String, val isCommand: Boolean)

    // Commands per language — keep exact phrase matching at end of utterance
    private val enCommands = mapOf(
        "new line" to "\n",
        "new paragraph" to "\n\n",
        "comma" to ",",
        "period" to ".",
        "full stop" to ".",
        "question mark" to "?",
        "exclamation mark" to "!",
        "open quote" to "\"",
        "close quote" to "\"",
        "colon" to ":",
        "semicolon" to ";",
        "dash" to " - ",
        "delete that" to "__DELETE_LAST__",
        "scratch that" to "__DELETE_LAST__",
        "delete last sentence" to "__DELETE_SENTENCE__",
    )
    private val deCommands = mapOf(
        "neue zeile" to "\n",
        "neuer absatz" to "\n\n",
        "komma" to ",",
        "punkt" to ".",
        "fragezeichen" to "?",
        "ausrufezeichen" to "!",
        "lösche das" to "__DELETE_LAST__",
        "lösch das" to "__DELETE_LAST__",
        "letzten satz löschen" to "__DELETE_SENTENCE__",
    )
    private val esCommands = mapOf(
        "nueva línea" to "\n",
        "nuevo párrafo" to "\n\n",
        "coma" to ",",
        "punto" to ".",
        "signo de interrogación" to "?",
        "signo de exclamación" to "!",
        "borra eso" to "__DELETE_LAST__",
        "borrar eso" to "__DELETE_LAST__",
    )

    // Removed correctionTriggers — implicit backtracking was unsafe (substring "no" in "not" etc.)
    // Only explicit whole-utterance delete commands remain. See mission: false positives worse than misses.

    /**
     * Language-aware parse — when language is known (EN/DE/ES/FR), normal behavior.
     * For backwards compat, Language.AUTO here is treated as EN (legacy) — prefer parseWithResolved for new code.
     */
    fun parse(text: String, lang: Language, enableCommands: Boolean): EditResult {
        // Preserve legacy contract but delegate to resolved path for safety
        val resolved: ResolvedUtteranceLanguage = when (lang) {
            Language.AUTO -> ResolvedUtteranceLanguage.Unknown
            else -> ResolvedUtteranceLanguage.Known(lang)
        }
        // Legacy callers that passed AUTO previously got English commands — now they get Unknown (safe, generic only).
        // If that legacy behavior is required, caller should pass Known(Language.EN) explicitly.
        // We treat Unknown as no language-specific commands/ITN to avoid wrong-language damage.
        return parse(text, resolved, enableCommands)
    }

    /** Preferred: distinguish Known vs Unknown — Unknown uses generic-only processing. */
    fun parse(text: String, resolved: ResolvedUtteranceLanguage, enableCommands: Boolean): EditResult {
        if (resolved is ResolvedUtteranceLanguage.Unknown) {
            // Safe generic only — no language-specific spoken commands, no English email ITN.
            // Only generic typography normalization (removes space before . , etc., but not : ; ? !)
            val normalized = TypographyNormalizer.normalizeForUnknown(text)
            return EditResult(normalized, false)
        }
        val lang = (resolved as ResolvedUtteranceLanguage.Known).language
        if (!enableCommands) {
            val raw = applyITN(text, lang)
            val normalized = TypographyNormalizer.normalize(raw, lang)
            return EditResult(normalized, false)
        }
        val lower = text.lowercase().trim()
        val map = when(lang){
            Language.DE -> deCommands
            Language.ES -> esCommands
            else -> enCommands
        }
        // Check if entire utterance is a command — conservative: whole utterance equals command phrase only
        if (map.containsKey(lower)) {
            val v = map[lower]!!
            return EditResult(v, true)
        }
        // Inline punctuation: replace spoken punctuation words surrounded by spaces, word-boundary, language-specific
        // e.g., "hello comma world" -> "hello, world" (EN) ; "hallo komma welt" -> "hallo, welt" (DE)
        var out = text
        var foundInline = false
        for ((k,v) in map) {
            if (v.startsWith("__DELETE")) continue
            // punctuation mapping only (single char or colon/semicolon etc.)
            if (v.length <= 2 || v == " - ") {
                // Word-boundary regex ensures "no" does not match "not", "innovative" etc.
                val regex = Regex("\\b${Regex.escape(k)}\\b", RegexOption.IGNORE_CASE)
                if (regex.containsMatchIn(out)) {
                    out = regex.replace(out, v)
                    foundInline = true
                }
            }
        }
        // Correction backtracking REMOVED entirely per reliability requirement — no substring triggers.
        out = applyITN(out, lang)
        // Deterministic typography: fix "Hallo Punkt" -> "Hallo ." -> "Hallo." and raw "gut ." -> "gut."
        // Run after ITN so we don't damage URLs/decimals; preserves newlines and intentional word spaces.
        out = TypographyNormalizer.normalize(out, lang)
        return EditResult(out, foundInline)
    }

    private fun applyITN(text: String, lang: Language): String {
        // Language-aware ITN — English patterns only for EN, never for DE/ES/FR
        if (lang != Language.EN && lang != Language.AUTO) {
            return text
        }
        var t = text
        // Numbers: disabled for reliability — simple "zero"/"one" -> "0"/"1" causes false positives
        // e.g., "no one knows" -> "no 1 knows" is worse than missed ITN. Keep only email which has clear structure.
        // If numeric ITN needed later, require explicit numeric context, not unconditional word replace.
        // Email: "marius at example dot com" -> marius@example.com (EN only, with word boundaries)
        t = Regex("\\b([a-zA-Z0-9_\\-]+)\\s+at\\s+([a-zA-Z0-9_\\-]+)\\s+dot\\s+([a-z]{2,})\\b", RegexOption.IGNORE_CASE)
            .replace(t) { m -> "${m.groupValues[1]}@${m.groupValues[2]}.${m.groupValues[3]}" }
        t = Regex("\\b([a-zA-Z0-9_\\-]+)\\s+dot\\s+com\\b", RegexOption.IGNORE_CASE)
            .replace(t) { m -> "${m.groupValues[1]}.com" }
        return t
    }

    fun isDeleteCommand(text: String): Boolean = text == "__DELETE_LAST__" || text == "__DELETE_SENTENCE__"
}
