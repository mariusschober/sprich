package com.sprich.app.input.lifecycle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U03 — stop-time decode policy (pure JVM).
 *
 * Abandon-type stops let an in-flight snapshot decode finish so post-decode
 * validation can refuse insertion AND preserve obtainable text as a recovery
 * draft. Only explicit user cancel, sensitive fields, and teardown kill it.
 */
class StopReasonDecodePolicyTest {

    @Test fun abandonStopsPreserveInflightDecode() {
        assertFalse(StopReason.WINDOW_HIDDEN.cancelsInflightDecode())
        assertFalse(StopReason.FIELD_LOST.cancelsInflightDecode())
        assertFalse(StopReason.INPUT_RESTARTED.cancelsInflightDecode())
        assertFalse(StopReason.CURSOR_MOVED.cancelsInflightDecode())
        assertFalse(StopReason.ERROR.cancelsInflightDecode())
    }

    @Test fun userCancelSensitiveAndTeardownKillDecode() {
        assertTrue(StopReason.USER_STOP.cancelsInflightDecode())
        assertTrue(StopReason.PASSWORD_FIELD.cancelsInflightDecode())
        assertTrue(StopReason.SERVICE_DESTROYED.cancelsInflightDecode())
    }

    @Test fun drainAndEndpointPathsUnaffected() {
        // SESSION_LIMIT/ENDPOINT never reach the non-finalizing cancel branch,
        // but the predicate must stay total over the enum.
        for (reason in StopReason.entries) {
            reason.cancelsInflightDecode()
        }
    }
}
