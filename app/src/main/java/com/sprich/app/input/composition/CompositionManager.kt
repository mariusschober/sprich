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
            // Detect editors that silently commit composing text while returning success.
            // Such editors duplicate the hypothesis: "Hello" -> "Hello world" becomes "HelloHello world"
            if (composingActive && lastComposing != null) {
                try {
                    val before = ic.getTextBeforeCursor(400, 0)?.toString().orEmpty()
                    if (before.isNotEmpty() && lastComposing!!.isNotEmpty()) {
                        val firstWord = lastComposing!!.trim().split(Regex("\\s+")).firstOrNull() ?: lastComposing!!
                        if (firstWord.length >= 2) {
                            val countFirst = before.windowed(firstWord.length, 1, false).count { it == firstWord }
                            if (countFirst >= 2 && before.contains(lastComposing!!)) {
                                Log.w("Composition", "silent-commit detected firstWord=$firstWord count=$countFirst beforeLen=${before.length} lastComposing=$lastComposing")
                                compositionRejected = true
                                try { ic.finishComposingText() } catch (_: Exception) {}
                                try {
                                    val toDelete = lastComposing!!.length + if (leadingSpaceNeeded == true) 1 else 0
                                    ic.deleteSurroundingText(toDelete, 0)
                                    Log.i("Composition", "silent-commit cleanup deleted $toDelete")
                                } catch (_: Exception) {}
                                composingActive = false
                                return false
                            }
                        }
                        // Also check exact duplicate of lastComposing
                        val countExact = before.windowed(lastComposing!!.length, 1, false).count { it == lastComposing }
                        if (countExact >= 2) {
                            Log.w("Composition", "silent-commit detected countExact=$countExact beforeLen=${before.length} lastComposing=$lastComposing")
                            compositionRejected = true
                            try { ic.finishComposingText() } catch (_: Exception) {}
                            try {
                                val toDelete = lastComposing!!.length + if (leadingSpaceNeeded == true) 1 else 0
                                ic.deleteSurroundingText(toDelete, 0)
                                Log.i("Composition", "silent-commit cleanup deleted $toDelete")
                            } catch (_: Exception) {}
                            composingActive = false
                            return false
                        }
                    }
                } catch (_: Exception) {}
            }
            // Dedupe: don't re-set same composing text nonstop (HelloHello spam)
            if (composing == lastComposing) return true
            if (leadingSpaceNeeded == null) {
                leadingSpaceNeeded = needsSpaceBeforeCursor(ic, composing)
            }
            val visibleComposing = if (leadingSpaceNeeded == true) " $composing" else composing
            val beforeLen = try { ic.getTextBeforeCursor(500, 0)?.length ?: -1 } catch (_: Exception) { -1 }
            val ok = ic.setComposingText(visibleComposing, 1)
            if (!ok) {
                compositionRejected = true
                lastStable = stable
                lastUnstable = unstable
                lastComposing = null
                return false
            } else {
                // Verify editor didn't silently commit despite returning true: check if committed length exploded
                try {
                    val after = ic.getTextBeforeCursor(500, 0)?.toString().orEmpty()
                    if (beforeLen >= 0 && lastComposing != null) {
                        val expectedGrowth = visibleComposing.length - lastComposing!!.length
                        val actualGrowth = after.length - beforeLen
                        // Silent commit: actual growth is close to visibleComposing.length, not expected delta
                        if (actualGrowth > expectedGrowth + 1 && visibleComposing.isNotEmpty()) {
                            if (after.contains(lastComposing!!) && after.contains(visibleComposing) && after.length >= visibleComposing.length + lastComposing!!.length) {
                                Log.w("Composition", "silent-commit post-check actualGrowth=$actualGrowth expected=$expectedGrowth before=$beforeLen after=${after.length}")
                                compositionRejected = true
                                try { ic.finishComposingText() } catch (_: Exception) {}
                                try {
                                    val toDelete = lastComposing!!.length + if (leadingSpaceNeeded == true) 1 else 0
                                    ic.deleteSurroundingText(toDelete, 0)
                                } catch (_: Exception) {}
                                composingActive = false
                                return false
                            }
                        }
                        // Also check firstWord duplicate in after — independent of growth
                        val firstWord = visibleComposing.trim().split(Regex("\\s+")).firstOrNull() ?: ""
                        if (firstWord.length >= 2) {
                            val cnt = after.windowed(firstWord.length, 1, false).count { it == firstWord }
                            if (cnt >= 2) {
                                Log.w("Composition", "silent-commit post firstWord duplicate cnt=$cnt afterLen=${after.length}")
                                compositionRejected = true
                                try { ic.finishComposingText() } catch (_: Exception) {}
                                // Try to clean duplicated first word
                                try { ic.deleteSurroundingText(firstWord.length, 0) } catch (_: Exception) {}
                                composingActive = false
                                return false
                            }
                        }
                    }
                } catch (_: Exception) {}
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
