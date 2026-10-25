package com.sprich.app.input.ime

/**
 * T2: one-shot voice-subtype policy.
 *
 * `method.xml` declares a `mode="voice"` auxiliary subtype next to the four typing subtypes.
 * Entering the IME through that subtype is a handoff, not a permanent keyboard choice: after
 * dictation inserts text, or after the session is cancelled or hidden, Sprich returns control to
 * the previously selected typing keyboard (API 28+; on API 26-27 the user returns with the globe
 * key, because the framework has no public previous-IME API there).
 *
 * The policy is a tiny state machine so the "return exactly once per entry" rule is testable
 * without an InputMethodService.
 */
internal class OneShotVoiceSubtypePolicy {

    private var active = false

    /** Called for every input view start with the current subtype mode (`null` if unknown). */
    fun onInputViewStarted(mode: String?) {
        active = mode == MODE_VOICE
    }

    /** True exactly once for a voice-subtype entry; consuming it disarms the return. */
    fun consumeReturn(): Boolean {
        val wasActive = active
        active = false
        return wasActive
    }

    fun reset() {
        active = false
    }

    fun isActive(): Boolean = active

    companion object {
        const val MODE_VOICE = "voice"
    }
}
