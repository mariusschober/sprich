package com.sprich.app

import com.sprich.app.ui.setup.keyboardStepReady
import com.sprich.app.ui.setup.practiceReady
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupReadinessTest {
    @Test fun practiceStaysHiddenUntilSprichIsTheSelectedKeyboard() {
        assertFalse(practiceReady(true, true, false, true, true))
        assertTrue(practiceReady(true, true, true, true, true))
    }

    @Test fun practiceAlsoRequiresMicrophoneAndRecognitionReadiness() {
        assertFalse(practiceReady(true, true, true, false, true))
        assertFalse(practiceReady(true, true, true, true, false))
        assertFalse(practiceReady(false, true, true, true, true))
    }

    @Test fun onboardingDoesNotAdvanceOnEnabledAlone() {
        assertFalse(keyboardStepReady(enabled = true, selected = false))
        assertTrue(keyboardStepReady(enabled = true, selected = true))
        assertFalse(keyboardStepReady(enabled = false, selected = true))
    }
}
