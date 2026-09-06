package com.sprich.app.speech.refinement

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Deterministic guards for optional text cleanup (S02/S03/S04).
 *
 * This validator does NOT prove semantic equivalence and no second LLM classifier is
 * presented as a guarantee: anything outside the supported edit catalogue is rejected and
 * the caller falls back to the original deterministic transcript. Rejections are counted
 * in [ValidationStats] so conservative-rejection cost is measurable.
 *
 * Protected in both Faithful and Polished modes:
 * - critical-token sequences (numbers with signs/decimals/ranges/dates/times/currency/
 *   percentages/units/identifiers) in occurrence and order — additions to nonnumeric
 *   sentences and removals are rejected, not only mutations;
 * - numbers together with their units (mg→g is a meaning change);
 * - email/URL/identifier occurrences; currency symbols;
 * - negation presence AND clause scope (moved negation is rejected);
 * - modality markers (may vs must) and uncertainty markers (removing "maybe"/"vielleicht"
 *   changes meaning);
 * - named/learned protected terms via [TermCanonical] (same comparison as discovery).
 *
 * Faithful additionally allows only its catalogue: punctuation/casing/spacing (invisible at
 * word-token level), removal of fixed-list fillers, removal of immediate tandem repetition,
 * and explicitly stated self-corrections ("Tuesday, no, Wednesday"). Polished additionally
 * permits other rephrasing inside the style choice, still under the protections above.
 */
object RefinementValidator {
    sealed class Result {
        data object Accept : Result()
        data class Reject(val reason: String) : Result()
    }

    /** Bounded counters: total validations and rejections per reason (S03 measurability). */
    object ValidationStats {
        private val total = AtomicLong(0)
        private val rejects = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
        fun record(result: Result) {
            total.incrementAndGet()
            if (result is Result.Reject) {
                rejects.computeIfAbsent(result.reason) { AtomicLong(0) }.incrementAndGet()
            }
        }
        fun snapshot(): Map<String, Long> {
            val out = mutableMapOf("total" to total.get())
            for ((k, v) in rejects) out["reject:$k"] = v.get()
            return out
        }
    }

    private val url = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val email = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val correction = Regex("(\\d+(?:[.,:/-]\\d+)*)\\s*[,;—-]?\\s*(?:no|sorry|actually|nein|sondern|pardon|perdón|digo|plutôt)\\s*[,;—-]?\\s*(\\d+(?:[.,:/-]\\d+)*)", RegexOption.IGNORE_CASE)
    // Repeated units are part of the same explicit correction: "50 Euro, nein, 15 Euro".
    private val unitCorrection = Regex("([+−-]?\\d+(?:[.,:/-]\\d+)*)\\s+([\\p{L}µ%°]{1,20})\\s*[,;—-]?\\s*(?:no|sorry|actually|nein|sondern|pardon|perdón|digo|plutôt)\\s*[,;—-]?\\s*([+−-]?\\d+(?:[.,:/-]\\d+)*)\\s+\\2(?!\\p{L})", RegexOption.IGNORE_CASE)
    private val assistantPrefix = Regex(
        "^(here is (?:the|your) (?:corrected|cleaned)|sure[,!:]|corrected text:|here’s (?:the|your) (?:corrected|cleaned)" +
            "|hier ist (?:der|dein) korrigierte?r? text|aquí está|voici (?:le texte|la version))",
        RegexOption.IGNORE_CASE,
    )
    private val negation = Regex(
        "\\b(?:no|not|never|cannot|nein|nicht|niemals|kein\\p{L}*|non|pas|jamais|aucun\\p{L}*|nunca|ningún|ninguna|nadie|nada|ni|ne)\\b|n’t\\b|n't\\b",
        RegexOption.IGNORE_CASE,
    )
    private val correctionCue = Regex("(\\p{L}{2,})\\s*,\\s*(?:no|nein|non)\\s*,\\s*(\\p{L}{2,})", RegexOption.IGNORE_CASE)
    private val currency = Regex("[$€£¥₹₩₽]")

