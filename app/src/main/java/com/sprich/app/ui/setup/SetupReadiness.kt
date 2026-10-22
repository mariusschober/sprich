package com.sprich.app.ui.setup

/** Practice and onboarding are ready only when Sprich is both enabled and the selected IME. */
internal fun practiceReady(
    configLoaded: Boolean,
    keyboardEnabled: Boolean,
    keyboardSelected: Boolean,
    microphoneGranted: Boolean,
    recognitionReady: Boolean,
): Boolean = configLoaded && keyboardEnabled && keyboardSelected && microphoneGranted && recognitionReady

internal fun keyboardStepReady(enabled: Boolean, selected: Boolean): Boolean = enabled && selected
