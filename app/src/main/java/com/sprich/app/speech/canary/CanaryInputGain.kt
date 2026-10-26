package com.sprich.app.speech.canary

import kotlin.math.sqrt

/**
 * R1: deterministic input conditioning for the Canary recognizer.
 *
 * The 2026-10-25 FLEURS re-check showed Canary returning empty text for very quiet but valid
 * English speech (RMS 0.00042-0.00289) even though the same PCM decoded correctly at ~10x gain.
 * The microphone path can produce such quiet utterances (distant speaker, low sensitivity), so
 * quiet input is amplified to a fixed target RMS before the waveform is handed to sherpa. Normal
 * and loud input passes through unchanged; near-digital silence is not amplified (it is rejected
 * by [CanarySpeechGate] first).
 *
 * Gain is bounded and deterministic; it changes only decode conditioning, never utterance
 * authority, routing or editor mutation.
 */
object CanaryInputGain {
    const val TARGET_RMS = 0.05
    const val QUIET_RMS_THRESHOLD = 0.01
    const val MAX_GAIN = 40.0

    /**
     * Below this the defensive silence gate rejects the waveform; do not amplify what is
     * essentially digital/near-digital silence (it is never decoded).
     */
    const val MIN_SPEECH_RMS = 0.0002

    fun gainFor(rms: Double): Double {
        if (rms <= MIN_SPEECH_RMS || rms >= QUIET_RMS_THRESHOLD) return 1.0
        return (TARGET_RMS / rms).coerceAtMost(MAX_GAIN)
    }

    fun normalize(pcm: ShortArray): ShortArray {
        if (pcm.isEmpty()) return pcm
        var sum = 0.0
        for (sample in pcm) {
            val f = sample / 32768.0
            sum += f * f
        }
        val rms = sqrt(sum / pcm.size)
        val gain = gainFor(rms)
        if (gain == 1.0) return pcm
        return ShortArray(pcm.size) { index ->
            (pcm[index] * gain).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }
}
