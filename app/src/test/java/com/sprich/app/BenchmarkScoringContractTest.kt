package com.sprich.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.min

/**
 * E01 / plan section 10.1 + 10.4 — host-runnable scoring-contract pins.
 *
 * Self-contained (no production imports) so it compiles with the offline
 * kotlinc-direct harness (stdlib + JUnit only). It does NOT measure device
 * latency, WER on real audio, or any Wispr comparison — those need a phone,
 * a 1,000-utterance corpus and provider credentials and remain BLOCKED.
 *
 * Documented normalization (the contract future corpus scoring must use):
 * lowercase, Unicode NFKC is NOT applied (host kotlin.text lacks it; note as
 * gap), trim, collapse inner whitespace, strip leading/trailing punctuation
 * per token, keep intra-token apostrophes/hyphens. Empty-after-normalize on
 * both sides scores 0.0; actual text against empty reference scores 1.0.
 */
class BenchmarkScoringContractTest {

    // --- scoring helpers under contract (mirrors GoldenBenchmarkHarness semantics) ---

    fun normalizeForWer(s: String): String =
        s.lowercase()
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { tok -> tok.trim { c -> !c.isLetterOrDigit() && c != '\'' && c != '-' } }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .let { collapsed -> collapsed.replace(Regex("\\s+"), " ").trim() }

    data class Outcome(
        val id: String,
        val correct: Boolean,
        val latencyMs: Long?, // null = failure / no result
    )

    /** WER over normalized word sequences; failures are NOT dropped by this fn. */
    fun wer(expectedRaw: String, actualRaw: String): Double {
        val exp = normalizeForWer(expectedRaw).split(" ").filter { it.isNotEmpty() }
        val act = normalizeForWer(actualRaw).split(" ").filter { it.isNotEmpty() }
        if (exp.isEmpty() && act.isEmpty()) return 0.0
        if (exp.isEmpty()) return 1.0
        val n = exp.size
        val m = act.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            dp[i][j] = if (exp[i - 1] == act[j - 1]) dp[i - 1][j - 1]
            else min(min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + 1)
        }
        return dp[n][m].toDouble() / n
    }

    /**
     * Deadline success (plan section 10.1): fraction of ALL outcomes that are
     * correct AND have latency <= deadline. Failures stay in the denominator —
     * dropping them must not improve the metric.
     */
    fun deadlineSuccess(outcomes: List<Outcome>, deadlineMs: Long): Double {
        if (outcomes.isEmpty()) return 0.0
        val ok = outcomes.count { it.correct && it.latencyMs != null && it.latencyMs <= deadlineMs }
        return ok.toDouble() / outcomes.size
    }

    fun failureRate(outcomes: List<Outcome>): Double {
        if (outcomes.isEmpty()) return 0.0
        return outcomes.count { it.latencyMs == null || !it.correct }.toDouble() / outcomes.size
    }

    /** p50/p95 over successful latencies only; caller must report failureRate alongside. */
    fun percentiles(successLatenciesMs: List<Long>): Pair<Long, Long> {
        require(successLatenciesMs.isNotEmpty())
        val s = successLatenciesMs.sorted()
        val p50 = s[(s.size * 0.50).toInt().coerceAtMost(s.size - 1)]
        val p95 = s[(s.size * 0.95).toInt().coerceAtMost(s.size - 1)]
        return p50 to p95
    }

    /**
     * Plan section 10.4 "2x faster" claim check on paired waits: requires
     * sprich p50 AND p95 each at most half of the paired competitor wait,
     * plus a pre-registered quality tolerance (sprich errors <= comp errors + tol).
     * Pure arithmetic on reported numbers — not a measurement.
     */
    fun meetsTwoFoldClaim(
        sprichP50: Long, sprichP95: Long, competitorP50: Long, competitorP95: Long,
        sprichErrors: Int, competitorErrors: Int, qualityTolerance: Int,
    ): Boolean =
        sprichP50 * 2 <= competitorP50 &&
            sprichP95 * 2 <= competitorP95 &&
            sprichErrors <= competitorErrors + qualityTolerance

    // --- contract tests ---

    @Test fun normalizationIsDocumentedAndStable() {
        assertEquals("hello world", normalizeForWer("  Hello,  WORLD! "))
        assertEquals("don't stop", normalizeForWer("Don't STOP..."))
        assertEquals("", normalizeForWer("... !!!"))
        assertEquals("3,14", normalizeForWer("(3,14)"))
    }

    @Test fun werKnownValues() {
        assertEquals(0.0, wer("hello world", "Hello, WORLD!"), 1e-9)
        assertEquals(0.0, wer("", ""), 1e-9)
        assertEquals(1.0, wer("", "hello"), 1e-9)
        // 1 substitution in 4 words
        assertEquals(0.25, wer("the cat sat down", "the dog sat down"), 1e-9)
        // critical meaning change still counts lexically (semantic review is separate):
        // 1 deletion over 3 reference words
        assertEquals(1.0 / 3, wer("do not stop", "do stop"), 1e-9)
    }

    @Test fun failuresStayInDenominator() {
        val all = listOf(
            Outcome("a", true, 400),
            Outcome("b", true, 900),
            Outcome("c", false, 300), // wrong text, fast — still a failure
            Outcome("d", false, null), // no result — still a failure
        )
        assertEquals(0.5, failureRate(all), 1e-9)
        // deadline 1000ms: only the 2 correct count -> 0.5, not 1.0
        assertEquals(0.5, deadlineSuccess(all, 1000), 1e-9)
        // dropping failures would give 1.0 — assert the contract forbids that reading
        val dropped = all.filter { it.latencyMs != null && it.correct }
        assertEquals(1.0, deadlineSuccess(dropped, 1000), 1e-9)
        assertTrue(deadlineSuccess(all, 1000) < deadlineSuccess(dropped, 1000))
    }

    @Test fun deadlineSuccessCannotImproveBySlowness() {
        val all = listOf(
            Outcome("a", true, 400),
            Outcome("b", true, 5000), // correct but past deadline
            Outcome("c", false, null),
        )
        assertEquals(1.0 / 3, deadlineSuccess(all, 1000), 1e-9)
    }

    @Test fun percentilesNeedFailureRateAlongside() {
        val (p50, p95) = percentiles(listOf(100, 200, 300, 400, 500))
        assertTrue(p50 <= p95)
        assertEquals(300L, p50)
        assertEquals(500L, p95)
    }

    @Test fun twoFoldClaimRequiresBothP50AndP95PlusQuality() {
        // genuine win
        assertTrue(meetsTwoFoldClaim(200, 400, 500, 900, 5, 5, 0))
        // p95 misses -> no claim
        assertFalse(meetsTwoFoldClaim(200, 500, 500, 900, 5, 5, 0))
        // p50 misses -> no claim
        assertFalse(meetsTwoFoldClaim(300, 400, 500, 900, 5, 5, 0))
        // quality tolerance exceeded -> no claim even when fast
        assertFalse(meetsTwoFoldClaim(200, 400, 500, 900, 8, 5, 2))
        // within tolerance -> claim holds
        assertTrue(meetsTwoFoldClaim(200, 400, 500, 900, 7, 5, 2))
    }

    @Test fun emptyCorpusScoresZeroNotNaN() {
        assertEquals(0.0, deadlineSuccess(emptyList(), 1000), 1e-9)
        assertEquals(0.0, failureRate(emptyList()), 1e-9)
    }
}
