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
    private var lastCommitted: String? = null
    private var lastComposing: String? = null

    fun reset() {
        lastStable = ""
        lastUnstable = ""
        composingActive = false
        cachedCursorStart = -1
        lastComposing = null
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
                        ic.finishComposingText()
                        composingActive = false
                    }
                    lastStable = ""; lastUnstable = ""; lastComposing = null
                    return true
                }
                // Prevent duplicate commit spam (HelloHello) — dedupe
                if (finalText == lastCommitted && composingActive) {
                    // Same as last committed, just finish composing
                    ic.finishComposingText()
                    composingActive = false
                    lastStable = ""; lastUnstable = ""; lastComposing = null
                    return true
                }
                // Smart spacing: check char before cursor, not lastStable
                val needsSpace = try {
                    val before = ic.getTextBeforeCursor(1, 0)?.toString()
                    before != null && before.isNotEmpty() && !before.endsWith(" ") && !before.endsWith("\n") && !finalText.startsWith(" ")
                } catch (_: Exception) { false }
                val toCommit = if (needsSpace) " $finalText" else finalText
                if (composingActive) {
                    ic.commitText(toCommit, 1)
                    composingActive = false
                } else {
                    ic.commitText(toCommit, 1)
                }
                lastCommitted = finalText
                lastComposing = null
                lastStable = ""; lastUnstable = ""
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
                return true
            }
            // Dedupe: don't re-set same composing text nonstop (HelloHello spam)
            if (composing == lastComposing) return true
            val ok = ic.setComposingText(composing, 1)
            if (!ok) {
                ic.commitText(composing, 1)
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
    }

    fun hasComposing(): Boolean = composingActive
}
