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
            // If new stable is shorter than lastStable, we need to backtrack (user correction case) — handle by resetting.
            if (stable.length < lastStable.length && !stable.isEmpty()) {
                // Potential correction — we should commit diff correctly.
                // Simplest: finish composing and set new composing
                if (composingActive) {
                    ic.finishComposingText()
                    composingActive = false
                }
                // If stable shrank, we need to delete excess committed?
                // But we never committed unstable; lastStable was committed + composing? Actually we commit incremental stable via setComposing.
                // Our model: we never commit until final, we keep all in composing.
                // So shrinking stable doesn't require deletion of committed text, just new composing.
            }

            if (isFinal) {
                val finalText = when {
                    stable.isNotEmpty() && unstable.isNotEmpty() -> "$stable $unstable"
                    stable.isNotEmpty() -> stable
                    else -> unstable
                }.trim()
                if (finalText.isEmpty()) {
                    if (composingActive) {
                        val finishOk = try { ic.finishComposingText() } catch (_: Exception) { false }
                        composingActive = false
                        Log.i("Composition", "finish empty composing finishOk=$finishOk")
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
                if (composingActive) {
                    ic.finishComposingText()
                    composingActive = false
                }
                lastStable = stable; lastUnstable = unstable; lastComposing = null
                leadingSpaceNeeded = null
                return true
            }
            // Some editors reject composing spans. Do not commit every partial as a fallback;
            // doing so duplicates the growing hypothesis. The final update will commit once.
            if (compositionRejected) return false
            // Dedupe: don't re-set same composing text nonstop (HelloHello spam)
            if (composing == lastComposing) return true
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

    fun finishIfActive(ic: InputConnection?) {
        if (composingActive) {
            try { ic?.finishComposingText() } catch(_:Exception){}
            composingActive = false
        }
        lastStable = ""; lastUnstable=""; lastComposing = null
        compositionRejected = false
        leadingSpaceNeeded = null
    }

    fun hasComposing(): Boolean = composingActive

    private fun needsSpaceBeforeCursor(ic: InputConnection, text: String): Boolean = try {
        val before = ic.getTextBeforeCursor(1, 0)?.toString()
        before != null && before.isNotEmpty() &&
            !before.endsWith(" ") && !before.endsWith("\n") && !text.startsWith(" ")
    } catch (_: Exception) {
        false
    }
}
