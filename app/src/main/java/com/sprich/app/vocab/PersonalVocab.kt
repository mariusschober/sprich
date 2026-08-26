package com.sprich.app.vocab

data class VocabEntry(val spoken: String, val written: String)

class PersonalVocabStore {
    private val entries = mutableListOf<VocabEntry>()
    @Volatile private var combined: Regex? = null
    @Volatile private var lookup: Map<String, String> = emptyMap()

    private fun rebuild() {
        // Longest-first single-pass alternation: replacements are never rescanned,
        // so a short entry can't corrupt the output of a longer one.
        val sorted = entries.sortedByDescending { it.spoken.length }
        lookup = sorted.associate { it.spoken.lowercase() to it.written }
        combined = if (sorted.isEmpty()) null
        else Regex("\\b(?:" + sorted.joinToString("|") { Regex.escape(it.spoken) } + ")\\b", RegexOption.IGNORE_CASE)
    }

    fun add(spoken: String, written: String) {
        entries.removeAll { it.spoken.equals(spoken, ignoreCase = true) }
        entries.add(VocabEntry(spoken, written))
        rebuild()
    }
    fun remove(spoken: String) { entries.removeAll{ it.spoken.equals(spoken, true)}; rebuild() }
    fun all(): List<VocabEntry> = entries.toList()
    fun clear(){ entries.clear(); combined = null; lookup = emptyMap() }

    fun apply(text: String): String {
        val re = combined ?: return text
        if (text.isEmpty()) return text
        return re.replace(text) { m -> lookup[m.value.lowercase()] ?: m.value }
    }
}
