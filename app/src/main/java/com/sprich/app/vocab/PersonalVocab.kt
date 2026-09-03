package com.sprich.app.vocab

data class VocabEntry(val spoken: String, val written: String) {
    override fun toString() = "VocabEntry(spokenChars=${spoken.length}, writtenChars=${written.length})"
}

class PersonalVocabStore {
    private var entries: List<VocabEntry> = emptyList()
    private var learned: List<LearnedWord> = emptyList()
    @Volatile private var frozen = VocabSnapshot.EMPTY

    @Synchronized fun add(spoken: String, written: String) {
        entries = entries.filterNot { VocabularyText.key(it.spoken) == VocabularyText.key(spoken) } + VocabEntry(spoken, written)
        rebuild()
    }
    @Synchronized fun replace(values: List<VocabEntry>, lessons: List<LearnedWord> = emptyList()) {
        entries = values.toList()
        learned = lessons.map { it.copy(samples = it.samples.toList(), forms = it.forms.toList()) }
        rebuild()
    }
    @Synchronized fun remove(spoken: String) {
        entries = entries.filterNot { VocabularyText.key(it.spoken) == VocabularyText.key(spoken) }; rebuild()
    }
    @Synchronized fun all(): List<VocabEntry> = entries.toList()
    @Synchronized fun learnedWords(): List<LearnedWord> = learned.toList()
    @Synchronized fun document() = VocabJson(entries.map { VocabEntryJson(it.spoken, it.written) }, learned.toList())
    @Synchronized fun clear() { entries = emptyList(); learned = emptyList(); frozen = VocabSnapshot.EMPTY }
    private fun rebuild() { frozen = VocabSnapshot.compile(entries, learned) }
    fun snapshot(): VocabSnapshot = frozen
    fun apply(text: String): String = frozen.apply(text)
}

/** One complete rule set is published atomically and captured at speech onset. */
class VocabSnapshot private constructor(
    val entries: List<VocabEntry>,
    private val manual: ReplacementRules,
    private val scoped: Map<String, ReplacementRules>,
    private val learnedTerms: List<String>,
) {
    fun apply(text: String, profile: String? = null): String = (scoped[profile] ?: manual).apply(text)
    // The user's intended spelling is useful across recognizers. Only error replacements are scoped.
    fun terms(): List<String> = (entries.map { it.written } + learnedTerms).distinct()

    companion object {
        val EMPTY = compile(emptyList(), emptyList())
        internal fun compile(entries: List<VocabEntry>, learned: List<LearnedWord>): VocabSnapshot {
            val groups = learned.groupBy { it.profile.key }
            return VocabSnapshot(entries.toList(), ReplacementRules(entries), groups.mapValues { (_, words) ->
                ReplacementRules(entries + words.flatMap { word -> word.forms.map { VocabEntry(it, word.written) } })
            }, learned.map { it.written }.distinct())
        }
    }
}

private class ReplacementRules(entries: List<VocabEntry>) {
    private val unique = entries.filter { it.spoken.isNotBlank() }.distinctBy { VocabularyText.key(it.spoken) }
    private val lookup = unique.associate { VocabularyText.key(it.spoken) to it.written }
    // Unicode letters, marks, numbers and underscores belong to words. Unlike \b, this
    // also handles punctuation in names (C++, .NET) without cutting longer words.
    private val regex = if (unique.isEmpty()) null else Regex(
        "(?<![\\p{L}\\p{M}\\p{N}_])(?:" + unique.sortedByDescending { it.spoken.length }.joinToString("|") {
            VocabularyText.clean(it.spoken).split(' ').joinToString("[\\p{Zs}\\t]+", transform = ::canonicalPattern)
        } + ")(?![\\p{L}\\p{M}\\p{N}_])", RegexOption.IGNORE_CASE)

    fun apply(text: String): String {
        val pattern = regex ?: return text
        val out = StringBuilder()
        var copied = 0
        var search = 0
        while (search < text.length) {
            val match = pattern.find(text, search) ?: break
            val start = match.range.first
            val end = match.range.last + 1
            // Some regex runtimes inspect only one UTF-16 unit in lookbehind. Check
            // actual code points too, so a supplementary letter cannot split a word.
            val boundary = (start == 0 || !wordCodePoint(Character.codePointBefore(text, start))) &&
                (end == text.length || !wordCodePoint(Character.codePointAt(text, end)))
            val replacement = if (boundary) lookup[VocabularyText.key(match.value)] else null
            if (replacement != null) {
                out.append(text, copied, start).append(replacement)
                copied = end
                search = end
            } else search = start + Character.charCount(Character.codePointAt(text, start))
        }
        return if (copied == 0) text else out.append(text, copied, text.length).toString()
    }

    private fun wordCodePoint(code: Int): Boolean = code == '_'.code || Character.isLetterOrDigit(code) || when (Character.getType(code)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
        Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

    /** Match composed/decomposed letters without normalizing any untouched editor text. */
    private fun canonicalPattern(part: String): String = buildString {
        part.codePoints().forEach { code ->
            val composed = String(Character.toChars(code))
            val decomposed = java.text.Normalizer.normalize(composed, java.text.Normalizer.Form.NFD)
            append(if (composed == decomposed) Regex.escape(composed)
                else "(?:${Regex.escape(composed)}|${Regex.escape(decomposed)})")
        }
    }
}
