package com.sprich.app.core.audio

/**
 * P05 (plan §5.3): bounded continuous speech — caps, safe-pause split, bounded
 * overlap with verified text reconciliation and sample-span identity.
 *
 * JVM-pure (no Android imports) so it compiles with the cached kotlinc harness.
 * Feeding live capture through this (endpoint-driven splits, editor commit)
 * needs Gradle + device and is recorded BLOCKED in the child report.
 *
 * - Hard caps are safety ceilings, never the ordinary segmentation policy:
 *   prefer [SplitPlan.atSafePause]; the overlap exists only for splits.
 * - Overlap is bounded by [MAX_OVERLAP_SAMPLES]; [reconcile] dedupes overlapped
 *   words ONLY on exact token match (verified), otherwise keeps both sides —
 *   overlapping ASR strings are never concatenated or deduped blindly.
 * - [AudioSpan] sample identity travels with the split so overlap can never
 *   become duplicated editor text downstream.
 */
object ContinuousSpeechPolicy {
    const val SAMPLE_RATE_HZ = 16000
    const val SEGMENT_SOFT_CAP_MS = 29_000
    const val SEGMENT_HARD_CAP_MS = 30_000
    const val SESSION_SOFT_CAP_MS = 119_000
    const val SESSION_HARD_CAP_MS = 120_000
    const val MAX_OVERLAP_MS = 1_000

    val SEGMENT_SOFT_CAP_SAMPLES = SEGMENT_SOFT_CAP_MS * SAMPLE_RATE_HZ / 1000 // 464,000
    val SEGMENT_HARD_CAP_SAMPLES = SEGMENT_HARD_CAP_MS * SAMPLE_RATE_HZ / 1000 // 480,000
    val SESSION_SOFT_CAP_SAMPLES = SESSION_SOFT_CAP_MS * SAMPLE_RATE_HZ / 1000 // 1,904,000
    val SESSION_HARD_CAP_SAMPLES = SESSION_HARD_CAP_MS * SAMPLE_RATE_HZ / 1000 // 1,920,000
    val MAX_OVERLAP_SAMPLES = MAX_OVERLAP_MS * SAMPLE_RATE_HZ / 1000 // 16,000
    const val BYTES_PER_SAMPLE = 2
    val SEGMENT_HARD_CAP_BYTES = SEGMENT_HARD_CAP_SAMPLES * BYTES_PER_SAMPLE // 960,000

    /** Canonical sample range [startSampleInclusive, endSampleExclusive). */
    data class AudioSpan(val startSampleInclusive: Int, val endSampleExclusive: Int) {
        init {
            require(startSampleInclusive >= 0 && endSampleExclusive >= startSampleInclusive)
        }
        val lengthSamples: Int get() = endSampleExclusive - startSampleInclusive
    }

    data class SplitPlan(
        val splitSample: Int,
        val atSafePause: Boolean,
        val overlap: AudioSpan,
    )

    /**
     * Returns null while the segment is under the soft cap (no split needed).
     * At/over the soft cap: take [safePauseSample] when it lies inside the
     * bounded overlap window below the hard cap, else split at the hard cap.
     */
    fun planSegmentSplit(capturedSamples: Int, safePauseSample: Int?): SplitPlan? {
        require(capturedSamples >= 0)
        if (capturedSamples < SEGMENT_SOFT_CAP_SAMPLES) return null
        val splitAt = if (safePauseSample != null &&
            safePauseSample >= capturedSamples - MAX_OVERLAP_SAMPLES &&
            safePauseSample <= SEGMENT_HARD_CAP_SAMPLES &&
            safePauseSample > 0
        ) {
            safePauseSample
        } else {
            minOf(capturedSamples, SEGMENT_HARD_CAP_SAMPLES)
        }
        val overlapStart = maxOf(0, splitAt - MAX_OVERLAP_SAMPLES)
        return SplitPlan(
            splitSample = splitAt,
            atSafePause = safePauseSample != null && splitAt == safePauseSample,
            overlap = AudioSpan(overlapStart, splitAt),
        )
    }

    /** True once the whole session reaches the hard 120 s safety ceiling. */
    fun sessionLimitReached(sessionSamples: Int): Boolean {
        require(sessionSamples >= 0)
        return sessionSamples >= SESSION_HARD_CAP_SAMPLES
    }

    /** True once the session passes the 119 s soft mark (start wrap-up, keep capturing). */
    fun sessionSoftCapReached(sessionSamples: Int): Boolean {
        require(sessionSamples >= 0)
        return sessionSamples >= SESSION_SOFT_CAP_SAMPLES
    }

    data class Reconciled(
        val text: String,
        val overlapDeduped: Boolean,
        val overlapSpan: AudioSpan?,
    )

    private val tokenRegex = Regex("[\\p{L}\\p{N}]+")

    private fun tokens(text: String): List<String> =
        tokenRegex.findAll(text.lowercase()).map { it.value }.toList()

    /**
     * Verified reconciliation of the overlapped region: strips the later
     * segment's leading words only when its first [overlapWords] tokens exactly
     * equal the earlier segment's last [overlapWords] tokens. Otherwise both
     * sides are kept (space-joined); overlap is never silently deleted.
     */
    fun reconcile(
        earlierText: String,
        laterText: String,
        overlapWords: Int,
        overlapSpan: AudioSpan? = null,
    ): Reconciled {
        require(overlapWords >= 0)
        if (earlierText.isBlank()) return Reconciled(laterText.trim(), false, overlapSpan)
        if (laterText.isBlank()) return Reconciled(earlierText.trim(), false, overlapSpan)
        if (overlapWords == 0) {
            return Reconciled(earlierText.trim() + " " + laterText.trim(), false, overlapSpan)
        }
        val earlierTokens = tokens(earlierText)
        val laterTokens = tokens(laterText)
        if (earlierTokens.size >= overlapWords && laterTokens.size >= overlapWords &&
            earlierTokens.takeLast(overlapWords) == laterTokens.take(overlapWords)
        ) {
            // Drop exactly the verified duplicated leading words from `later`,
            // preserving its original casing/punctuation for the remainder.
            var wordsToDrop = overlapWords
            var idx = 0
            val raw = laterText.trim()
            while (wordsToDrop > 0 && idx < raw.length) {
                while (idx < raw.length && !raw[idx].isLetterOrDigit()) idx++
                while (idx < raw.length && raw[idx].isLetterOrDigit()) idx++
                wordsToDrop--
            }
            val remainder = raw.substring(idx).trim().trimStart('.', ',', ';', ':', '!', '?')
            val head = earlierText.trim()
            return Reconciled(
                if (remainder.isEmpty()) head else "$head ${remainder.trim()}",
                true,
                overlapSpan,
            )
        }
        return Reconciled(earlierText.trim() + " " + laterText.trim(), false, overlapSpan)
    }
}
