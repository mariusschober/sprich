package com.sprich.app.input.lifecycle

import android.os.SystemClock
import com.sprich.app.core.perf.LatencyTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class SessionState {
    object Idle : SessionState()
    object Preparing : SessionState()
    object Listening : SessionState()
    object Speech : SessionState()
    object Finalizing : SessionState()
    object Ending : SessionState()
    data class Error(val reason: String) : SessionState()
    object Suspended : SessionState()
}

class DictationSession(
    private val tracker: LatencyTracker = LatencyTracker(),
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state

    @Volatile var isActive: Boolean = false

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
            is SessionState.Listening -> to is SessionState.Speech || to is SessionState.Finalizing || to is SessionState.Ending || to is SessionState.Error || to is SessionState.Suspended
            is SessionState.Speech -> to is SessionState.Finalizing || to is SessionState.Listening || to is SessionState.Ending || to is SessionState.Error
            is SessionState.Finalizing -> to is SessionState.Listening || to is SessionState.Ending || to is SessionState.Error
            is SessionState.Ending -> to is SessionState.Idle || to is SessionState.Error
            is SessionState.Error -> to is SessionState.Idle || to is SessionState.Preparing
            is SessionState.Suspended -> to is SessionState.Idle || to is SessionState.Listening || to is SessionState.Error
        }
    }

    fun start() {
        tracker.beginSession()
        tracker.mark("focusDetected")
        transition(SessionState.Preparing)
        isActive = true
    }

    fun onAudioStarted() { tracker.mark("audioActuallyRecording"); transition(SessionState.Listening) }
    fun onSpeechOnset() { tracker.mark("speechOnset"); transition(SessionState.Speech) }
    fun onFinalizing() { tracker.mark("endpointDetected"); transition(SessionState.Finalizing) }
    fun onListeningAgain() { transition(SessionState.Listening) }
    fun end() { transition(SessionState.Ending); isActive = false; tracker.mark("sessionEnd"); transition(SessionState.Idle) }
    fun error(msg: String) { transition(SessionState.Error(msg)) }
    fun idle() { isActive=false; transition(SessionState.Idle) }

    fun requireActive(): Boolean = isActive && state.value != SessionState.Idle && state.value !is SessionState.Error
}
