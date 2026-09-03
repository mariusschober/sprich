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
        assertTrue(c.deletePreviousUnit(e.ic, "field", 1, false))
        assertEquals("Hello   world ", e.text())
        assertEquals(14, c.lastRecord()!!.cursorAfter)
        assertTrue(c.undoDeletion(e.ic, "field", 1, false))
        assertEquals("Hello   world  ", e.text())
    }
    @Test fun manualCursorMoveRejectsUndoEvenInIdenticalText() {
        val e = Editor("Hello world Hello world")
        val c = EditorActionController()
        assertTrue(c.deletePreviousUnit(e.ic, "field", 1, false))
        e.view.setSelection(5)
        assertFalse(c.undoDeletion(e.ic, "field", 1, false))
        assertEquals("Hello world Hello ", e.text())
    }
    @Test fun changedContextAtSameCursorRejectsUndo() {
        val e = Editor("Hello world")
        val c = EditorActionController()
        assertTrue(c.deletePreviousUnit(e.ic, "field", 1, false))
        e.view.setText("Other "); e.view.setSelection(6)
        assertFalse(c.undoDeletion(e.ic, "field", 1, false))
    }
    @Test fun fieldRestartRejectsUndo() {
        val e = Editor("Hello world"); val c = EditorActionController()
        assertTrue(c.deletePreviousUnit(e.ic, "field", 1, false))
        assertFalse(c.undoDeletion(e.ic, "field", 2, false))
        assertFalse(c.undoDeletion(e.ic, "other", 1, false))
    }
    @Test fun selectionAndPasswordDoNotMutate() {
        val e = Editor("Hello world"); val c = EditorActionController()
        e.view.setSelection(1, 4)
        assertFalse(c.deletePreviousUnit(e.ic, "field", 1, false))
        e.view.setSelection(11)
        assertFalse(c.deletePreviousUnit(e.ic, "field", 1, true))
        assertEquals("Hello world", e.text())
    }
    @Test fun unavailableSelectionAuthorityDoesNotGuess() {
        val e = Editor("Hello world"); val c = EditorActionController()
        val unavailable = object : InputConnectionWrapper(e.ic, false) {
            override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): android.view.inputmethod.SurroundingText? = null
            override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int): android.view.inputmethod.ExtractedText? = null
        }
        assertFalse(c.deletePreviousUnit(unavailable, "field", 1, false))
        assertEquals("Hello world", e.text())
    }
    @Test fun truncatedLongWordIsNotPartiallyDeleted() {
        val e = Editor("x".repeat(1500)); val c = EditorActionController()
        assertFalse(c.deletePreviousUnit(e.ic, "field", 1, false))
        assertEquals(1500, e.text().length)
    }
    @Test fun emojiAndCombiningSequencesRemainWhole() {
        for (word in listOf("👩🏽‍💻", "🇩🇪", "cafe\u0301", "😀")) {
            val e = Editor("Hello $word"); val c = EditorActionController()
            assertTrue(word, c.deletePreviousUnit(e.ic, "field", 1, false))
            assertEquals("Hello ", e.text())
            assertTrue(c.undoDeletion(e.ic, "field", 1, false))
            assertEquals("Hello $word", e.text())
        }
    }
    @Test fun cursorInsideUnicodeClusterDoesNotDelete() {
        for ((text, cursor) in listOf("Hello 😀" to 7, "Hello e\u0301" to 7, "Hello 👩‍💻" to 8)) {
            val e = Editor(text); val c = EditorActionController(); e.view.setSelection(cursor)
            assertFalse(text, c.deletePreviousUnit(e.ic, "field", 1, false))
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
        assertTrue(c.deletePreviousUnit(e.ic, "field", 1, false))
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

    @Test fun shortSwipeDeletesExactlyOneWordOrGrapheme() {
        val cases = listOf(
            "Hello world" to "Hello ",
            "Hello world " to "Hello ",
            "Hello world  " to "Hello world ",
            "Hello." to "Hello",
            "Hello. " to "Hello.",
            "Hello, " to "Hello,",
            "Hi 👩🏽‍💻" to "Hi ",
            "Hi 🇩🇪" to "Hi ",
            "cafe\u0301" to "",
            "don't " to "",
        )
        for ((before, after) in cases) {
            val e = Editor(before); val c = EditorActionController()
            assertTrue(before, c.deletePreviousUnit(e.ic, "field", 1, false))
            assertEquals(before, after, e.text())
        }
    }

    @Test fun sentenceDeletionKeepsThePreviousPhraseAndHandlesLines() {
        for ((before, after) in listOf(
            "First sentence. Second sentence. " to "First sentence. ",
            "First sentence. An unfinished thought" to "First sentence. ",
            "First line\nSecond line" to "First line\n",
            "The value is 2.5. Next test. " to "The value is 2.5. ",
        )) {
            val e = Editor(before); val c = EditorActionController()
            assertTrue(before, c.deletePreviousSentence(e.ic, "field", 1, false))
            assertEquals(before, after, e.text())
        }
    }

    @Test fun longSwipePrefersTheExactLastSprichInsertion() {
        val e = Editor("One sentence. A dictated phrase without punctuation")
        val c = EditorActionController()
        c.recordSprichInsertion("field", 1, " A dictated phrase without punctuation", e.ic)
        val gesture = c.beginDeletion(e.ic, "field", 1, false)!!
        assertTrue(c.deleteStep(gesture, EditorActionController.Unit.PHRASE, e.ic, "field", 1, false))
        assertEquals("One sentence.", e.text())
    }

    @Test fun heldSentenceDeletionNeedsExactFreshAuthorityForEveryStep() {
        val e = Editor("One. Two. Three."); val c = EditorActionController()
        val gesture = c.beginDeletion(e.ic, "field", 1, false)!!
        assertTrue(c.deleteStep(gesture, EditorActionController.Unit.SENTENCE, e.ic, "field", 1, false))
        assertEquals("One. Two. ", e.text())
        assertTrue(c.deleteStep(gesture, EditorActionController.Unit.SENTENCE, e.ic, "field", 1, false))
        assertEquals("One. ", e.text())
        e.view.setText("Changed at the same cursor."); e.view.setSelection(5)
        assertFalse(c.deleteStep(gesture, EditorActionController.Unit.SENTENCE, e.ic, "field", 1, false))
        e.view.setText("One. "); e.view.setSelection(5)
        assertFalse(c.deleteStep(gesture, EditorActionController.Unit.SENTENCE, e.ic, "field", 1, false))
        assertEquals("One. ", e.text())
    }

    @Test fun appliedButFalseHeldDeletionCannotRepeat() {
        val e = Editor("One. Two."); val c = EditorActionController()
        val gesture = c.beginDeletion(e.ic, "field", 1, false)!!
        var calls = 0
        val ambiguous = object : InputConnectionWrapper(e.ic, false) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                calls++; super.deleteSurroundingText(beforeLength, afterLength); return false
            }
        }
        assertFalse(c.deleteStep(gesture, EditorActionController.Unit.SENTENCE, ambiguous, "field", 1, false))
        assertFalse(c.deleteStep(gesture, EditorActionController.Unit.SENTENCE, ambiguous, "field", 1, false))
        assertEquals(1, calls)
        assertEquals("One. ", e.text())
    }

    @Test fun truncatedSentenceAndWrongFieldCannotDelete() {
        val e = Editor("A".repeat(1500)); val c = EditorActionController()
        assertFalse(c.deletePreviousSentence(e.ic, "field", 1, false))
        val normal = Editor("One. Two.")
        val gesture = c.beginDeletion(normal.ic, "field", 1, false)!!
        assertFalse(c.deleteStep(gesture, EditorActionController.Unit.SENTENCE, normal.ic, "other", 1, false))
        assertEquals("One. Two.", normal.text())
    }
}
