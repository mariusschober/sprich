package com.sprich.app

import com.sprich.app.ui.onboarding.ONBOARDING_GESTURE_STEP
import com.sprich.app.ui.onboarding.ONBOARDING_KEYBOARD_STEP
import com.sprich.app.ui.onboarding.ONBOARDING_MODEL_STEP
import com.sprich.app.ui.onboarding.ONBOARDING_STEPS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFlowTest {
    @Test fun gesturesAreTaughtBeforeTheModelDownload() {
        assertTrue(ONBOARDING_GESTURE_STEP > ONBOARDING_KEYBOARD_STEP)
        assertTrue(ONBOARDING_MODEL_STEP > ONBOARDING_GESTURE_STEP)
        assertEquals(ONBOARDING_MODEL_STEP, ONBOARDING_STEPS - 1)
    }
}
