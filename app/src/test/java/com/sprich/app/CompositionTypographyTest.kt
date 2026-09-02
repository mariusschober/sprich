package com.sprich.app

import android.view.inputmethod.InputConnection
import com.sprich.app.input.composition.CompositionManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompositionTypographyTest {

    private class FakeIC(var initial: String = "") : InputConnection {
        var committed = StringBuilder(initial)
        var composing: String? = null
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = (committed.toString() + (composing ?: "")).takeLast(n)
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getCursorCapsMode(reqModes: Int): Int = 0
        override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int): android.view.inputmethod.ExtractedText? {
            val et = android.view.inputmethod.ExtractedText()
            et.text = (committed.toString() + (composing ?: ""))
            et.startOffset = 0
            et.selectionStart = et.text.length
            et.selectionEnd = et.text.length
            return et
        }
        override fun deleteSurroundingText(b: Int, a: Int): Boolean { if (committed.length >= b) committed.delete(committed.length - b, committed.length); return true }
        override fun deleteSurroundingTextInCodePoints(b: Int, a: Int): Boolean = deleteSurroundingText(b,a)
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean { composing = text?.toString(); return true }
        override fun setComposingRegion(s: Int, e: Int): Boolean = true
        override fun finishComposingText(): Boolean { if (composing!=null) { committed.append(composing); composing=null }; return true}
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { composing = null; committed.append(text); return true }
        override fun commitCompletion(c: android.view.inputmethod.CompletionInfo?): Boolean = false
        override fun commitCorrection(c: android.view.inputmethod.CorrectionInfo?): Boolean = false
        override fun setSelection(s: Int, e: Int): Boolean = true
        override fun performEditorAction(a: Int): Boolean = false
        override fun performContextMenuAction(id: Int): Boolean = false
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun sendKeyEvent(e: android.view.KeyEvent?): Boolean = false
        override fun clearMetaKeyStates(s: Int): Boolean = false
        override fun reportFullscreenMode(e: Boolean): Boolean = false
        override fun performPrivateCommand(a: String?, d: android.os.Bundle?): Boolean = false
        override fun requestCursorUpdates(m: Int): Boolean = false
        override fun getHandler(): android.os.Handler? = null
        override fun closeConnection() {}
        override fun commitContent(i: android.view.inputmethod.InputContentInfo, f: Int, o: android.os.Bundle?): Boolean = false
    }

    @Test fun existingHalloPlusDotAttachesWithoutSpace() {
        val cm = CompositionManager()
        val ic = FakeIC("Hallo")
        // Simulate prior text "Hallo" (committed) and next utterance is only "."
        assertTrue(cm.applyUpdate(ic, ".", "", true))
        assertEquals("Hallo.", ic.committed.toString())
    }

    @Test fun existingHalloPlusCommaAttaches() {
        val cm = CompositionManager()
        val ic = FakeIC("Hallo")
        assertTrue(cm.applyUpdate(ic, ",", "", true))
        assertEquals("Hallo,", ic.committed.toString())
    }

    @Test fun existingHalloPlusQuestionAttaches() {
        val cm = CompositionManager()
        val ic = FakeIC("Hallo")
        assertTrue(cm.applyUpdate(ic, "?", "", true))
        assertEquals("Hallo?", ic.committed.toString())
    }

    @Test fun existingHalloPlusSemicolonAttaches() {
        val cm = CompositionManager()
        val ic = FakeIC("Hallo")
        assertTrue(cm.applyUpdate(ic, ";", "", true))
        assertEquals("Hallo;", ic.committed.toString())
    }

    @Test fun normalWordStillGetsSpace() {
        val cm = CompositionManager()
        val ic = FakeIC("Hallo")
        assertTrue(cm.applyUpdate(ic, "Welt", "", true))
        assertEquals("Hallo Welt", ic.committed.toString())
    }

    @Test fun stableShrinkDoesNotCommitSpeculative() {
        val cm = CompositionManager()
        val ic = FakeIC()
        // IME-local partials: composing stays null externally
        cm.applyUpdate(ic, "Hello world", "", false)
        assertNull(ic.composing)
        assertEquals(0, ic.committed.length)
        // Hypothesis shrinks to "Hello" — still IME-local
        cm.applyUpdate(ic, "Hello", "", false)
        assertNull(ic.composing)
        assertEquals(0, ic.committed.length)
        // Grows again
        cm.applyUpdate(ic, "Hello there", "", false)
        assertNull(ic.composing)
        assertEquals(0, ic.committed.length)
        // Final enters exactly once
        cm.applyUpdate(ic, "Hello there", "", true)
        assertEquals("Hello there", ic.committed.toString())
        assertFalse(ic.committed.toString().contains("Hello world"))
    }

    @Test fun emptyPartialDiscardsNotCommits() {
        val cm = CompositionManager()
        val ic = FakeIC("before ")
        cm.applyUpdate(ic, "temporary words", "", false)
        assertNull(ic.composing) // IME-local, no external composing
        // Empty partial should discard, not commit
        cm.applyUpdate(ic, "", "", false)
        assertNull(ic.composing)
        assertEquals("before ", ic.committed.toString())
        // Empty final also discards
        cm.applyUpdate(ic, "something", "", false)
        assertNull(ic.composing)
        cm.applyUpdate(ic, "", "", true)
        assertEquals("before ", ic.committed.toString())
        assertNull(ic.composing)
    }

    @Test fun repetitionDoesNotTriggerSilentCommitFallback() {
        val cm = CompositionManager()
        val ic = FakeIC()
        // IME-local policy: partials never trigger silent-commit fallback, intentional repetition preserved
        assertFalse(cm.applyUpdate(ic, "very very", "", false))
        assertNull(ic.composing)
        // Second partial with intentional repetition — still IME-local, no duplication
        assertFalse(cm.applyUpdate(ic, "very very good", "", false))
        assertNull(ic.composing)
        assertEquals(0, ic.committed.length)
        // Final must preserve intentional repetition exactly once
        assertTrue(cm.applyUpdate(ic, "very very good", "", true))
        assertEquals("very very good", ic.committed.toString())
    }

    @Test fun germanRepetitionNoFalseCompatibilityTrigger() {
        val cases = listOf("sehr sehr gut", "nein nein nein", "very very good", "no no no")
        for (c in cases) {
            val cm = CompositionManager()
            val ic = FakeIC()
            assertTrue(cm.applyUpdate(ic, c, "", true))
            assertEquals(c, ic.committed.toString())
        }
    }

    @Test fun compositionPreserves3_14AndExampleCom() {
        val cm = CompositionManager()
        val ic = FakeIC()
        assertTrue(cm.applyUpdate(ic, "3.14", "", true))
        assertEquals("3.14", ic.committed.toString())
        val ic2 = FakeIC()
        assertTrue(cm.applyUpdate(ic2, "example.com", "", true))
        assertEquals("example.com", ic2.committed.toString())
    }
}
