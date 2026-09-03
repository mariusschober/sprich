package com.sprich.app.vocab

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

object VocabularyText {
    private val spaces = Regex("[\\p{Z}\\s]+")
    fun clean(text: String): String = spaces.replace(Normalizer.normalize(text, Normalizer.Form.NFC).trim(), " ")
    fun key(text: String): String = clean(text).lowercase(Locale.ROOT)

    /** Keep abbreviations and meaningful symbols; omit punctuation added to an isolated term. */
    fun heardForm(raw: String): String {
        val text = clean(raw)
        return if (Regex("(?:\\p{L}\\.){2,}").matches(text)) text
        else text.trimEnd('.', '!', '?', '。', '！', '？').trimEnd()
    }

    fun validManual(text: String, max: Int): Boolean = text.isNotBlank() && text.length <= max &&
        text.none { it.isISOControl() } && text.indices.none { i ->
            (text[i].isHighSurrogate() && (i + 1 == text.length || !text[i + 1].isLowSurrogate())) ||
                (text[i].isLowSurrogate() && (i == 0 || !text[i - 1].isHighSurrogate()))
        }
    fun validTerm(text: String, max: Int = 128): Boolean = validManual(text, max) && text.any { it.isLetterOrDigit() }
    fun validSample(text: String): Boolean = text.length <= 256 && validTerm(heardForm(text)) &&
        text.none { it.isISOControl() && !it.isWhitespace() }
}

data class HeardForm(val text: String, val count: Int, val spellings: Set<String> = setOf(text)) {
    val key: String get() = VocabularyText.key(text)
    fun needsCorrection(written: String): Boolean = spellings.any { it != VocabularyText.clean(written) }
    override fun toString() = "HeardForm(chars=${text.length}, count=$count)"
}

/** Only final recognizer observations may become aliases. No synthetic or fuzzy variants. */
object WordLesson {
    const val MIN_SAMPLES = 3
    const val MAX_SAMPLES = 5
    const val MAX_WORDS = 200

    fun forms(samples: List<String>): List<HeardForm> = samples.filter(VocabularyText::validSample)
        .map(VocabularyText::heardForm).groupBy(VocabularyText::key)
        .values.map { HeardForm(it.first(), it.size, it.toSet()) }

    fun suggested(samples: List<String>, written: String): Set<String> = forms(samples)
        .filter { it.count >= 2 && it.needsCorrection(written) }.map { it.key }.toSet()

    fun create(profile: RecognitionProfile, written: String, samples: List<String>, selected: Set<String>): LearnedWord {
        val target = VocabularyText.clean(written)
        require(VocabularyText.validTerm(target))
        require(samples.size in MIN_SAMPLES..MAX_SAMPLES && samples.all(VocabularyText::validSample))
        require(Regex("[a-f0-9]{64}").matches(profile.key))
        val forms = forms(samples)
        require(selected.all { key -> forms.any { it.key == key && it.needsCorrection(target) } })
        return LearnedWord(UUID.randomUUID().toString(), target, profile, samples.toList(),
            forms.filter { it.key in selected }.map { it.text })
    }
}

@Serializable
data class LearnedWord(
    val id: String,
    val written: String,
    val profile: RecognitionProfile,
    val samples: List<String>,
    val forms: List<String>,
) {
    fun isValid(): Boolean = runCatching {
        require(Regex("[a-zA-Z0-9-]{1,64}").matches(id))
        val rebuilt = WordLesson.create(profile, written, samples, forms.map(VocabularyText::key).toSet())
        require(rebuilt.forms == forms && rebuilt.written == written)
    }.isSuccess
    override fun toString() = "LearnedWord(samples=${samples.size}, forms=${forms.size})"
}

class VocabularyConflictException : IllegalArgumentException("A recognition form already has another spelling")
class WordAlreadyLearnedException : IllegalArgumentException("This word is already learned for this recognition mode")

@Serializable data class VocabJson(val entries: List<VocabEntryJson> = emptyList(), val learned: List<LearnedWord> = emptyList()) {
    fun conflicts(form: String, written: String, profile: String? = null): Boolean {
        val key = VocabularyText.key(form)
        val target = VocabularyText.clean(written)
        return entries.any { VocabularyText.key(it.spoken) == key && VocabularyText.clean(it.written) != target } ||
            learned.any { (profile == null || it.profile.key == profile) && VocabularyText.clean(it.written) != target &&
                it.forms.any { alias -> VocabularyText.key(alias) == key } }
    }

    fun addWord(word: LearnedWord): VocabJson {
        require(word.isValid())
        require(entries.size + learned.size < WordLesson.MAX_WORDS)
        if (learned.any { it.profile.key == word.profile.key && VocabularyText.key(it.written) == VocabularyText.key(word.written) })
            throw WordAlreadyLearnedException()
        if (word.forms.any { conflicts(it, word.written, word.profile.key) }) throw VocabularyConflictException()
        return copy(learned = learned + word)
    }

    fun addManual(spoken: String, written: String): VocabJson {
        val form = VocabularyText.clean(spoken)
        val target = VocabularyText.clean(written)
        require(VocabularyText.validManual(form, 128) && VocabularyText.validManual(target, 256))
        val withoutOld = copy(entries = entries.filterNot { VocabularyText.key(it.spoken) == VocabularyText.key(form) })
        if (withoutOld.conflicts(form, target)) throw VocabularyConflictException()
        require(withoutOld.entries.size + learned.size < WordLesson.MAX_WORDS)
        return withoutOld.copy(entries = withoutOld.entries + VocabEntryJson(form, target))
    }
    override fun toString() = "VocabJson(manual=${entries.size}, learned=${learned.size})"
}

@Serializable data class VocabEntryJson(val spoken: String, val written: String) {
    override fun toString() = "VocabEntryJson(spokenChars=${spoken.length}, writtenChars=${written.length})"
}