    /** Modality-bearing words: changing one changes obligation/permission (may vs must). */
    private val modality = Regex(
        "\\b(?:may|must|might|shall|should|ought|kann|können|kannst|könnt|muss|müssen|musst|müsst|darf|dürfen|soll|sollen|sollte|sollten|puede|pueden|debe|deben|peut|peuvent|doit|doivent)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Uncertainty/hedge markers whose removal changes meaning. */
    private val uncertainty = Regex(
        "\\b(?:maybe|perhaps|possibly|probably|uncertain|unsure|vielleicht|möglicherweise|wahrscheinlich|eventuell|quizás|tal vez|creo|peut-être|probablement|je crois)\\b|\\bi think\\b|\\bich glaube\\b|\\bno estoy segur[oa]\\b|\\bje ne suis pas sûr\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Fixed filler lexicon (EN/DE/ES/FR) — only these may be dropped as fillers in Faithful. */
    private val fillers = setOf(
        "um", "uh", "uhm", "er", "erm", "ah", "eh",
        "ähm", "äh", "hm", "hmm",
        "eh", "este", "pues", "o sea",
        "euh", "ben", "bah", "hein",
    )

    fun validate(original: String, candidate: String, mode: RefinementMode, protectedTerms: List<String> = emptyList()): Result {
        val result = validateInner(original, candidate, mode, protectedTerms)
        ValidationStats.record(result)
        return result
    }

    private fun validateInner(original: String, candidate: String, mode: RefinementMode, protectedTerms: List<String>): Result {
        if (candidate.isBlank()) return Result.Reject("empty")
        if (candidate.length > 16_384) return Result.Reject("oversized")
        if (candidate == original) return Result.Accept
        if (candidate.contains("```") && !original.contains("```")) return Result.Reject("added_markup")
        if (assistantPrefix.containsMatchIn(candidate) && !assistantPrefix.containsMatchIn(original)) return Result.Reject("added_commentary")
        // Explicitly stated numeric self-corrections resolve first ("50 Euro, nein, 15 Euro"
        // means 15 Euro): the superseded value and its cue word carry no further meaning, so
        // the resolved text — not the raw transcript — is the baseline for length bounds.
        val numericallyResolved =
            correction.replace(unitCorrection.replace(original) { "${it.groupValues[3]} ${it.groupValues[2]}" }) {
                it.groupValues[2]
            }
        val lowerBound = if (mode.isPolished) .25 else .5
        if (candidate.length < numericallyResolved.length * lowerBound) return Result.Reject("excessive_shortening")
        if (candidate.length > numericallyResolved.length * 1.6 + 24) return Result.Reject("excessive_expansion")
        fun literals(regex: Regex, text: String) = regex.findAll(text).map { it.value.trimEnd('.', ',', ';', '!', '?', ')', ']') }.toSet()
        if (literals(url, original) != literals(url, candidate)) return Result.Reject("url_changed")
        if (literals(email, original) != literals(email, candidate)) return Result.Reject("email_changed")

        // S02: ordered critical-token comparison — always, not only when original has numbers.
        // Additions to a nonnumeric sentence, removals, reorderings and unit changes all reject.
        val oldSeq = CriticalTokens.sequence(original)
        val newSeq = CriticalTokens.sequence(candidate)
        if (oldSeq != newSeq) {
            // Only the explicitly stated correction above may rewrite the sequence, in both modes.
            if (CriticalTokens.sequence(numericallyResolved) != newSeq) return Result.Reject("number_changed")
        }
        if (currency.containsMatchIn(original) && literals(currency, original) != literals(currency, candidate)) {
            return Result.Reject("currency_changed")
        }

        // Negation: presence plus clause scope — moved negation across clauses rejects.
        val semanticOriginal = correctionCue.replace(numericallyResolved, "$1 $2")
        if (negation.containsMatchIn(semanticOriginal) != negation.containsMatchIn(candidate)) {
            return Result.Reject("negation_changed")
        }
        if (negationScopeMoved(semanticOriginal, candidate)) return Result.Reject("negation_moved")

        // Modality (may vs must, incl. DE/ES/FR equivalents) and uncertainty markers.
        fun markers(regex: Regex, text: String) =
            regex.findAll(text).map { it.value.lowercase(Locale.ROOT) }.toSet()
        if (markers(modality, semanticOriginal) != markers(modality, candidate)) {
            return Result.Reject("modality_changed")
        }
        if (markers(uncertainty, semanticOriginal).isNotEmpty() &&
            markers(uncertainty, candidate).isEmpty()
        ) {
            return Result.Reject("uncertainty_removed")
        }

        // S04: protected terms with the same canonical comparison used for discovery.
        for (term in protectedTerms) {
            if (term.isNotBlank() && TermCanonical.containsTerm(original, term) &&
                !TermCanonical.containsTerm(candidate, term)
            ) {
                return Result.Reject("protected_term_changed")
            }
        }

        // S03: Faithful edit catalogue via word-token alignment.
        if (mode.isFaithful && !faithfulEditsOnly(original, candidate)) {
            return Result.Reject("unsupported_edit")
        }
        // Polished permits rephrasing inside the style choice, but an irrelevant rewrite that
        // keeps almost none of the original content (e.g. an assistant response to dictation)
        // is not cleanup — it is a different text.
        if (mode.isPolished && !polishedTopicOverlap(original, candidate)) {
            return Result.Reject("unsupported_rephrase")
        }
        return Result.Accept
    }

    /**
     * Polished topic guard: the candidate must retain at least half of the original content
     * tokens and introduce at most three-fifths new ones (multiset comparison, fillers and
     * short function words excluded). Explicit numeric self-corrections resolve first so the
     * superseded value does not count against overlap.
     */
    private fun polishedTopicOverlap(original: String, candidate: String): Boolean {
        fun content(text: String): List<String> =
            wordTokens(text).filter { it.length >= 3 && !fillers.contains(it) }
        val a = content(
            correction.replace(unitCorrection.replace(original) { "${it.groupValues[3]} ${it.groupValues[2]}" }) {
                it.groupValues[2]
            },
        )
        val b = content(candidate)
        if (a.isEmpty()) return true
        if (b.isEmpty()) return false
        val remaining = a.toMutableList()
        var retained = 0
        for (t in b) if (remaining.remove(t)) retained++
        val introduced = b.size - retained
        return retained * 2 >= a.size && introduced * 5 <= b.size * 3
    }

    /** True when negation moved between clauses while clause count stayed comparable. */
    private fun negationScopeMoved(original: String, candidate: String): Boolean {
        // Commas split clauses too: "I do not want tea, I want coffee" moves negation
        // across a comma, which presence alone cannot see.
        fun clauseFlags(text: String): List<Boolean> =
            text.split(Regex("[,.!?;:]+")).map { it.isNotBlank() && negation.containsMatchIn(it) }
        val a = clauseFlags(original)
        val b = clauseFlags(candidate)
        // Only comparable when cleanup preserved clause segmentation; otherwise presence
        // check above already guards, and scope cannot be established -> presence decides.
        if (a.size != b.size || a.size < 2) return false
        return a != b
    }

    private val wordToken = Regex("[\\p{L}\\p{N}_]+")

    private fun wordTokens(text: String): List<String> {
        val norm = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return wordToken.findAll(norm).map { it.value }.toList()
    }

    /**
     * Faithful catalogue check: every word-token edit must be filler removal, immediate
     * tandem-repetition removal, or an explicitly stated self-correction. Case/punctuation/
     * spacing differences are invisible at token level and always allowed.
     */
    private fun faithfulEditsOnly(original: String, candidate: String): Boolean {
        val a = wordTokens(original)
        val b = wordTokens(candidate)
        if (a == b) return true
        val allowedDeletes = allowedDeletePositions(original, a)
        // LCS over token lists; every non-LCS original token must be an allowed delete,
        // every non-LCS candidate token is an insertion -> reject.
        val lcs = lcsTable(a, b)
        var i = a.size
        var j = b.size
        val inLcsA = BooleanArray(a.size)
        val inLcsB = BooleanArray(b.size)
        while (i > 0 && j > 0) {
            if (a[i - 1] == b[j - 1]) {
                inLcsA[i - 1] = true; inLcsB[j - 1] = true; i--; j--
            } else if (lcs[i - 1][j] >= lcs[i][j - 1]) {
                i--
            } else {
                j--
            }
        }
        for (k in b.indices) if (!inLcsB[k]) return false // insertions of content words
        for (k in a.indices) if (!inLcsA[k] && !allowedDeletes.contains(k)) return false
        return true
    }

    /**
     * Token positions in the original that may be deleted: fixed-list fillers, the first copy
     * of an immediate tandem repeat ("ich glaube ich glaube"), and the superseded side plus
     * cue of an explicit self-correction ("Tuesday, no, Wednesday" keeps Wednesday).
     */
    private fun allowedDeletePositions(original: String, tokens: List<String>): Set<Int> {
        val allowed = mutableSetOf<Int>()
        for (k in tokens.indices) if (fillers.contains(tokens[k])) allowed.add(k)
        // Immediate tandem repeats: span S followed by identical span S -> first copy deletable.
        var p = 0
        while (p < tokens.size) {
            var len = 1
            var matched = false
            while (len <= (tokens.size - p) / 2 && len <= 6) {
                val first = tokens.subList(p, p + len)
                val second = tokens.subList(p + len, p + 2 * len)
                if (first == second) {
                    for (k in p until p + len) allowed.add(k)
                    p += len
                    matched = true
                    break
                }
                len++
            }
            if (!matched) p++
        }
        // Explicit numeric self-corrections ("50 Euro, nein, 15 Euro" keeps "15 Euro",
        // "2 no 3" keeps "3"): every token of the match except the retained replacement tail
        // is deletable. Same normalization as the number-sequence resolution above.
        val norm = Normalizer.normalize(original, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        fun allowMatchExceptTail(match: MatchResult, replacement: String) {
            val replCount = wordToken.findAll(replacement.lowercase(Locale.ROOT)).count()
            val spanTokens = wordToken.findAll(norm)
                .withIndex()
                .filter { it.value.range.first >= match.range.first && it.value.range.last + 1 <= match.range.last + 1 }
                .map { it.index }
                .toList()
            val deletable = (spanTokens.size - replCount).coerceAtLeast(0)
            for (k in spanTokens.take(deletable)) allowed.add(k)
        }
        for (m in unitCorrection.findAll(norm)) {
            allowMatchExceptTail(m, "${m.groupValues[3]} ${m.groupValues[2]}")
        }
        for (m in correction.findAll(norm)) {
            allowMatchExceptTail(m, m.groupValues[2])
        }
        // Explicit word self-correction cues: mark superseded word + cue words as deletable.
        // Matched on the same normalized text the tokens came from so offsets align.
        for (m in correctionCue.findAll(norm)) {
            val cueStart = m.range.first
            val cueEnd = m.range.last + 1
            val retained = m.groupValues[2]
            var idx = 0
            for (t in wordToken.findAll(norm)) {
                if (t.range.first >= cueStart && t.range.last + 1 <= cueEnd && t.value != retained) {
                    allowed.add(idx)
                }
                idx++
            }
        }
        return allowed
    }

    private fun lcsTable(a: List<String>, b: List<String>): Array<IntArray> {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 1..a.size) for (j in 1..b.size) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
        return dp
    }
}
