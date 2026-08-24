package com.sprich.app.vocab

data class VocabEntry(val spoken: String, val written: String)

class PersonalVocabStore {
    private val entries = mutableListOf<VocabEntry>()
    @Volatile private var compiled: List<Pair<Regex, String>> = emptyList()

    private fun rebuild() {
        // Sort by spoken length desc to avoid substring order bug (e.g., "new york" before "york")
        compiled = entries.sortedByDescending { it.spoken.length }.map { e ->
            Regex("\\b${Regex.escape(e.spoken)}\\b", RegexOption.IGNORE_CASE) to e.written
        }
    }

    fun add(spoken: String, written: String) {
        entries.removeAll { it.spoken.equals(spoken, ignoreCase = true) }
        entries.add(VocabEntry(spoken, written))
        rebuild()
    }
    fun remove(spoken: String) { entries.removeAll{ it.spoken.equals(spoken, true)}; rebuild() }
    fun all(): List<VocabEntry> = entries.toList()
    fun clear(){ entries.clear(); compiled = emptyList() }

    fun apply(text: String): String {
        if (compiled.isEmpty() || text.isEmpty()) return text
        var out = text
        for ((regex, written) in compiled) out = regex.replace(out, written)
        return out
    }
}
