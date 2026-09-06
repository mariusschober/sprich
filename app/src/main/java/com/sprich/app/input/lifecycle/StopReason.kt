package com.sprich.app.input.lifecycle

/**
 * Semantically distinct stop reasons — each dictates whether an active utterance
 * may be finalized or must be cancelled without insertion.
 */
enum class StopReason {
    USER_STOP,          // User tapped stop — may finalize active utterance if audio present
    ENDPOINT,           // VAD natural endpoint — always finalize
    CURSOR_MOVED,       // Manual cursor/selection change cancels uninserted dictation
    FIELD_LOST,         // onFinishInput / focus lost — never insert stale text into another field
    INPUT_RESTARTED,    // onStartInput(restarting=true) — never insert after restart
    WINDOW_HIDDEN,      // onWindowHidden — prioritize safety, cancel speculative final
    PASSWORD_FIELD,     // Password field detected — cancel immediately
    ERROR,              // Engine/audio error — cancel
    SERVICE_DESTROYED,  // onDestroy — cancel
    SESSION_LIMIT,      // P05 §5.3: 120 s session safety ceiling — drain like USER_STOP, keep field
    ;

    /**
     * U03: whether a stop kills an in-flight snapshot decode immediately.
     * Abandon-type stops (field lost, window hidden, cursor moved, restart,
     * error) let the decode run to completion: post-decode validation still
     * refuses insertion (stale generation) and preserves obtainable text as a
     * recovery draft. Only explicit user cancel, sensitive fields, and
     * teardown cancel the decode — nothing is retained on those paths.
     */
    fun cancelsInflightDecode(): Boolean =
        this == USER_STOP || this == PASSWORD_FIELD || this == SERVICE_DESTROYED
}
