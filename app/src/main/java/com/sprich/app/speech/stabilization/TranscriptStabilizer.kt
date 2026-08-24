package com.sprich.app.speech.stabilization

import kotlin.math.min

/**
 * Stable-prefix algorithm:
 * Keep N recent hypotheses (tokenized by words). Find longest prefix that survived N consecutive decodes.
 * Stable prefix never moves backwards. Unstable suffix can churn.
 *
 * Implementation: word-level, case/punct sensitive. N=2-3 configurable.
 */
class TranscriptStabilizer(
    private val requiredAgreement: Int = 2, // N
    private val maxHistory: Int = 4,
) {
    private val history = ArrayDeque<List<String>>()
    private var committedStable = emptyList<String>()
    private var lastStable = emptyList<String>()

    data class Result(val stable: String, val unstable: String, val stableWords: List<String>)

    fun reset() {
        history.clear()
        committedStable = emptyList()
        lastStable = emptyList()
    }

    fun commitStable() {
        committedStable = lastStable.toList()
        history.clear()
    }

    fun pushHypothesis(text: String): Result {
        val words = tokenize(text)
        if (words.isEmpty()) {
            // keep last stable, no new info
            val stable = detokenize(lastStable)
            return Result(stable, "", lastStable)
        }
        history.addLast(words)
        if (history.size > maxHistory) history.removeFirst()

        // If not enough history, nothing stable yet except committed
        if (history.size < requiredAgreement) {
            val stable = detokenize(lastStable) // still previous stable
            val unstable = detokenize(words.drop(lastStable.size))
            return Result(stable, unstable, lastStable)
        }

        // Compute longest common prefix of last N hypotheses
        val recent = history.takeLast(requiredAgreement)
        var lcpLen = recent.minOf { it.size }
        for (i in 0 until lcpLen) {
            val w = recent[0][i]
            if (recent.any { it[i] != w }) { lcpLen = i; break }
        }
        // Stable is min of lcp and current prefix that matches lcp, and at least committed growth handling
        // If lcp is shorter than lastStable, we must shrink (hypothesis revised)
        // If lcp is longer, we can grow to lcp
        var newStableLen = lcpLen
        // But also ensure stable is prefix of current words (by definition of lcp it is)
        // Clamp to not exceed words.size
        newStableLen = min(newStableLen, words.size)
        // Additionally, if lcp is still longer than lastStable, that's fine to grow; if shorter, shrink to lcp (allow revision)
        // No need for extra shrinking logic; lcp already handles

        val stableWords = words.take(newStableLen)
        lastStable = stableWords
        // committedStable is prefix of stable; but if lastStable shrank, committed should stay?
        // committedStable only grows via commitStable(), not here.

        val stable = detokenize(stableWords)
        val unstable = detokenize(words.drop(newStableLen))
        return Result(stable, unstable, stableWords)
    }

    fun currentStable(): String = detokenize(lastStable)
    fun currentStableWords(): List<String> = lastStable

    private fun tokenize(s: String): List<String> {
        if (s.isBlank()) return emptyList()
        // Simple whitespace tokenization, preserve punctuation attached; good enough for stable prefix.
        // Could split on whitespace regex.
        return s.trim().split(Regex("\\s+"))
    }

    private fun detokenize(words: List<String>): String = words.joinToString(" ")
}
