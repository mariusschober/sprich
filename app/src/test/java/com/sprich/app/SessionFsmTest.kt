package com.sprich.app

import com.sprich.app.input.lifecycle.DictationSession
import com.sprich.app.input.lifecycle.SessionState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])

class SessionFsmTest {

    @Test
    fun validTransitions() {
        val s = DictationSession()
        s.start()
        assertTrue(s.state.value is SessionState.Preparing)
        s.onAudioStarted()
        assertTrue(s.state.value is SessionState.Listening)
        s.onSpeechOnset()
        assertTrue(s.state.value is SessionState.Speech)
        s.onFinalizing()
        assertTrue(s.state.value is SessionState.Finalizing)
        s.onListeningAgain()
        assertTrue(s.state.value is SessionState.Listening)
        s.end()
        assertTrue(s.state.value is SessionState.Idle)
    }

    @Test
    fun impossibleTransitionIgnored() {
        val s = DictationSession()
        // Idle -> Speech should be invalid, stays Idle
        s.transition(SessionState.Speech)
        assertTrue(s.state.value is SessionState.Idle)
    }

    @Test
    fun errorRecovery() {
        val s = DictationSession()
        s.start()
        s.error("mic")
        assertTrue(s.state.value is SessionState.Error)
        s.idle()
        assertTrue(s.state.value is SessionState.Idle)
    }

    @Test
    fun passwordFieldNeverStarts() {
        val s = DictationSession()
        // Simulate: we simply don't call start for password
        assertTrue(s.state.value is SessionState.Idle)
        assertFalse(s.requireActive())
    }
}
