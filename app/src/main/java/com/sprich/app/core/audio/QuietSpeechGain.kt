package com.sprich.app.core.audio

import kotlin.math.abs
import kotlin.math.min

/** Capture-owned level boost. No allocation, no noise synthesis, and no sample may clip. */
internal class QuietSpeechGain {
    private var gain = MAX_GAIN

    fun process(samples: ShortArray, length: Int) {
        require(length in 0..samples.size)
        if (length == 0) return
        var peak = 0
        for (i in 0 until length) peak = maxOf(peak, abs(samples[i].toInt()))
        if (peak == 0) return
        val safeGain = min(MAX_GAIN, 30_000f / peak)
        // Reduce immediately for a loud sound; recover slowly to avoid pumping between words.
        val from = min(gain, safeGain)
        gain = min(safeGain, from + (safeGain - from) * 0.15f)
        for (i in 0 until length) {
            val level = from + (gain - from) * (i + 1f) / length
            samples[i] = (samples[i] * level).toInt().coerceIn(-30_000, 30_000).toShort()
        }
    }

    companion object { const val MAX_GAIN = 3f }
}
