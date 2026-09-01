package com.sprich.app.input.lifecycle

import android.view.inputmethod.InputConnection
import com.sprich.app.input.composition.CompositionManager

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

    fun onFieldFocused(fieldId: String, selectionStart: Int, selectionEnd: Int): Long {
        val id = session.start()
        currentSessionId = id
        currentFieldId = fieldId
        startingSelectionStart = selectionStart
        startingSelectionEnd = selectionEnd
        composition.reset()
        return id
    }

    fun onFieldLost(fieldId: String) {
        if (fieldId == currentFieldId) {
            composition.finishIfActive(null)
            session.end()
            currentFieldId = null
            currentSessionId = 0L
        }
    }

    fun applyPartial(sessionId: Long, ic: InputConnection?, stable: String, unstable: String): Boolean {
        if (sessionId != currentSessionId) return false // late callback ignored
        if (!session.isSessionValid(sessionId)) return false
        if (ic == null) return false
        // Verify IC still belongs to current field by checking selection hasn't moved unexpectedly?
        // We trust caller has revalidated currentInputConnection; we also check session validity.
        return composition.applyUpdate(ic, stable, unstable, false)
    }

    fun commitFinal(sessionId: Long, ic: InputConnection?, text: String): Boolean {
        if (sessionId != currentSessionId) return false
        if (!session.isSessionValid(sessionId)) return false
        if (ic == null) return false
        // ImeWriter: single final commit, composing span replaced atomically
        session.onInserting()
        val ok = composition.applyUpdate(ic, text, "", true)
        session.end()
        currentFieldId = null
        currentSessionId = 0L
        return ok
    }

    fun isCurrentSession(id: Long): Boolean = id == currentSessionId

    fun cancelActive() {
        composition.finishIfActive(null)
        if (session.isActive) session.end()
        currentFieldId = null
        currentSessionId = 0L
    }
}
