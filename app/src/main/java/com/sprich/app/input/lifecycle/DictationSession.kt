package com.sprich.app.input.lifecycle

import android.os.SystemClock
import com.sprich.app.core.perf.LatencyTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class SessionState {
    object Idle : SessionState()
    object Preparing : SessionState() // Arming
    object Listening : SessionState()
    object Speech : SessionState()
    object Finalizing : SessionState()
    object Inserting : SessionState()
    object Ending : SessionState()
    data class Error(val reason: String) : SessionState() // RecoverableError
    object Suspended : SessionState() // Paused
    object Paused : SessionState()
}

class DictationSession(
    private val tracker: LatencyTracker = LatencyTracker(),
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state

    @Volatile var isActive: Boolean = false
    // Unique session ID per focus/capture cycle. Late callbacks from old session are ignored.
    @Volatile var sessionId: Long = 0L
        private set
    private var nextSessionId: Long = 1L

    fun transition(to: SessionState) {
        val from = _state.value
        // Guard impossible transitions
        if (!isValidTransition(from, to)) {
            // Log but allow recovery to Idle/Error
            if (to is SessionState.Idle || to is SessionState.Error) {
                _state.value = to
                return
            }
            return
        }
        _state.value = to
        tracker.mark("state:${to::class.simpleName}")
    }

    private fun isValidTransition(from: SessionState, to: SessionState): Boolean {
        return when (from) {
            is SessionState.Idle -> to is SessionState.Preparing || to is SessionState.Error
            is SessionState.Preparing -> to is SessionState.Listening || to is SessionState.Error || to is SessionState.Idle
            is SessionState.Listening -> to is SessionState.Speech || to is SessionState.Finalizing || to is SessionState.Ending || to is SessionState.Error || to is SessionState.Suspended || to is SessionState.Paused
            is SessionState.Speech -> to is SessionState.Finalizing || to is SessionState.Listening || to is SessionState.Ending || to is SessionState.Error || to is SessionState.Inserting
            is SessionState.Finalizing -> to is SessionState.Inserting || to is SessionState.Listening || to is SessionState.Ending || to is SessionState.Error
            is SessionState.Inserting -> to is SessionState.Listening || to is SessionState.Ending || to is SessionState.Error
            is SessionState.Ending -> to is SessionState.Idle || to is SessionState.Error
            is SessionState.Error -> to is SessionState.Idle || to is SessionState.Preparing
            is SessionState.Suspended -> to is SessionState.Idle || to is SessionState.Listening || to is SessionState.Error
            is SessionState.Paused -> to is SessionState.Idle || to is SessionState.Listening || to is SessionState.Error
        }
    }

    fun start(): Long {
        sessionId = nextSessionId++
        tracker.beginSession()
        tracker.mark("focusDetected")
        tracker.mark("sessionId:$sessionId")
        transition(SessionState.Preparing)
        isActive = true
        return sessionId
    }

    fun onAudioStarted() { tracker.mark("audioActuallyRecording"); transition(SessionState.Listening) }
    fun onSpeechOnset() { tracker.mark("speechOnset"); transition(SessionState.Speech) }
    fun onFinalizing() { tracker.mark("endpointDetected"); transition(SessionState.Finalizing) }
    fun onInserting() { tracker.mark("inserting"); transition(SessionState.Inserting) }
    fun onListeningAgain() { transition(SessionState.Listening) }
    fun end() { transition(SessionState.Ending); isActive = false; tracker.mark("sessionEnd"); transition(SessionState.Idle) }
    fun error(msg: String) { transition(SessionState.Error(msg)) }
    fun idle() { isActive=false; transition(SessionState.Idle) }
    fun pause() { transition(SessionState.Paused) }
    fun resumeFromPause() { transition(SessionState.Listening) }

    fun requireActive(): Boolean = isActive && state.value != SessionState.Idle && state.value !is SessionState.Error

    fun isSessionValid(id: Long): Boolean = id == sessionId && requireActive()
}
