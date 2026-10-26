package com.sprich.app.input.ime

/**
 * U02 "dictate replacement" routing.
 *
 * An utterance may only be delivered into the open correction sheet when its endpoint was
 * captured after the sheet armed dictation. Both timestamps MUST come from the same monotonic
 * clock source: [System.nanoTime] (CLOCK_MONOTONIC, which does not advance while the device is
 * suspended).
 *
 * Mixing that clock with [android.os.SystemClock.elapsedRealtime] (CLOCK_BOOTTIME, which does
 * advance during suspend) silently breaks the guard after any deep sleep: the endpoint value
 * falls behind the armed value by the total susp time, so the replacement is committed into the
 * document while the sheet stays armed. Keep arming and comparison on one clock.
 */
internal fun correctionDictationEndedAfterArm(
    endpointMonotonicNanos: Long,
    armedMonotonicNanos: Long,
): Boolean = endpointMonotonicNanos >= armedMonotonicNanos
