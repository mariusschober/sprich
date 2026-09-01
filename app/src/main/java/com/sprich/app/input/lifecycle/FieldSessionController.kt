package com.sprich.app.input.lifecycle

import android.view.inputmethod.InputConnection
import com.sprich.app.input.composition.CompositionManager
import java.util.Collections

/**
 * Isolates IME/focus correctness:
 * - Owns sessionId per focus/capture cycle
 * - Uses composing spans for partials and one final commit
 * - Preserves selection/spacing, handles password/numeric rejection
 * - Rapid focus switching must never cross-insert (late callbacks ignored via sessionId)
 */
class FieldSessionController(
    private val session: DictationSession,
    private val composition: CompositionManager,
) {
    @Volatile private var currentSessionId: Long = 0L
    @Volatile private var currentFieldId: String? = null
    @Volatile private var startingSelectionStart: Int = -1
    @Volatile private var startingSelectionEnd: Int = -1
    private val finalizedUtteranceIds = Collections.synchronizedSet(mutableSetOf<Long>())
    @Volatile private var utteranceCounter: Long = 0L

    fun onFieldFocused(fieldId: String, selectionStart: Int, selectionEnd: Int): Long {
        val id = session.start()
        currentSessionId = id
        currentFieldId = fieldId
        startingSelectionStart = selectionStart
        startingSelectionEnd = selectionEnd
        finalizedUtteranceIds.clear()
        utteranceCounter = 0L
        composition.reset()
        return id
    }

    fun onFieldLost(fieldId: String) {
        if (fieldId == currentFieldId) {
            try { composition.discardPartial(null) } catch (_: Exception) { composition.finishIfActive(null) }
            try { session.end() } catch (_: Exception) {}
            currentFieldId = null
            currentSessionId = 0L
            finalizedUtteranceIds.clear()
        }
    }

    fun applyPartial(sessionId: Long, ic: InputConnection?, stable: String, unstable: String): Boolean {
        if (sessionId != currentSessionId) return false // late callback ignored
        if (!session.isSessionValid(sessionId)) return false
        if (ic == null) return false
        return composition.applyUpdate(ic, stable, unstable, false)
    }

    sealed class CommitResult {
        object Committed : CommitResult()
        object EditorRejected : CommitResult()
        object AlreadyFinalized : CommitResult()
        object StaleSession : CommitResult()
        object WrongField : CommitResult()
        object NoInputConnection : CommitResult()
    }

    /**
     * Per-utterance commit — keeps field session alive for next utterance.
     * Exactly once per utteranceId within the same field session.
     * Transitions Finalizing -> Inserting -> Listening without invalidating field session.
     * Now returns typed result to avoid dangerous fallback that treated all `false` as editor rejection.
     */
    fun commitUtterance(sessionId: Long, utteranceId: Long, ic: InputConnection?, text: String): Boolean {
        return when (commitUtteranceTyped(sessionId, utteranceId, ic, text)) {
            is CommitResult.Committed -> true
            else -> false
        }
    }

    fun commitUtteranceTyped(sessionId: Long, utteranceId: Long, ic: InputConnection?, text: String): CommitResult {
        if (sessionId != currentSessionId) return CommitResult.StaleSession
        if (!session.isSessionValid(sessionId)) return CommitResult.StaleSession
        if (ic == null) return CommitResult.NoInputConnection
        if (currentFieldId == null) return CommitResult.WrongField
        // Exactly once per utteranceId — FieldSessionController's set is secondary; primary owner is SprichIME coordinator.
        // Keep this check for field-local safety, but SprichIME's finalizedUtterances is authoritative for cross-utterance dedup.
        val claimed = synchronized(finalizedUtteranceIds) {
            if (finalizedUtteranceIds.contains(utteranceId)) false
            else { finalizedUtteranceIds.add(utteranceId); true }
        }
        if (!claimed) return CommitResult.AlreadyFinalized
        session.onInserting()
        val ok = composition.applyUpdate(ic, text, "", true)
        if (ok) {
            // Return to Listening for next utterance within same field session
            session.onListeningAgain()
            return CommitResult.Committed
        } else {
            // Commit failed — editor rejected, not stale. Keep session alive.
            try { session.onListeningAgain() } catch (_: Exception) {}
            return CommitResult.EditorRejected
        }
    }

    /**
     * Legacy field-terminating commit — ends field session (used for explicit stop or field loss).
     * Prefer commitUtterance for per-utterance commits that keep listening.
     */
    fun commitFinal(sessionId: Long, ic: InputConnection?, text: String): Boolean {
        if (sessionId != currentSessionId) return false
        if (!session.isSessionValid(sessionId)) return false
        if (ic == null) return false
        session.onInserting()
        val ok = composition.applyUpdate(ic, text, "", true)
        session.end()
        currentFieldId = null
        currentSessionId = 0L
        finalizedUtteranceIds.clear()
        return ok
    }

    /** Generate next utteranceId within current field session (monotonic). */
    fun nextUtteranceId(): Long = synchronized(finalizedUtteranceIds) { ++utteranceCounter }

    fun isCurrentSession(id: Long): Boolean = id == currentSessionId

    fun currentSessionId(): Long = currentSessionId

    fun cancelActive() {
        try { composition.discardPartial(null) } catch (_: Exception) { composition.finishIfActive(null) }
        if (session.isActive) try { session.end() } catch (_: Exception) {}
        currentFieldId = null
        currentSessionId = 0L
        finalizedUtteranceIds.clear()
    }

    /** For testing: check if utteranceId already finalized. */
    fun isUtteranceFinalized(utteranceId: Long): Boolean = finalizedUtteranceIds.contains(utteranceId)
}
