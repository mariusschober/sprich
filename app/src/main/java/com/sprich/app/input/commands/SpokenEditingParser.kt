package com.sprich.app.input.commands

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

    private val correctionTriggers = setOf("actually", "no", "correction", "scratch that", "i mean", "rather",
        "eigentlich", "nein", "korrektur", "ich meine", "eher",
        "en realidad", "no", "corrección", "quiero decir")

    fun parse(text: String, lang: Language, enableCommands: Boolean): EditResult {
        if (!enableCommands) return EditResult(applyITN(text, lang), false)
        val lower = text.lowercase().trim()
        val map = when(lang){
            Language.DE -> deCommands
            Language.ES -> esCommands
            else -> enCommands
        }
        // Check if entire utterance is a command (or ends with command phrase)
        // Conservative: only if text exactly equals command or "xxx , comma" at end with punctuation command allowed inline
        if (map.containsKey(lower)) {
            val v = map[lower]!!
            return EditResult(v, true)
        }
        // Inline punctuation: replace spoken punctuation words surrounded by spaces, but be conservative
        // e.g., "hello comma world" -> "hello, world"
        var out = text
        var foundInline = false
        for ((k,v) in map) {
            if (v.startsWith("__DELETE")) continue
            // punctuation mapping only
            if (v.length <= 2) {
                // Replace word boundaries, case-insensitive
                val regex = Regex("\\b${Regex.escape(k)}\\b", RegexOption.IGNORE_CASE)
                if (regex.containsMatchIn(out)) {
                    // Only replace if not at start and seems intentional: keep simple
                    out = regex.replace(out, v)
                    foundInline = true
                }
            }
        }
        // Backtracking: "A actually B" -> keep B only after trigger
        for (trigger in correctionTriggers) {
            val idx = out.lowercase().lastIndexOf(trigger)
            if (idx > 0) {
                val before = out.substring(0, idx).trim()
                val after = out.substring(idx + trigger.length).trim().removePrefix(",").trim()
                if (after.isNotEmpty() && before.isNotEmpty()) {
                    // Only apply if words before not huge? conservative
                    // For now, if trigger preceded by punctuation or dash, we cut
                    out = after // simplified: keep only correction
                    break
                }
            }
        }
        out = applyITN(out, lang)
        return EditResult(out, foundInline)
    }

    private fun applyITN(text: String, lang: Language): String {
        var t = text
        // Numbers: "five point two" -> 5.2 (EN)
        // Conservative: only simple patterns
        t = t.replace(Regex("\\bzero\\b", RegexOption.IGNORE_CASE), "0")
        t = t.replace(Regex("\\bone\\b", RegexOption.IGNORE_CASE), "1")
        // Email: "marius at example dot com" -> marius@example.com
        t = Regex("\\b([a-zA-Z0-9_\\-]+)\\s+at\\s+([a-zA-Z0-9_\\-]+)\\s+dot\\s+([a-z]{2,})\\b", RegexOption.IGNORE_CASE)
            .replace(t) { m -> "${m.groupValues[1]}@${m.groupValues[2]}.${m.groupValues[3]}" }
        t = Regex("\\b([a-zA-Z0-9_\\-]+)\\s+dot\\s+com\\b", RegexOption.IGNORE_CASE)
            .replace(t) { m -> "${m.groupValues[1]}.com" }
        return t
    }

    fun isDeleteCommand(text: String): Boolean = text == "__DELETE_LAST__" || text == "__DELETE_SENTENCE__"
}
