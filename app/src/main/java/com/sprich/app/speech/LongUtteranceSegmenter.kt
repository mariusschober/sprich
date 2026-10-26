package com.sprich.app.speech

import com.sprich.app.input.typography.TypographyNormalizer
import com.sprich.app.speech.api.Language
import kotlin.math.sqrt

/** One decode segment of frozen PCM; [length] is in samples. */
data class SampleRange(val start: Int, val length: Int)

/**
 * R1: bounded, lowest-energy segmentation for long utterances before offline Canary decoding.
 *
 * The production PCM bound is 29 s and the Canary export is an offline recognizer; decoding one
 * very long window is the least safe case (and the 2026-10-25 FLEURS run showed blanks on long
 * English clips). Long utterances are therefore split at the quietest point inside a search
 * window around the target boundary, which lands in a pause between words far more reliably than
 * a fixed cut. Segments are decoded in order and joined with typography rules; the IME still
 * performs exactly one editor mutation for the whole utterance.
 *
 * This is a deterministic pure component so the split policy and the join are host-testable.
 */
object LongUtteranceSegmenter {
    /** Frames RMS measured over 20 ms are cheap and discriminate pauses from speech. */
    private const val FRAME_SAMPLES = 320
    /** Do not create segments shorter than this (0.75 s). */
    private const val MIN_SEGMENT_SAMPLES = 12_000

    /**
     * Plan decode segments. A PCM buffer at or below [safeLimit] is one segment. For longer input
     * the boundary is searched +/-[searchWindow] samples around each target boundary and snapped
     * to the local minimum-energy frame (ties prefer the frame closest to the midpoint and then a
     * zero crossing at the frame edge).
     */
    fun plan(
        pcm: ShortArray,
        safeLimit: Int = 20 * 16_000,
        searchWindow: Int = 2 * 16_000,
    ): List<SampleRange> {
        require(safeLimit >= 3 * MIN_SEGMENT_SAMPLES) { "safe limit too small" }
        if (pcm.size <= safeLimit) return listOf(SampleRange(0, pcm.size))
        val segments = ArrayList<SampleRange>()
        var start = 0
        while (start < pcm.size) {
            val remaining = pcm.size - start
            if (remaining <= safeLimit) {
                segments += SampleRange(start, remaining)
                break
            }
            val target = start + safeLimit
            val low = maxOf(start + MIN_SEGMENT_SAMPLES, target - searchWindow)
            val high = minOf(pcm.size - MIN_SEGMENT_SAMPLES, target + searchWindow)
            val boundary = if (high > low) quietestBoundary(pcm, low, high, target) else target
            segments += SampleRange(start, boundary - start)
            start = boundary
        }
        return segments
    }

    private fun quietestBoundary(pcm: ShortArray, low: Int, high: Int, target: Int): Int {
        val starts = ArrayList<Int>()
        val rmsValues = ArrayList<Double>()
        var frame = low
        while (frame + FRAME_SAMPLES <= high) {
            var sum = 0.0
            for (i in frame until frame + FRAME_SAMPLES) {
                val f = pcm[i] / 32768.0
                sum += f * f
            }
            starts += frame
            rmsValues += sqrt(sum / FRAME_SAMPLES)
            frame += FRAME_SAMPLES
        }
        if (rmsValues.isEmpty()) return target
        val minRms = rmsValues.min()
        // Use the centre of the whole quiet plateau, not its edge nearest the target: an edge
        // split risks cutting the first phoneme of the next word.
        val plateau = rmsValues.indices.filter { rmsValues[it] <= minRms + 1e-6 }
        val plateauCenter = starts[plateau[plateau.size / 2]] + FRAME_SAMPLES / 2
        return snapToQuietZeroCrossing(pcm, plateauCenter)
    }

    /** Nudge the boundary to a zero crossing within +/-5 ms so a split cannot cut a waveform. */
    private fun snapToQuietZeroCrossing(pcm: ShortArray, boundary: Int): Int {
        val span = 80 // 5 ms at 16 kHz
        var best = boundary.coerceIn(1, pcm.size - 1)
        var bestScore = Int.MAX_VALUE
        for (i in maxOf(1, boundary - span)..minOf(pcm.size - 2, boundary + span)) {
            val crossing = (pcm[i] >= 0) != (pcm[i + 1] >= 0)
            if (!crossing) continue
            val score = kotlin.math.abs(i - boundary)
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    /** Join decoded segments with one space and the deterministic typography rules. */
    fun join(parts: List<String>, language: Language = Language.AUTO): String {
        val nonBlank = parts.map { it.trim() }.filter { it.isNotEmpty() }
        if (nonBlank.isEmpty()) return ""
        return TypographyNormalizer.normalize(nonBlank.joinToString(" "), language)
    }
}
