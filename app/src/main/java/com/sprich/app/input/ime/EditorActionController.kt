package com.sprich.app.input.ime

import android.icu.text.BreakIterator
import android.view.inputmethod.InputConnection
import java.util.Locale

/** One mutation attempt per gesture/command. Unknown selection or context means no mutation. */
class EditorActionController {
    data class DeletionRecord(val fieldId: String, val fieldGeneration: Long, val deletedSpan: String, val anchor: EditorSnapshot) {
        val cursorAfter get() = anchor.selectionStart
    }
    private data class Insertion(val fieldId: String, val generation: Long, val text: String, val anchor: EditorSnapshot)
    private val history = ArrayDeque<DeletionRecord>()
    private var insertion: Insertion? = null

    fun clearHistory() = history.clear()
    fun clearSprichInsertion() { insertion = null }
    fun onPasswordFieldFocused() { clearHistory(); clearSprichInsertion() }

    fun recordSprichInsertion(fieldId: String?, fieldGeneration: Long, text: String, ic: InputConnection? = null) {
        insertion = null
        if (fieldId == null || text.isBlank() || text.length > EditorSnapshot.BEFORE) return
        val anchor = EditorSnapshot.read(ic) ?: return
        if (!anchor.collapsed || !anchor.before.endsWith(text)) return
        insertion = Insertion(fieldId, fieldGeneration, text, anchor)
    }

    fun deletePreviousWord(ic: InputConnection?, currentFieldId: String?, currentFieldGeneration: Long, isPasswordField: Boolean): Boolean {
        if (isPasswordField || ic == null || currentFieldId == null) return false
        val anchor = EditorSnapshot.read(ic) ?: return false
        if (!anchor.collapsed || anchor.before.isEmpty() || !cursorBoundary(anchor)) return false
        val before = anchor.before
        var keep = before.length
        while (keep > 0 && before[keep - 1].isWhitespace()) keep--
        while (keep > 0 && !before[keep - 1].isWhitespace()) keep--
        while (keep > 0 && before[keep - 1].isWhitespace()) keep--
        // A bounded read must not pretend its start is the start of an arbitrarily long word.
        if (keep == 0 && anchor.selectionStart > before.length) return false
        val span = before.substring(keep)
        if (!completeUnicode(span) || !boundary(before + anchor.after, keep)) return false
        return delete(ic, currentFieldId, currentFieldGeneration, anchor, span)
    }

    fun undoDeletion(ic: InputConnection?, currentFieldId: String?, currentFieldGeneration: Long, isPasswordField: Boolean): Boolean {
        if (isPasswordField || ic == null || currentFieldId == null) return false
        val record = history.lastOrNull() ?: return false
        if (record.fieldId != currentFieldId || record.fieldGeneration != currentFieldGeneration || EditorSnapshot.read(ic) != record.anchor) return false
        // Consume before the call: false/throw may follow an actual mutation.
        history.removeLast()
        clearSprichInsertion()
        val ok = try { ic.commitText(record.deletedSpan, 1) } catch (_: Exception) { false }
        if (!ok) clearHistory()
        return ok
    }

    fun deleteLastSprichInsertion(ic: InputConnection?, currentFieldId: String?, currentFieldGeneration: Long, isPasswordField: Boolean): Boolean {
        if (isPasswordField || ic == null || currentFieldId == null) return false
        val owned = insertion ?: return false
        if (owned.fieldId != currentFieldId || owned.generation != currentFieldGeneration) return false
        val anchor = EditorSnapshot.read(ic) ?: return false
        if (anchor != owned.anchor || !anchor.collapsed || !anchor.before.endsWith(owned.text) || !cursorBoundary(anchor)) return false
        if (!boundary(anchor.before + anchor.after, anchor.before.length - owned.text.length)) return false
        return delete(ic, currentFieldId, currentFieldGeneration, anchor, owned.text)
    }

    private fun delete(ic: InputConnection, fieldId: String, generation: Long, anchor: EditorSnapshot, span: String): Boolean {
        clearSprichInsertion()
        val ok = try { ic.deleteSurroundingText(span.length, 0) } catch (_: Exception) { false }
        if (!ok) { clearHistory(); return false }
        val after = EditorSnapshot.read(ic)
        // A success flag alone cannot establish undo authority (editors may clamp/filter edits).
        if (after != null && after.collapsed && after.selectionStart == anchor.selectionStart - span.length &&
            after.before.endsWith(anchor.before.dropLast(span.length)) && after.after == anchor.after) {
            history.addLast(DeletionRecord(fieldId, generation, span, after))
            if (history.size > 10) history.removeFirst()
        } else clearHistory()
        return true
    }

    private fun cursorBoundary(anchor: EditorSnapshot) = completeUnicode(anchor.before) && completeUnicode(anchor.after) && boundary(anchor.before + anchor.after, anchor.before.length)
    private fun boundary(text: String, offset: Int): Boolean = BreakIterator.getCharacterInstance(Locale.ROOT).run { setText(text); isBoundary(offset) }
    private fun completeUnicode(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (Character.isHighSurrogate(c)) { if (i >= text.length || !Character.isLowSurrogate(text[i++])) return false }
            else if (Character.isLowSurrogate(c)) return false
        }
        return true
    }
    fun historySize() = history.size
    fun lastRecord(): DeletionRecord? = history.lastOrNull()
    fun hasSprichInsertion() = insertion != null
}
