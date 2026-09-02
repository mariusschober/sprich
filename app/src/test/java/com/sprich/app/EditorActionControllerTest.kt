package com.sprich.app

import android.view.inputmethod.InputConnection
import android.view.inputmethod.EditorInfo
import com.sprich.app.input.ime.EditorActionController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class EditorActionControllerTest {

    // Simple InputConnection fake for host testing — mirrors CompositionManagerTest FakeIC
    open class FakeIC(var text: StringBuilder = StringBuilder(), var selStart: Int = -1, var selEnd: Int = -1) : InputConnection {
        var beforeCursorResult: String? = "USE_TEXT" // null simulates unavailable; "USE_TEXT" means use text.takeLast(n)
        var deleteCalled = 0
        var deleteLengths = mutableListOf<Int>()
        var commitCalled = mutableListOf<String>()
        var returnFalseOnDelete = false
        var hasSelection = false
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            if (beforeCursorResult == null) return null
            if (beforeCursorResult == "USE_TEXT") return text.toString().takeLast(n)
            return beforeCursorResult!!.takeLast(n)
        }
        override fun getSelectedText(flags: Int): CharSequence? = if (hasSelection) "selected" else null
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deleteCalled++
            deleteLengths.add(beforeLength)
            if (returnFalseOnDelete) return false
            if (text.length >= beforeLength) text.delete(text.length - beforeLength, text.length)
            return true
        }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commitCalled.add(text.toString())
            this.text.append(text)
            return true
        }
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = ""
        override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int): android.view.inputmethod.ExtractedText? = null
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = deleteSurroundingText(beforeLength, afterLength)
        override fun setSelection(start: Int, end: Int): Boolean = true
        override fun performEditorAction(actionCode: Int): Boolean = false
        override fun performContextMenuAction(id: Int): Boolean = false
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun sendKeyEvent(event: android.view.KeyEvent?): Boolean = true
        override fun clearMetaKeyStates(states: Int): Boolean = true
        override fun reportFullscreenMode(enabled: Boolean): Boolean = true
        override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean = true
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = true
        override fun getHandler(): android.os.Handler? = null
        override fun closeConnection() {}
        override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean = false
        override fun commitCorrection(correctionInfo: android.view.inputmethod.CorrectionInfo?): Boolean = false
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true
        override fun setComposingRegion(start: Int, end: Int): Boolean = true
        override fun finishComposingText(): Boolean = true
        override fun getCursorCapsMode(reqModes: Int): Int = 0
        override fun commitContent(inputContentInfo: android.view.inputmethod.InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean = false
    }

    @Test
    fun deletePreviousWord_zeroMutationWhenCannotRead() {
        val c = EditorActionController()
        val ic = FakeIC(StringBuilder("Hello world"))
        ic.beforeCursorResult = null
        // Simulate unavailable by returning null? Our Fake returns text, but we can set to null via flag and make getTextBeforeCursor return null
        // Override to simulate unavailable: set beforeCursorResult to null and make getTextBeforeCursor return null via custom
        val ic2 = object : FakeIC(StringBuilder("Hello")) {
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? { return null }
        }
        val ok = c.deletePreviousWord(ic2, "field1", 1, false)
        assertFalse(ok)
        assertEquals(0, ic2.deleteCalled)
        assertEquals(0, c.historySize())
    }

    @Test
    fun deletePreviousWord_savesExactSpanWithWhitespace() {
        val c = EditorActionController()
        val ic = FakeIC(StringBuilder("Hello world"))
        ic.beforeCursorResult = "Hello world"
        val ok = c.deletePreviousWord(ic, "field1", 1, false)
        assertTrue(ok)
        assertEquals(1, ic.deleteCalled)
        // toDelete should be " world" (space + world)
        val rec = c.lastRecord()
        assertNotNull(rec)
        assertEquals(" world", rec!!.deletedSpan)
        assertEquals("field1", rec.fieldId)
        assertEquals(1L, rec.fieldGeneration)
    }

    @Test
    fun deletePreviousWord_ambiguousReturnsFalseNoHistory() {
        val c = EditorActionController()
        val ic = FakeIC(StringBuilder("Hello world"))
        ic.beforeCursorResult = "Hello world"
        ic.returnFalseOnDelete = true
        val ok = c.deletePreviousWord(ic, "field1", 1, false)
        assertFalse(ok)
        assertEquals(0, c.historySize())
        // Must not create undo record, return success false, no retry
        assertEquals(1, ic.deleteCalled) // one attempt only
    }

    @Test
    fun undoLegalOnlySameFieldGeneration() {
        val c = EditorActionController()
        val ic = FakeIC(StringBuilder("Hello world"))
        ic.beforeCursorResult = "Hello world"
        assertTrue(c.deletePreviousWord(ic, "field1", 1, false))
        assertEquals(1, c.historySize())
        // Undo on same field
        val ic2 = FakeIC(StringBuilder("Hello"))
        assertTrue(c.undoDeletion(ic2, "field1", 1, false))
        assertEquals(0, c.historySize())
        assertEquals(" world", ic2.commitCalled.first())
    }

    @Test
    fun undoBlockedDifferentField() {
        val c = EditorActionController()
        val ic = FakeIC(StringBuilder("Hello world"))
        ic.beforeCursorResult = "Hello world"
        assertTrue(c.deletePreviousWord(ic, "fieldA", 1, false))
        val ic2 = FakeIC(StringBuilder("Hello"))
        // Different field → zero mutation
        assertFalse(c.undoDeletion(ic2, "fieldB", 1, false))
        assertEquals(0, ic2.commitCalled.size)
        assertEquals(1, c.historySize())
    }

    @Test
    fun undoBlockedPasswordField() {
        val c = EditorActionController()
        val ic = FakeIC(StringBuilder("Hello world"))
        ic.beforeCursorResult = "Hello world"
        assertTrue(c.deletePreviousWord(ic, "field1", 1, false))
        val ic2 = FakeIC(StringBuilder("Hello"))
        assertFalse(c.undoDeletion(ic2, "field1", 1, true))
        assertEquals(0, ic2.commitCalled.size)
    }

    @Test
    fun spokenDeleteThatRequiresExactProof() {
        val c = EditorActionController()
        c.recordSprichInsertion("field1", 1, "hello world")
        val ic = FakeIC(StringBuilder("prefix hello world"))
        ic.beforeCursorResult = "prefix hello world"
        assertTrue(c.deleteLastSprichInsertion(ic, "field1", 1, false))
        assertEquals(1, ic.deleteCalled)
        // Second delete should fail (consumed)
        assertFalse(c.deleteLastSprichInsertion(ic, "field1", 1, false))
    }

    @Test
    fun spokenDeleteThatBlockedWhenContextNotMatching() {
        val c = EditorActionController()
        c.recordSprichInsertion("field1", 1, "hello world")
        val ic = FakeIC(StringBuilder("prefix hello"))
        ic.beforeCursorResult = "prefix hello" // does not end with exact insertion
        assertFalse(c.deleteLastSprichInsertion(ic, "field1", 1, false))
        assertEquals(0, ic.deleteCalled)
    }

    @Test
    fun spokenDeleteThatBlockedPasswordField() {
        val c = EditorActionController()
        c.recordSprichInsertion("field1", 1, "hello world")
        val ic = FakeIC(StringBuilder("hello world"))
        ic.beforeCursorResult = "hello world"
        assertFalse(c.deleteLastSprichInsertion(ic, "field1", 1, true))
    }

    @Test
    fun normalFieldDeleteThenFocusPasswordUndoZeroMutation() {
        val c = EditorActionController()
        val icNormal = FakeIC(StringBuilder("Hello world test"))
        icNormal.beforeCursorResult = "Hello world test"
        assertTrue(c.deletePreviousWord(icNormal, "fieldA", 1, false))
        assertEquals(1, c.historySize())
        // Focus password B
        c.onPasswordFieldFocused() // simulating password field focus clears history
        val icPass = FakeIC(StringBuilder("password field"))
        assertFalse(c.undoDeletion(icPass, "fieldB", 2, true))
        assertEquals(0, icPass.commitCalled.size)
    }

    @Test
    fun passwordSwipeLeftZeroDelete() {
        val c = EditorActionController()
        val ic = FakeIC(StringBuilder("secret"))
        ic.beforeCursorResult = "secret"
        val ok = c.deletePreviousWord(ic, "fieldPass", 5, true)
        assertFalse(ok)
        assertEquals(0, ic.deleteCalled)
    }

    @Test
    fun passwordSpokenDeleteThatZeroDelete() {
        val c = EditorActionController()
        c.recordSprichInsertion("fieldPass", 5, "hello")
        val ic = FakeIC(StringBuilder("hello"))
        ic.beforeCursorResult = "hello"
        assertFalse(c.deleteLastSprichInsertion(ic, "fieldPass", 5, true))
    }

    @Test
    fun deleteDoesNotGuessWhenBeforeEmpty() {
        val c = EditorActionController()
        val ic = FakeIC(StringBuilder(""))
        ic.beforeCursorResult = ""
        assertFalse(c.deletePreviousWord(ic, "field1", 1, false))
        assertEquals(0, ic.deleteCalled)
    }
}
