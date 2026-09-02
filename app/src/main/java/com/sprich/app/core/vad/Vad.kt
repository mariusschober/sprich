package com.sprich.app.core.vad

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tiny energy-based VAD. No ML, configurable, monotonic.
 * Calibrates noise floor on first 300ms.
 * States: SILENCE -> SPEECH -> HESITATION -> UTTERANCE_END -> LONG_SILENCE
 */
class Vad(
    var speechOnsetMs: Int = 45,
    var hesitationMs: Int = 400,
    var utteranceEndMs: Int = 650,
    var longSilenceMs: Int = 1800,
    var energyThreshold: Float = 0.0012f,
    var calibrationMs: Int = 150,
) {
    enum class State { SILENCE, SPEECH, HESITATION, UTTERANCE_END, LONG_SILENCE }

    private var state = State.SILENCE
    private var noiseFloor: Float = 0.0015f
    private var calibrating = true
    private var calibrationElapsedMs = 0L
    private var quietCalibrationFrames = 0
    private var calEnergySum = 0f
    private var speechMsAcc: Long = 0
    private var silenceMsAcc: Long = 0
    private var hasSpeechEver = false

    data class Result(val state: State, val isSpeech: Boolean, val rms: Float)

    @Synchronized fun reset() {
        state = State.SILENCE
        calibrating = true
        calibrationElapsedMs = 0
        quietCalibrationFrames = 0
        calEnergySum = 0f
        noiseFloor = 0.0015f
        speechMsAcc = 0; silenceMsAcc = 0; hasSpeechEver = false
    }

    @Synchronized fun calibrateIfNeeded(samples: ShortArray, durationMs: Long = 64, precomputedRms: Float? = null): Float {
        val rms = precomputedRms ?: rms(samples, 0, samples.size)
        if (calibrating) {
            calibrationElapsedMs += durationMs
            // Never learn a likely speech frame as room noise. This is what lets a user
            // speak immediately after tapping without raising the threshold above speech.
            if (rms < energyThreshold) {
                calEnergySum += rms
                quietCalibrationFrames++
            }
            if (calibrationElapsedMs >= calibrationMs) {
                if (quietCalibrationFrames > 0) {
                    noiseFloor = calEnergySum / quietCalibrationFrames
                }
                calibrating = false
            }
        }
        return rms
    }

    @Synchronized fun calibrateIfNeeded(samples: ShortArray, offset: Int, length: Int, durationMs: Long, precomputedRms: Float): Float {
        val rms = precomputedRms // already computed from window
        if (calibrating) {
            calibrationElapsedMs += durationMs
            if (rms < energyThreshold) {
                calEnergySum += rms
                quietCalibrationFrames++
            }
            if (calibrationElapsedMs >= calibrationMs) {
                if (quietCalibrationFrames > 0) noiseFloor = calEnergySum / quietCalibrationFrames
                calibrating = false
            }
        }
        return rms
    }

    @Synchronized fun process(samples: ShortArray, durationMs: Long): Result {
        val rms = calibrateIfNeeded(samples, durationMs)
        val threshold = maxOf(energyThreshold, noiseFloor * 1.15f)
        val isSpeechFrame = rms > threshold

        // Update accumulators
        if (isSpeechFrame) {
            speechMsAcc += durationMs
            silenceMsAcc = 0
        } else {
            silenceMsAcc += durationMs
            // speech accumulator decays after silence? keep for onset detection
            if (silenceMsAcc > 200) speechMsAcc = 0
        }

        val prev = state
        state = when (state) {
            State.SILENCE -> if (isSpeechFrame && speechMsAcc >= speechOnsetMs) {
                hasSpeechEver = true
                State.SPEECH
            } else State.SILENCE
            State.SPEECH -> if (!isSpeechFrame) State.HESITATION else State.SPEECH
            State.HESITATION -> when {
                isSpeechFrame -> State.SPEECH
                silenceMsAcc >= longSilenceMs -> State.LONG_SILENCE
                silenceMsAcc >= utteranceEndMs -> State.UTTERANCE_END
                else -> State.HESITATION
            }
            State.UTTERANCE_END -> if (isSpeechFrame) State.SPEECH else if (silenceMsAcc >= longSilenceMs) State.LONG_SILENCE else State.UTTERANCE_END
            State.LONG_SILENCE -> if (isSpeechFrame) State.SPEECH else State.LONG_SILENCE
        }

        val speech = state == State.SPEECH || state == State.HESITATION
        return Result(state, speech, rms)
    }

    @Synchronized fun process(samples: ShortArray, offset: Int, length: Int, durationMs: Long, precomputedRms: Float): Result {
        val rms = calibrateIfNeeded(samples, offset, length, durationMs, precomputedRms)
        val threshold = maxOf(energyThreshold, noiseFloor * 1.15f)
        val isSpeechFrame = rms > threshold
        if (isSpeechFrame) {
            speechMsAcc += durationMs
            silenceMsAcc = 0
        } else {
            silenceMsAcc += durationMs
            if (silenceMsAcc > 200) speechMsAcc = 0
        }
        state = when (state) {
            State.SILENCE -> if (isSpeechFrame && speechMsAcc >= speechOnsetMs) {
                hasSpeechEver = true
                State.SPEECH
            } else State.SILENCE
            State.SPEECH -> if (!isSpeechFrame) State.HESITATION else State.SPEECH
            State.HESITATION -> when {
                isSpeechFrame -> State.SPEECH
                silenceMsAcc >= longSilenceMs -> State.LONG_SILENCE
                silenceMsAcc >= utteranceEndMs -> State.UTTERANCE_END
                else -> State.HESITATION
            }
            State.UTTERANCE_END -> if (isSpeechFrame) State.SPEECH else if (silenceMsAcc >= longSilenceMs) State.LONG_SILENCE else State.UTTERANCE_END
            State.LONG_SILENCE -> if (isSpeechFrame) State.SPEECH else State.LONG_SILENCE
        }
        val speech = state == State.SPEECH || state == State.HESITATION
        return Result(state, speech, rms)
    }

    private fun rms(samples: ShortArray, offset: Int, length: Int): Float {
        if (length <= 0) return 0f
        var sum = 0.0
        val end = offset + length
        for (i in offset until end) {
            val f = samples[i] / 32768f
            sum += f * f
        }
        return sqrt(sum / length).toFloat()
    }

    private fun rms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) {
            val f = s / 32768f
            sum += f * f
        }
        return sqrt(sum / samples.size).toFloat()
    }

    @Synchronized fun hasSpeech(): Boolean = hasSpeechEver
    @Synchronized fun currentState(): State = state
    @Synchronized fun isCalibrating(): Boolean = calibrating
    @Synchronized fun calibrationInfo(): String = "calibrating=$calibrating noiseFloor=$noiseFloor threshold=${maxOf(energyThreshold, noiseFloor * 1.15f)}"
    @Synchronized fun noiseFloorValue(): Float = noiseFloor
    @Synchronized fun currentThreshold(): Float = maxOf(energyThreshold, noiseFloor * 1.15f)
}
