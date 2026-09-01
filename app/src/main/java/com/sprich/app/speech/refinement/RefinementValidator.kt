package com.sprich.app.speech.refinement

import kotlin.math.abs

/**
 * Deterministic local validator — never replace base transcript merely because LLM output is nonblank.
 */
object RefinementValidator {
    sealed class Result {
        data object Accept : Result()
        data class Reject(val reason: String) : Result()
    }

    private val urlRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val emailRegex = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val numberRegex = Regex("""(?<!\w)(?:\d{1,3}(?:,\d{3})+|\d+)(?:[.:]\d+)?(?:[°%€$£¥]?)?(?!\w)""")
    // markdown fences / assistant commentary markers
    private val markdownFence = Regex("""```""")
    private val assistantPrefix = Regex("""^(Here is|Corrected|Sure|The corrected).*?:\s*""", RegexOption.IGNORE_CASE)

    fun validate(original: String, candidate: String, mode: RefinementMode, protectedTerms: List<String> = emptyList()): Result {
        if (candidate.isBlank()) return Result.Reject("empty")
        if (candidate.length > 8192) return Result.Reject("oversized")
        if (markdownFence.containsMatchIn(candidate)) return Result.Reject("markdown fence")
        if (candidate.contains("```")) return Result.Reject("markdown")
        if (candidate.trim().startsWith("Here is", ignoreCase = true)) return Result.Reject("assistant commentary")
        if (assistantPrefix.containsMatchIn(candidate.trim())) return Result.Reject("assistant prefix")
        // multiple alternatives
        if (candidate.contains("\n\n") && candidate.split("\n\n").size > 3) return Result.Reject("multiple alternatives")
        // Must not be multiple lines when original single line and not CLEAN? Allow single newline for paragraph, but not many
        if (candidate.lines().size > original.lines().size + 2) return Result.Reject("sentence count change")

        // URL / email preservation (strip trailing punctuation for fair compare)
        fun normalizeUrl(u: String) = u.trim().trimEnd('.', ',', '!', '?', ';', ':')
        val origUrls = urlRegex.findAll(original).map { normalizeUrl(it.value) }.toSet()
        val candUrls = urlRegex.findAll(candidate).map { normalizeUrl(it.value) }.toSet()
        if (origUrls.isNotEmpty() && candUrls != origUrls) return Result.Reject("url mutation orig=$origUrls cand=$candUrls")

        val origEmails = emailRegex.findAll(original).map { it.value.lowercase().trimEnd('.', ',', '!', '?') }.toSet()
        val candEmails = emailRegex.findAll(candidate).map { it.value.lowercase().trimEnd('.', ',', '!', '?') }.toSet()
        if (origEmails.isNotEmpty() && candEmails != origEmails) return Result.Reject("email mutation")

        // Protected terms
        for (term in protectedTerms) {
            if (term.isBlank()) continue
            val inOrig = original.contains(term, ignoreCase = true)
            if (inOrig && !candidate.contains(term, ignoreCase = true)) {
                // Allow case change? No, must preserve case-insensitive presence
                return Result.Reject("protected term missing: $term")
            }
        }

        // Numbers: no new numbers, no missing numbers (exact string set equality for numbers)
        val origNums = extractNumbers(original)
        val candNums = extractNumbers(candidate)
        if (origNums.isNotEmpty()) {
            if (origNums != candNums) {
                // For CORRECT, any number mutation is reject; for CLEAN also strict
                return Result.Reject("number mutation orig=$origNums cand=$candNums")
            }
        } else {
            // If candidate introduces numbers where none existed — suspicious
            if (candNums.isNotEmpty() && candNums.any { it !in origNums }) {
                // Allow if candidate adds "1" due to formatting? Be strict for CORRECT
                if (mode == RefinementMode.CORRECT) return Result.Reject("new numbers")
            }
        }

        // Technical IDs: patterns like T807D, GPT-5, AB-123 etc. — preserve alphanum-
        val idRegex = Regex("""\b[A-Z]{1,3}\d+[A-Z]?\b|\b[A-Z]+-\d+\b""")
        val origIds = idRegex.findAll(original).map { it.value }.toSet()
        val candIds = idRegex.findAll(candidate).map { it.value }.toSet()
        if (origIds.isNotEmpty() && origIds != candIds) return Result.Reject("technical id mutation")

        // Length ratio heuristics
        val origLen = original.length.coerceAtLeast(1)
        val candLen = candidate.length
        val ratio = candLen.toFloat() / origLen
        val allowedRatio = when (mode) {
            RefinementMode.CORRECT -> 0.7f to 1.35f
            RefinementMode.CLEAN_DICTATION -> 0.5f to 1.4f
            else -> 0.5f to 1.5f
        }
        if (ratio < allowedRatio.first || ratio > allowedRatio.second) return Result.Reject("length ratio $ratio out of $allowedRatio")

        // Lexical edit distance via word-level Jaccard + char Levenshtein ratio
        val origWords = original.split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.lowercase().trim('.', ',', '!', '?', ';', ':') }
        val candWords = candidate.split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.lowercase().trim('.', ',', '!', '?', ';', ':') }
        if (origWords.isNotEmpty() && candWords.isNotEmpty()) {
            val origSet = origWords.toSet()
            val candSet = candWords.toSet()
            val introduced = (candSet - origSet).filter { it.length > 2 } // new content words
            val removed = (origSet - candSet).filter { it.length > 2 }
            // For CORRECT, introduced long content words > 35% of orig is drift
            val thresholdIntroduced = when (mode) {
                RefinementMode.CORRECT -> 0.35
                RefinementMode.CLEAN_DICTATION -> 0.5
                else -> 0.6
            }
            if (origSet.isNotEmpty()) {
                val introRatio = introduced.size.toFloat() / origSet.size
                if (introRatio > thresholdIntroduced && introduced.size > 3) return Result.Reject("semantic drift introduced=$introduced ratio=$introRatio")
                val removedRatio = removed.size.toFloat() / origSet.size
                val removedThreshold = when (mode) {
                    RefinementMode.CORRECT -> 0.35
                    RefinementMode.CLEAN_DICTATION -> 0.55
                    else -> 0.6
                }
                if (removedRatio > removedThreshold && removed.size > 3) return Result.Reject("semantic drift removed=$removed ratio=$removedRatio")
            }
        }

        // Language switch heuristic: if original is clearly DE (contains äöü/ß) and candidate loses them and gains EN words, flag
        // Simplified: detect source contains umlaut and candidate is ascii-only + english common words vs german
        // We reject translation: if Levenshtein distance high and new words are translation-like.
        // For now, only reject if candidate contains "the" "and" etc while original DE vocab — conservative not to false positive on short.
        // Use heuristic: if original length <20, skip translation check.
        if (original.length > 20) {
            val origIsGerman = original.contains(Regex("[äöüßÄÖÜ]")) || original.lowercase().contains(" und ") || original.lowercase().contains(" ich ")
            if (origIsGerman && candidate.lowercase().contains(" the ") && !original.lowercase().contains(" the ")) {
                // Possible translation — if many english function words appear newly, reject
                val candLower = candidate.lowercase()
                val germanMarkers = listOf(" und ", " der ", " die ", " das ")
                val englishMarkers = listOf(" the ", " and ", " is ")
                val origGermanCount = germanMarkers.count { original.lowercase().contains(it) }
                val candEnglishCount = englishMarkers.count { candLower.contains(it) }
                if (origGermanCount > 0 && candEnglishCount >= 2) return Result.Reject("possible translation")
            }
        }

        // Prompt injection signals not handled here — separate prompt contract ensures transcript treated as data.

        return Result.Accept
    }

    private fun extractNumbers(text: String): Set<String> {
        return numberRegex.findAll(text).map { it.value.trim() }.filter { it.length in 1..20 }.toSet()
    }
}
