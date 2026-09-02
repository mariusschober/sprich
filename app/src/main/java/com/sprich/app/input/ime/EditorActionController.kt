package com.sprich.app.input.ime

import android.util.Log
import android.view.inputmethod.InputConnection

/**
 * Single narrow owner for delete/undo editor actions.
 * Owns fieldId/generation, exact removed text, safe cursor/context verification, bounded history, password policy.
 *
 * Invariants:
 * - If Sprich cannot safely read previous word/span (getTextBeforeCursor unavailable) → zero mutation (no 1-char fallback guess)
 * - Saves exact deleted span including whitespace actually removed
 * - Undo legal only when same fieldId/generation, cursor/context remains compatible, exact span known
 */
class EditorActionController {

    data class DeletionRecord(
        val fieldId: String?,
        val fieldGeneration: Long,
        val deletedSpan: String, // exact span removed, including leading whitespace
        val cursorAfter: Int = -1,
    )

    private val history = ArrayDeque<DeletionRecord>(10)
    private var lastSprichInsertion: String? = null
    private var lastInsertionFieldId: String? = null
    private var lastInsertionGeneration: Long = -1
    private var lastInsertionCommitted: Boolean = false

    fun clearHistory() {
        history.clear()
    }

    fun clearSprichInsertion() {
        lastSprichInsertion = null
        lastInsertionFieldId = null
        lastInsertionGeneration = -1
        lastInsertionCommitted = false
    }

    fun onPasswordFieldFocused() {
        clearHistory()
        clearSprichInsertion()
    }

    fun recordSprichInsertion(fieldId: String?, fieldGeneration: Long, text: String) {
        if (text.isBlank()) return
        lastSprichInsertion = text
        lastInsertionFieldId = fieldId
        lastInsertionGeneration = fieldGeneration
        lastInsertionCommitted = true
    }

    /**
     * Delete previous word — single owner.
     * Returns true if mutation occurred, false if zero mutation (including password, no IC, cannot read safely, selection, ambiguous)
     */
    fun deletePreviousWord(
        ic: InputConnection?,
        currentFieldId: String?,
        currentFieldGeneration: Long,
        isPasswordField: Boolean,
    ): Boolean {
        if (isPasswordField) {
            try { Log.i("EditorAction", "deletePreviousWord blocked: password field") } catch (_: Exception) {}
            return false
        }
        if (ic == null) return false
        if (currentFieldId == null) return false
        // Check selection — if selection exists, deletion is ambiguous (would delete selection vs word)
        try {
            val selected = try { ic.getSelectedText(0)?.toString() } catch (_: Exception) { null }
            if (!selected.isNullOrEmpty()) {
                try { Log.w("EditorAction", "deletePreviousWord blocked: selection present sel=$selected") } catch (_: Exception) {}
                return false
            }
        } catch (_: Exception) {}

        val before: String? = try { ic.getTextBeforeCursor(160, 0)?.toString() } catch (_: Exception) { null }
        if (before == null) {
            // Spec: if cannot safely read previous word/span → zero mutation, do not guess delete 1 char
            try { Log.w("EditorAction", "deletePreviousWord zero mutation: getTextBeforeCursor unavailable (null)") } catch (_: Exception) {}
            return false
        }
        if (before.isEmpty()) {
            try { Log.i("EditorAction", "deletePreviousWord zero mutation: before empty") } catch (_: Exception) {}
            return false
        }
        val trimmed = before.trimEnd()
        if (trimmed.isEmpty()) {
            // Only whitespace before cursor — delete whitespace span exactly
            val toDelete = before.length
            // Ambiguity check: if before contains only spaces but we cannot verify cursor, still bounded
            val ok = try { ic.deleteSurroundingText(toDelete, 0) } catch (_: Exception) { false }
            if (!ok) {
                try { Log.w("EditorAction", "deletePreviousWord whitespace delete returned false — ambiguous, not recording undo") } catch (_: Exception) {}
                return false
            }
            // Save exact deleted span (whitespace)
            val record = DeletionRecord(currentFieldId, currentFieldGeneration, before)
            addToHistory(record)
            try { Log.i("EditorAction", "deletePreviousWord whitespace deleted=$toDelete") } catch (_: Exception) {}
            return true
        }
        // Find previous word boundary
        var i = trimmed.length - 1
        while (i >= 0 && !trimmed[i].isWhitespace()) i--
        while (i >= 0 && trimmed[i].isWhitespace()) i--
        val keepLen = i + 1
        val toDelete = (before.length - keepLen).coerceIn(1, before.length)
        val deletedSpan = before.substring(keepLen) // exact span including leading space
        if (deletedSpan.isEmpty()) {
            try { Log.w("EditorAction", "deletePreviousWord zero mutation: deletedSpan empty") } catch (_: Exception) {}
            return false
        }
        val ok = try { ic.deleteSurroundingText(toDelete, 0) } catch (_: Exception) { false }
        if (!ok) {
            try { Log.w("EditorAction", "deleteSurroundingText returned false — ambiguous, zero history, no retry") } catch (_: Exception) {}
            return false
        }
        val record = DeletionRecord(currentFieldId, currentFieldGeneration, deletedSpan)
        addToHistory(record)
        try { Log.i("EditorAction", "deletePreviousWord deletedSpanLen=${deletedSpan.length} toDelete=$toDelete") } catch (_: Exception) {}
        // Invalidate sprich insertion tracking on manual delete (cursor moved)
        lastSprichInsertion = null
        return true
    }

