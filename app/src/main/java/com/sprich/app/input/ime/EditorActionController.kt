package com.sprich.app.input.ime

import android.icu.text.BreakIterator
import android.view.inputmethod.InputConnection
import java.util.Locale

/** One mutation attempt per action. Held repeats require fresh, exact editor authority each time. */
class EditorActionController {
    enum class Unit { WORD_OR_SYMBOL, PHRASE, SENTENCE }
    class DeletionGesture internal constructor(val fieldId: String, val generation: Long, internal var anchor: EditorSnapshot?) {
        fun cancel() { anchor = null }
    }
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

    fun beginDeletion(ic: InputConnection?, fieldId: String?, generation: Long, isPasswordField: Boolean): DeletionGesture? {
        if (isPasswordField || ic == null || fieldId == null) return null
        val anchor = EditorSnapshot.read(ic) ?: return null
        if (!anchor.collapsed || anchor.before.isEmpty() || !cursorBoundary(anchor)) return null
        return DeletionGesture(fieldId, generation, anchor)
    }

    fun deletePreviousUnit(ic: InputConnection?, fieldId: String?, generation: Long, isPasswordField: Boolean): Boolean {
        val gesture = beginDeletion(ic, fieldId, generation, isPasswordField) ?: return false
        return deleteStep(gesture, Unit.WORD_OR_SYMBOL, ic, fieldId, generation, isPasswordField)
    }

    fun deletePreviousSentence(ic: InputConnection?, fieldId: String?, generation: Long, isPasswordField: Boolean): Boolean {
        val gesture = beginDeletion(ic, fieldId, generation, isPasswordField) ?: return false
        return deleteStep(gesture, Unit.SENTENCE, ic, fieldId, generation, isPasswordField)
    }

    fun deleteStep(gesture: DeletionGesture, unit: Unit, ic: InputConnection?, fieldId: String?, generation: Long, isPasswordField: Boolean): Boolean {
        val anchor = gesture.anchor ?: return false
        // Consume first. An ambiguous return must not authorize another step of the same hold.
        gesture.cancel()
        if (isPasswordField || ic == null || fieldId != gesture.fieldId || generation != gesture.generation || EditorSnapshot.read(ic) != anchor) return false
        val keep = when (unit) {
            Unit.WORD_OR_SYMBOL -> unitStart(anchor.before)
            Unit.PHRASE -> phraseStart(gesture, anchor)
            Unit.SENTENCE -> sentenceStart(anchor)
        }
        if (keep !in 0 until anchor.before.length || (keep == 0 && anchor.selectionStart > anchor.before.length)) return false
        val span = anchor.before.substring(keep)
        if (!cursorBoundary(anchor) || !completeUnicode(span) || !boundary(anchor.before + anchor.after, keep)) return false
        if (!delete(ic, gesture.fieldId, generation, anchor, span)) return false
        gesture.anchor = history.lastOrNull()?.anchor
        return gesture.anchor != null
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
        val verified = after != null && after.collapsed && after.selectionStart == anchor.selectionStart - span.length &&
            after.before.endsWith(anchor.before.dropLast(span.length)) && after.after == anchor.after
        if (verified) {
            history.addLast(DeletionRecord(fieldId, generation, span, after!!))
            if (history.size > 10) history.removeFirst()
        } else clearHistory()
        return verified
    }

    private fun unitStart(before: String): Int {
        val characters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(before) }
        val last = characters.preceding(before.length)
        if (last < 0) return -1
        fun isWord(start: Int, end: Int): Boolean = before.substring(start, end).codePoints().anyMatch { Character.isLetterOrDigit(it) }
        var wordEnd = before.length
        if (before.substring(last) == " " && last > 0) {
            val previous = characters.preceding(last)
            if (previous < 0 || !isWord(previous, last)) return last
            wordEnd = last
        } else if (!isWord(last, before.length)) return last
        return BreakIterator.getWordInstance(Locale.ROOT).run { setText(before); preceding(wordEnd) }
    }

    private fun sentenceStart(anchor: EditorSnapshot): Int {
        val before = anchor.before
        var end = before.length
        while (end > 0 && before[end - 1].isWhitespace()) end--
        if (end == 0) return 0
        val sentence = BreakIterator.getSentenceInstance(Locale.ROOT).run { setText(before + anchor.after); preceding(end) }
        // A line is also a phrase boundary, even when it has no terminal punctuation.
        val line = before.lastIndexOf('\n', end - 1) + 1
        return maxOf(sentence, line)
    }

    private fun phraseStart(gesture: DeletionGesture, anchor: EditorSnapshot): Int {
        val owned = insertion
        return if (owned != null && owned.fieldId == gesture.fieldId && owned.generation == gesture.generation &&
            owned.anchor == anchor && anchor.before.endsWith(owned.text) && boundary(anchor.before + anchor.after, anchor.before.length - owned.text.length)) {
            anchor.before.length - owned.text.length
        } else sentenceStart(anchor)
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
