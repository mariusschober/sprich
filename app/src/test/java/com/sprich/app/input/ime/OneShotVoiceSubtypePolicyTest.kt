package com.sprich.app.input.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotVoiceSubtypePolicyTest {

    @Test
    fun voiceSubtypeEntryArmsExactlyOneReturn() {
        val policy = OneShotVoiceSubtypePolicy()
        policy.onInputViewStarted(OneShotVoiceSubtypePolicy.MODE_VOICE)
        assertTrue(policy.isActive())
        assertTrue(policy.consumeReturn())
        assertFalse(policy.isActive())
        // A second stop/hide callback in the same session must not switch keyboards again.
        assertFalse(policy.consumeReturn())
    }

    @Test
    fun typingSubtypesNeverReturnAutomatically() {
        for (mode in listOf("keyboard", null, "unknown")) {
            val policy = OneShotVoiceSubtypePolicy()
            policy.onInputViewStarted(mode)
            assertFalse("mode=$mode", policy.isActive())
            assertFalse("mode=$mode", policy.consumeReturn())
        }
    }

    @Test
    fun aNewFieldResetsThePreviousEntry() {
        val policy = OneShotVoiceSubtypePolicy()
        policy.onInputViewStarted(OneShotVoiceSubtypePolicy.MODE_VOICE)
        policy.reset()
        assertFalse(policy.consumeReturn())
    }
}
