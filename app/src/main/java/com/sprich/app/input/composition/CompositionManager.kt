package com.sprich.app.input.composition

import android.view.inputmethod.InputConnection
import android.util.Log

/**
 * Manages composing vs committed text via InputConnection.
 * Guarantees no duplication: only stable prefix is committed, unstable stays composing.
 * Handles cursor movement, manual edits, focus changes.
 */
class CompositionManager {

    private var lastStable: String = ""
    private var lastUnstable: String = ""
    private var composingActive = false
    private var cachedCursorStart = -1
    private var cachedCursorEnd = -1
    private var lastComposing: String? = null
    private var compositionRejected = false
    private var leadingSpaceNeeded: Boolean? = null

    fun reset() {
        lastStable = ""
        lastUnstable = ""
        composingActive = false
        cachedCursorStart = -1
        lastComposing = null
        compositionRejected = false
        leadingSpaceNeeded = null
    }

    /**
     * Delta logic: apply transcript update.
     * Returns true if applied.
     */
    fun applyUpdate(
        ic: InputConnection?,
        stable: String,
        unstable: String,
        isFinal: Boolean,
    ): Boolean {
        if (ic == null) return false
        var didBegin = false
        try {
            try { ic.beginBatchEdit(); didBegin = true } catch (_: Exception) {}

            // Prevent duplication: stable should be prefix monotonic.
            // If new stable is shorter than lastStable (hypothesis shrank: "Hello world" -> "Hello"), do NOT finishComposingText.
            // finishComposingText would commit old speculative "Hello world" — wrong. Must replace composing span directly.
            // If replacement cannot be trusted, we fallback to final-only mode via setComposingText rejection, never by committing speculative text.
            if (stable.length < lastStable.length && !stable.isEmpty()) {
                // No-op: allow direct replacement below. Do NOT call finishComposingText.
            }

            if (isFinal) {
                val finalText = when {
                    stable.isNotEmpty() && unstable.isNotEmpty() -> "$stable $unstable"
                    stable.isNotEmpty() -> stable
                    else -> unstable
                }.trim()
                if (finalText.isEmpty()) {
                    if (composingActive) {
                        // Empty final should discard composing, not commit it. Use setComposingText("") to clear.
                        try { ic.setComposingText("", 1) } catch (_: Exception) {}
                        try { ic.finishComposingText() } catch (_: Exception) {}
                        composingActive = false
                        Log.i("Composition", "finish empty composing discarded")
                    }
                    lastStable = ""; lastUnstable = ""; lastComposing = null
                    leadingSpaceNeeded = null
                    return true
                }
                // Once composing text is visible, getTextBeforeCursor() may return
                // the hypothesis itself. Preserve the cursor context captured before
                // the first partial so finalization cannot invent a leading space.
                val needsSpace = leadingSpaceNeeded ?: needsSpaceBeforeCursor(ic, finalText)
                val toCommit = if (needsSpace) " $finalText" else finalText
                val commitOk = if (composingActive) {
                    val ok = ic.commitText(toCommit, 1)
                    composingActive = false
                    ok
                } else {
                    ic.commitText(toCommit, 1)
                }
                Log.i("Composition", "commitText chars=${toCommit.length} needsSpace=$needsSpace composingActiveBefore=${composingActive} commitOk=$commitOk")
                if (!commitOk) {
                    // Fallback: try commit without composing state if editor rejected.
                    val fallbackOk = try { ic.commitText(toCommit, 1) } catch (_: Exception) { false }
                    Log.w("Composition", "commitText fallback fallbackOk=$fallbackOk")
                    if (!fallbackOk) return false
                }
                lastComposing = null
                lastStable = ""; lastUnstable = ""
                leadingSpaceNeeded = null
                return true
            }

            // Live update: set composing text = stable + unstable — dedupe to prevent flicker + spam
            val composing = when {
                stable.isNotEmpty() && unstable.isNotEmpty() -> "$stable $unstable"
                stable.isNotEmpty() -> stable
                else -> unstable
            }.trim()
            if (composing.isEmpty()) {
                // Empty partial means discard/clear speculative composition, not commit it.
                if (composingActive) {
                    try { ic.setComposingText("", 1) } catch (_: Exception) {}
                    try { ic.finishComposingText() } catch (_: Exception) {}
                    composingActive = false
                }
                lastStable = stable; lastUnstable = unstable; lastComposing = null
                leadingSpaceNeeded = null
                return true
            }
            // Some editors reject composing spans. Do not commit every partial as a fallback;
            // doing so duplicates the growing hypothesis. The final update will commit once.
            if (compositionRejected) return false
            // Silent-commit heuristic REMOVED per reliability invariant: transcript repetition (e.g. "very very good")
            // must never be treated as editor incompatibility. Intentional repetition is orthogonal to transport reliability.
            // Fallback to FINAL_ONLY only when setComposingText() explicitly rejects the operation (returns false).
            // If detecting silent commits robustly is impossible from InputConnection APIs, prefer FINAL_ONLY over heuristic inspection.
            // See mission P0/P1 — first-word duplicate and exact repetition heuristics based solely on content are removed.
            // Dedupe: don't re-set same composing text nonstop (HelloHello spam)
            if (composing == lastComposing) return true
            // Punctuation-aware spacing: punctuation-only composing like "," should attach, handled via needsSpaceBeforeCursor.
            // For regular composing, cache cursor decision once per utterance to avoid hypothesis-invented space.
            if (leadingSpaceNeeded == null) {
                leadingSpaceNeeded = needsSpaceBeforeCursor(ic, composing)
            }
            val visibleComposing = if (leadingSpaceNeeded == true) " $composing" else composing
            val ok = ic.setComposingText(visibleComposing, 1)
            if (!ok) {
                compositionRejected = true
                lastStable = stable
                lastUnstable = unstable
                lastComposing = null
                return false
            } else {
                composingActive = true
            }
            lastStable = stable; lastUnstable = unstable; lastComposing = composing
            return true
        } catch (e: Exception) {
            Log.w("Composition", "applyUpdate failed", e)
            return false
        } finally {
            if (didBegin) try { ic.endBatchEdit() } catch (_: Exception) {}
        }
    }

