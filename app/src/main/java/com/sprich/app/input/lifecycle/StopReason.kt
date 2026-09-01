package com.sprich.app.input.lifecycle

/**
 * Semantically distinct stop reasons — each dictates whether an active utterance
 * may be finalized or must be cancelled without insertion.
 */
enum class StopReason {
    USER_STOP,          // User tapped stop — may finalize active utterance if audio present
    ENDPOINT,           // VAD natural endpoint — always finalize
    FIELD_LOST,         // onFinishInput / focus lost — never insert stale text into another field
    INPUT_RESTARTED,    // onStartInput(restarting=true) — never insert after restart
    WINDOW_HIDDEN,      // onWindowHidden — prioritize safety, cancel speculative final
    PASSWORD_FIELD,     // Password field detected — cancel immediately
    ERROR,              // Engine/audio error — cancel
    SERVICE_DESTROYED,  // onDestroy — cancel
}
