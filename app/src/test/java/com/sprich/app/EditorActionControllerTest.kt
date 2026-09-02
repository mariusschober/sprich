package com.sprich.app

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.input.ime.EditorActionController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorActionControllerTest {
    private class Editor(text: String) {
        val view = EditText(ApplicationProvider.getApplicationContext<Context>()).apply { setText(text); setSelection(text.length) }
        val ic = view.onCreateInputConnection(EditorInfo())!!
        fun text() = view.text.toString()
    }

    @Test fun deletionAndUndoPreserveExactWhitespaceAndCursor() {
        val e = Editor("Hello   world  ")
        val c = EditorActionController()
        assertTrue(c.deletePreviousWord(e.ic, "field", 1, false))
        assertEquals("Hello", e.text())
        assertEquals(5, c.lastRecord()!!.cursorAfter)
        assertTrue(c.undoDeletion(e.ic, "field", 1, false))
        assertEquals("Hello   world  ", e.text())
    }
    @Test fun manualCursorMoveRejectsUndoEvenInIdenticalText() {
        val e = Editor("Hello world Hello world")
        val c = EditorActionController()
        assertTrue(c.deletePreviousWord(e.ic, "field", 1, false))
        e.view.setSelection(5)
        assertFalse(c.undoDeletion(e.ic, "field", 1, false))
        assertEquals("Hello world Hello", e.text())
    }
    @Test fun changedContextAtSameCursorRejectsUndo() {
        val e = Editor("Hello world")
        val c = EditorActionController()
        assertTrue(c.deletePreviousWord(e.ic, "field", 1, false))
        e.view.setText("Other"); e.view.setSelection(5)
        assertFalse(c.undoDeletion(e.ic, "field", 1, false))
    }
    @Test fun fieldRestartRejectsUndo() {
        val e = Editor("Hello world"); val c = EditorActionController()
        assertTrue(c.deletePreviousWord(e.ic, "field", 1, false))
        assertFalse(c.undoDeletion(e.ic, "field", 2, false))
        assertFalse(c.undoDeletion(e.ic, "other", 1, false))
    }
    @Test fun selectionAndPasswordDoNotMutate() {
        val e = Editor("Hello world"); val c = EditorActionController()
        e.view.setSelection(1, 4)
        assertFalse(c.deletePreviousWord(e.ic, "field", 1, false))
        e.view.setSelection(11)
        assertFalse(c.deletePreviousWord(e.ic, "field", 1, true))
        assertEquals("Hello world", e.text())
    }
    @Test fun unavailableSelectionAuthorityDoesNotGuess() {
        val e = Editor("Hello world"); val c = EditorActionController()
        val unavailable = object : InputConnectionWrapper(e.ic, false) {
            override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): android.view.inputmethod.SurroundingText? = null
            override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int): android.view.inputmethod.ExtractedText? = null
        }
        assertFalse(c.deletePreviousWord(unavailable, "field", 1, false))
        assertEquals("Hello world", e.text())
    }
    @Test fun truncatedLongWordIsNotPartiallyDeleted() {
        val e = Editor("x".repeat(1500)); val c = EditorActionController()
        assertFalse(c.deletePreviousWord(e.ic, "field", 1, false))
        assertEquals(1500, e.text().length)
    }
    @Test fun emojiAndCombiningSequencesRemainWhole() {
        for (word in listOf("👩🏽‍💻", "🇩🇪", "cafe\u0301", "😀")) {
            val e = Editor("Hello $word"); val c = EditorActionController()
            assertTrue(word, c.deletePreviousWord(e.ic, "field", 1, false))
            assertEquals("Hello", e.text())
            assertTrue(c.undoDeletion(e.ic, "field", 1, false))
            assertEquals("Hello $word", e.text())
        }
    }
    @Test fun cursorInsideUnicodeClusterDoesNotDelete() {
        for ((text, cursor) in listOf("Hello 😀" to 7, "Hello e\u0301" to 7, "Hello 👩‍💻" to 8)) {
            val e = Editor(text); val c = EditorActionController(); e.view.setSelection(cursor)
            assertFalse(text, c.deletePreviousWord(e.ic, "field", 1, false))
            assertEquals(text, e.text())
        }
    }
    @Test fun spokenDeletionRequiresTheExactOwnedLocation() {
        val e = Editor("Hello Hello"); val c = EditorActionController()
        c.recordSprichInsertion("field", 1, " Hello", e.ic)
        e.view.setSelection(5)
        assertFalse(c.deleteLastSprichInsertion(e.ic, "field", 1, false))
        e.view.setSelection(11)
        assertTrue(c.deleteLastSprichInsertion(e.ic, "field", 1, false))
        assertFalse(c.deleteLastSprichInsertion(e.ic, "field", 1, false))
        assertEquals("Hello", e.text())
    }
    @Test fun appliedButFalseUndoIsConsumedAndNeverRepeated() {
        val e = Editor("Hello world"); val c = EditorActionController()
        assertTrue(c.deletePreviousWord(e.ic, "field", 1, false))
        var calls = 0
        val ambiguous = object : InputConnectionWrapper(e.ic, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { calls++; super.commitText(text, newCursorPosition); return false }
        }
        assertFalse(c.undoDeletion(ambiguous, "field", 1, false))
        assertFalse(c.undoDeletion(ambiguous, "field", 1, false))
        assertEquals(1, calls)
        assertEquals("Hello world", e.text())
    }
    @Test fun appliedButFalseSpokenDeletionIsConsumed() {
        val e = Editor("Hello world"); val c = EditorActionController()
        c.recordSprichInsertion("field", 1, " world", e.ic)
        var calls = 0
        val ambiguous = object : InputConnectionWrapper(e.ic, false) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean { calls++; super.deleteSurroundingText(beforeLength, afterLength); return false }
        }
        assertFalse(c.deleteLastSprichInsertion(ambiguous, "field", 1, false))
        assertFalse(c.deleteLastSprichInsertion(ambiguous, "field", 1, false))
        assertEquals(1, calls)
        assertEquals(0, c.historySize())
    }
}
