package com.sprich.app.ui.vocab

import com.sprich.app.vocab.LearnedWord
import com.sprich.app.vocab.VocabEntry
import com.sprich.app.vocab.VocabularyText
import java.text.Normalizer

/** Case/diacritic-friendly containment over the written form and the forms the user says. */
private fun fold(text: String): String =
    Normalizer.normalize(VocabularyText.key(text), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

internal fun learnedMatches(word: LearnedWord, query: String): Boolean {
    val needle = fold(query)
    if (needle.isEmpty()) return true
    return fold(word.written).contains(needle) ||
        word.samples.any { fold(it).contains(needle) } ||
        word.forms.any { fold(it).contains(needle) }
}

internal fun entryMatches(entry: VocabEntry, query: String): Boolean =
    entryMatches(entry.written, entry.spoken, query)

internal fun entryMatches(written: String, spoken: String, query: String): Boolean {
    val needle = fold(query)
    if (needle.isEmpty()) return true
    return fold(written).contains(needle) || fold(spoken).contains(needle)
}
