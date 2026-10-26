package com.sprich.app.input.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the U02 dictated-replacement guard.
 *
 * The historical defect was not the comparison itself but its inputs: the endpoint timestamp
 * came from System.nanoTime() (CLOCK_MONOTONIC) while the arming time came from
 * SystemClock.elapsedRealtime() (CLOCK_BOOTTIME). After any deep sleep the endpoint value fell
 * behind the armed value by the total suspend time, so a replacement dictated into the
 * correction sheet was committed into the document instead.
 *
 * These tests pin the contract the production sites rely on: both arguments are nanosecond
 * values from the SAME monotonic clock, so elapsed suspend time can never reorder them.
 */
class CorrectionDictationRoutingTest {

    // ~16 days of device uptime, measured in the nanosecond scale used by System.nanoTime().
    private val uptime = 16L * 24 * 60 * 60 * 1_000_000_000L

    @Test
    fun utteranceEndpointedAfterArmIsDelivered() {
        val armedAt = uptime
        val nextUtteranceEndpoint = uptime + 500_000_000L // 0.5 s after tapping Dictate
        assertTrue(correctionDictationEndedAfterArm(nextUtteranceEndpoint, armedAt))
    }

    @Test
    fun utteranceAlreadyInFlightWhenArmingHappenedIsNotHijacked() {
        val armedAt = uptime
        val inFlightEndpoint = uptime - 1_000_000L // captured before Dictate was tapped
        assertFalse(correctionDictationEndedAfterArm(inFlightEndpoint, armedAt))
    }

    @Test
    fun sameInstantIsAccepted() {
        assertTrue(correctionDictationEndedAfterArm(uptime, uptime))
    }

    @Test
    fun realMonotonicClockOrdersArmThenEndpoint() {
        val armedAt = System.nanoTime()
        val endpoint = System.nanoTime()
        // The clock is monotonic, so a later read is never earlier. It may be equal on a
        // low-resolution scheduler tick; only assert a strict ordering when one exists.
        assertTrue(correctionDictationEndedAfterArm(endpoint, armedAt))
        if (endpoint > armedAt) {
            assertFalse(correctionDictationEndedAfterArm(armedAt, endpoint))
        }
    }

    @Test
    fun longSuspendTimeCannotReorderSameDomainTimestamps() {
        // A device that slept for 8 hours still armed and endpointed on one clock: the endpoint
        // arrives later in that same domain. The guard is a monotonic comparison, not wall time.
        val armedAt = uptime
        val endpointAfterEightHoursOfSuspend = armedAt + 8L * 60 * 60 * 1_000_000_000L
        assertTrue(correctionDictationEndedAfterArm(endpointAfterEightHoursOfSuspend, armedAt))
    }
}
