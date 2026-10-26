package com.sprich.app.speech.canary

import kotlin.math.sqrt

/**
 * R1: defensive digital-silence gate for the Canary engine.
 *
 * The previous threshold (RMS < 0.0005) rejected quiet but valid speech: in the 2026-10-25
 * FLEURS bake-off, 5 of 7 blank English clips had RMS 0.00042-0.00049, i.e. they were discarded
 * by this gate before the recognizer ran. The engine only needs to refuse true digital silence
 * or near-zero input; whether an utterance contains speech is decided by the VAD/endpoint layer
 * and the immutable utterance authority, never by an amplitude heuristic here.
 */
object CanarySpeechGate {
    /** Only true/near-digital silence is rejected here. */
    const val SILENCE_RMS_THRESHOLD = 0.0001

    fun isEffectivelySilent(pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return true
        var sum = 0.0
        for (sample in pcm) {
            val f = sample / 32768.0
            sum += f * f
        }
        return sqrt(sum / pcm.size) < SILENCE_RMS_THRESHOLD
    }
}
