package com.sprich.app.core.audio

/**
 * Tested linear resampler for mono PCM16. Engine requires 16kHz; device may deliver
 * 48k, 44.1k or 16k depending on hardware. Verification via AudioRecord.getSampleRate
 * is device-dependent, so we enforce mono/16k at construction but provide resampling
 * for harness-generated buffers (e.g., 48k test fixtures) and future device paths.
 *
 * Quality: linear interpolation, bounded buffering, no allocation beyond output array.
 * Telemetry: caller should log clipping/RMS after resampling.
 */
object Resampler {
    /**
     * Resamples mono PCM16 from [srcRate] to 16000Hz.
     * Returns new ShortArray. Input is not modified.
     */
    fun resampleTo16k(input: ShortArray, srcRate: Int, dstRate: Int = 16000): ShortArray {
        if (srcRate == dstRate) return input.copyOf()
        if (input.isEmpty()) return ShortArray(0)
        val ratio = srcRate.toDouble() / dstRate
        val dstLen = (input.size / ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(dstLen)
        for (i in 0 until dstLen) {
            val srcPos = i * ratio
            val idx = srcPos.toInt().coerceIn(0, input.size - 1)
            val frac = (srcPos - idx).toFloat()
            val a = input[idx].toFloat()
            val b = if (idx + 1 < input.size) input[idx + 1].toFloat() else a
            val v = a + (b - a) * frac
            out[i] = v.coerceIn(-32768f, 32767f).toInt().toShort()
        }
        return out
    }

    /** Quality check: RMS before vs after should not clip beyond 0.99 linear. */
    fun rms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) {
            val f = s / 32768.0
            sum += f * f
        }
        return kotlin.math.sqrt(sum / samples.size).toFloat()
    }

    fun peak(samples: ShortArray): Int = samples.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
}