    fun onCursorMoved() {
        // If cursor moved while composing, finalize to avoid insertion at wrong place
        // Caller should finish composing
    }

    /**
     * Discard speculative partial — must NOT commit it.
     * Uses setComposingText("",1) + finishComposingText to ensure Android does not convert composing to committed.
     * Required for FIELD_LOST, INPUT_RESTARTED, WINDOW_HIDDEN, PASSWORD, ERROR, SERVICE_DESTROYED.
     */
    fun discardPartial(ic: InputConnection?) {
        if (composingActive) {
            try { ic?.setComposingText("", 1) } catch (_: Exception) {}
            try { ic?.finishComposingText() } catch (_: Exception) {}
            composingActive = false
            Log.i("Composition", "discarded speculative composing without commit")
        }
        lastStable = ""; lastUnstable=""; lastComposing = null
        compositionRejected = false
        leadingSpaceNeeded = null
    }

    /**
     * Commit final text — replaces composing span atomically via commitText.
     * Only called for successful utterance finalization.
     */
    fun commitFinal(ic: InputConnection?, text: String): Boolean {
        if (ic == null) return false
        // Delegate to applyUpdate with isFinal=true for consistent spacing logic
        return applyUpdate(ic, text, "", true)
    }

    /** Legacy alias — prefer discardPartial for cancellations. */
    fun finishIfActive(ic: InputConnection?) {
        // For safety, treat as discard, not commit. Callers that need commit should use commitFinal.
        discardPartial(ic)
    }

    fun hasComposing(): Boolean = composingActive

    private fun needsSpaceBeforeCursor(ic: InputConnection, text: String): Boolean = try {
        val before = ic.getTextBeforeCursor(1, 0)?.toString()
        if (before == null || before.isEmpty() || before.endsWith(" ") || before.endsWith("\n") || text.startsWith(" ")) {
            false
        } else {
            // Punctuation-only final must attach to previous token without leading separator.
            // e.g., existing "Hallo" + final "." -> "Hallo." not "Hallo ."
            // At minimum classify . , ; : ? ! ) ] } as attaching.
            // Conservative with quotes (can be opening/closing).
            val first = text.trimStart().firstOrNull()
            if (first != null && isClosingPunctuation(first)) {
                false
            } else {
                true
            }
        }
    } catch (_: Exception) {
        false
    }

    private fun isClosingPunctuation(c: Char): Boolean =
        c == '.' || c == ',' || c == ';' || c == ':' || c == '?' || c == '!' || c == ')' || c == ']' || c == '}'
}
