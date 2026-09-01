package com.sprich.app.input.commands

import com.sprich.app.speech.api.Language

/**
 * Deterministic spoken editing and narrowly-scoped text normalization. No LLM.
 * False positives are worse than missed commands: normal dictated words are never treated as
 * self-correction instructions.
 */
object SpokenEditingParser {

    data class EditResult(val text: String, val isCommand: Boolean)

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

    fun parse(text: String, lang: Language, enableCommands: Boolean): EditResult {
        if (!enableCommands) return EditResult(applyITN(text), false)
        val lower = text.lowercase().trim()
        val map = when(lang){
            Language.DE -> deCommands
            Language.ES -> esCommands
            else -> enCommands
        }

        // Destructive commands only run when the complete utterance is the exact command.
        map[lower]?.let { command ->
            return EditResult(command, true)
        }

        // Inline spoken punctuation remains supported, but correction words such as "no" and
        // "actually" are deliberately not interpreted. Doing so destroyed ordinary sentences.
        var out = text
        var foundInline = false
        for ((phrase, replacement) in map) {
            if (replacement.startsWith("__DELETE")) continue
            if (replacement.length <= 2) {
                val regex = Regex("\\b${Regex.escape(phrase)}\\b", RegexOption.IGNORE_CASE)
                if (regex.containsMatchIn(out)) {
                    out = regex.replace(out, replacement)
                    foundInline = true
                }
            }
        }

        return EditResult(applyITN(out), foundInline)
    }

    private fun applyITN(text: String): String {
        var normalized = text
        // Only transform a highly-specific spoken email shape. Generic number-word replacement
        // was removed because phrases such as "one reason" must remain verbatim.
        normalized = Regex(
            "\\b([a-zA-Z0-9_\\-]+)\\s+at\\s+([a-zA-Z0-9_\\-]+)\\s+dot\\s+([a-z]{2,})\\b",
            RegexOption.IGNORE_CASE,
        ).replace(normalized) { match ->
            "${match.groupValues[1]}@${match.groupValues[2]}.${match.groupValues[3]}"
        }
        normalized = Regex(
            "\\b([a-zA-Z0-9_\\-]+)\\s+dot\\s+com\\b",
            RegexOption.IGNORE_CASE,
        ).replace(normalized) { match -> "${match.groupValues[1]}.com" }
        return normalized
    }

    fun isDeleteCommand(text: String): Boolean =
        text == "__DELETE_LAST__" || text == "__DELETE_SENTENCE__"
}