    fun undoDeletion(
        ic: InputConnection?,
        currentFieldId: String?,
        currentFieldGeneration: Long,
        isPasswordField: Boolean,
    ): Boolean {
        if (isPasswordField) {
            try { Log.i("EditorAction", "undoDeletion blocked: password field") } catch (_: Exception) {}
            return false
        }
        val record = history.lastOrNull() ?: return false
        // Legal only when same field/generation and exact span known
        if (record.fieldId != currentFieldId || record.fieldGeneration != currentFieldGeneration) {
            try { Log.w("EditorAction", "undo blocked field mismatch record=${record.fieldId}/${record.fieldGeneration} vs current=$currentFieldId/$currentFieldGeneration") } catch (_: Exception) {}
            return false
        }
        if (record.deletedSpan.isEmpty()) return false
        if (ic == null) return false
        // Cursor/context compatibility: verify we can still safely insert — check no selection
        try {
            val sel = try { ic.getSelectedText(0)?.toString() } catch (_: Exception) { null }
            if (!sel.isNullOrEmpty()) {
                try { Log.w("EditorAction", "undo blocked selection present") } catch (_: Exception) {}
                return false
            }
        } catch (_: Exception) {}
        val ok = try { ic.commitText(record.deletedSpan, 1) } catch (_: Exception) { false }
        if (ok) {
            history.removeLast()
            try { Log.i("EditorAction", "undoDeletion restored len=${record.deletedSpan.length}") } catch (_: Exception) {}
        } else {
            try { Log.w("EditorAction", "undo commitText returned false") } catch (_: Exception) {}
        }
        return ok
    }

    /**
     * Spoken "delete that" — delete most recent Sprich insertion only if safe proof holds:
     * same field/generation, cursor immediately after insertion, textBeforeCursor ends with exact insertion
     */
    fun deleteLastSprichInsertion(
        ic: InputConnection?,
        currentFieldId: String?,
        currentFieldGeneration: Long,
        isPasswordField: Boolean,
    ): Boolean {
        if (isPasswordField) {
            try { Log.i("EditorAction", "spoken deleteThat blocked: password field") } catch (_: Exception) {}
            return false
        }
        if (ic == null) return false
        val insertion = lastSprichInsertion ?: run {
            try { Log.i("EditorAction", "spoken deleteThat blocked: no recent insertion") } catch (_: Exception) {}
            return false
        }
        if (insertion.isEmpty()) return false
        if (lastInsertionFieldId != currentFieldId || lastInsertionGeneration != currentFieldGeneration) {
            try { Log.w("EditorAction", "spoken deleteThat blocked field mismatch last=$lastInsertionFieldId/$lastInsertionGeneration vs cur=$currentFieldId/$currentFieldGeneration") } catch (_: Exception) {}
            return false
        }
        if (!lastInsertionCommitted) return false
        // Verify cursor immediately after insertion
        val before = try { ic.getTextBeforeCursor(insertion.length + 32, 0)?.toString() } catch (_: Exception) { null } ?: return false
        if (!before.endsWith(insertion)) {
            try { Log.w("EditorAction", "spoken deleteThat blocked context proof failed beforeLen=${before.length} insertionLen=${insertion.length} beforeEnds='${before.takeLast(20)}'") } catch (_: Exception) {}
            return false
        }
        // Verify no selection
        try {
            val sel = try { ic.getSelectedText(0)?.toString() } catch (_: Exception) { null }
            if (!sel.isNullOrEmpty()) return false
        } catch (_: Exception) {}

        val toDelete = insertion.length
        val ok = try { ic.deleteSurroundingText(toDelete, 0) } catch (_: Exception) { false }
        if (!ok) {
            try { Log.w("EditorAction", "spoken deleteThat deleteSurroundingText ambiguous false") } catch (_: Exception) {}
            return false
        }
        // Save for undo with exact span
        val record = DeletionRecord(currentFieldId, currentFieldGeneration, insertion)
        addToHistory(record)
        // Consume insertion — can only delete once
        clearSprichInsertion()
        try { Log.i("EditorAction", "spoken deleteThat success len=$toDelete") } catch (_: Exception) {}
        return true
    }

    private fun addToHistory(record: DeletionRecord) {
        history.addLast(record)
        if (history.size > 10) history.removeFirst()
    }

    // For testing
    fun historySize(): Int = history.size
    fun lastRecord(): DeletionRecord? = history.lastOrNull()
    fun hasSprichInsertion(): Boolean = lastSprichInsertion != null
}
