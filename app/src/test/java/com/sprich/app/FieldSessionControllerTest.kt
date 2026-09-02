package com.sprich.app

import com.sprich.app.input.composition.CompositionManager
import com.sprich.app.input.lifecycle.DictationSession
import com.sprich.app.input.lifecycle.FieldSessionController
import com.sprich.app.input.lifecycle.SessionState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.view.inputmethod.InputConnection
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.ExtractedText

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FieldSessionControllerTest {

    private class FakeIC(var text: String = "") : InputConnection {
        var committed = StringBuilder(text)
        var composing: String? = null
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = (committed.toString() + (composing ?: "")).takeLast(n)
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getCursorCapsMode(reqModes: Int): Int = 0
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            val et = ExtractedText()
            et.text = (committed.toString() + (composing ?: ""))
            et.startOffset = 0
            et.selectionStart = et.text.length
            et.selectionEnd = et.text.length
            return et
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean { if (committed.length >= beforeLength) committed.delete(committed.length - beforeLength, committed.length); return true }
        override fun deleteSurroundingTextInCodePoints(b: Int, a: Int): Boolean = deleteSurroundingText(b,a)
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean { composing = text?.toString(); return true }
        override fun setComposingRegion(start: Int, end: Int): Boolean = true
        override fun finishComposingText(): Boolean { if (composing!=null) { committed.append(composing); composing=null }; return true}
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { composing = null; committed.append(text); return true }
        override fun commitCompletion(c: android.view.inputmethod.CompletionInfo?): Boolean = false
        override fun commitCorrection(c: android.view.inputmethod.CorrectionInfo?): Boolean = false
        override fun setSelection(start: Int, end: Int): Boolean = true
        override fun performEditorAction(actionCode: Int): Boolean = false
        override fun performContextMenuAction(id: Int): Boolean = false
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun sendKeyEvent(event: android.view.KeyEvent?): Boolean = false
        override fun clearMetaKeyStates(states: Int): Boolean = false
        override fun reportFullscreenMode(enabled: Boolean): Boolean = false
        override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean = false
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
        override fun getHandler(): android.os.Handler? = null
        override fun closeConnection() {}
        override fun commitContent(inputContentInfo: android.view.inputmethod.InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean = false
    }

    @Test
    fun rapidFocusSwitchNeverCrossInserts() {
        val session = DictationSession()
        val comp = CompositionManager()
        val controller = FieldSessionController(session, comp)
        val fieldA = FakeIC("A:")
        val fieldB = FakeIC("B:")
        val idA = controller.onFieldFocused("fieldA", 2, 2)
        controller.applyPartial(idA, fieldA, "hello", "world")
        // IME-local policy: partials do not set external composing, so composing stays null
        assertNull(fieldA.composing)
        assertEquals(0, fieldA.committed.length - "A:".length)
        // Rapid switch to B before final
        controller.onFieldLost("fieldA")
        val idB = controller.onFieldFocused("fieldB", 2, 2)
        // Late final from A must be ignored
        val ignored = controller.commitFinal(idA, fieldA, "hello world")
        assertFalse(ignored)
        assertEquals("A:", fieldA.committed.toString()) // not inserted into A
        // Correct final into B
        val ok = controller.commitFinal(idB, fieldB, "final B")
        assertTrue(ok)
        assertTrue(fieldB.committed.toString().contains("final B"))
        assertFalse(fieldA.committed.toString().contains("final B"))
    }

    @Test
    fun composingReplacesPreviousPartial() {
        val session = DictationSession()
        val comp = CompositionManager()
        val controller = FieldSessionController(session, comp)
        val field = FakeIC()
        val id = controller.onFieldFocused("f1", 0, 0)
        // IME-local: partials stay inside Sprich, composing stays null externally
        controller.applyPartial(id, field, "Let's meet", "tomorrow")
        assertNull(field.composing)
        controller.applyPartial(id, field, "Let's meet", "Friday")
        assertNull(field.composing)
        assertEquals(0, field.committed.length)
    }

    @Test
    fun staleInputConnectionIsRejected() {
        val session = DictationSession()
        val comp = CompositionManager()
        val controller = FieldSessionController(session, comp)
        val current = FakeIC("cur")
        val stale = FakeIC("stale")
        val id = controller.onFieldFocused("curField", 3, 3)
        // Stale callback with wrong sessionId should be rejected even if we pass stale IC
        val fakeOldId = id + 99
        assertFalse(controller.applyPartial(fakeOldId, stale, "hi", ""))
        assertFalse(controller.commitFinal(fakeOldId, stale, "hi"))
        // Valid id but null IC also rejected
        assertFalse(controller.applyPartial(id, null, "hi", ""))
    }

    @Test
    fun finalCommitsExactlyOnce() {
        val session = DictationSession()
        val comp = CompositionManager()
        val controller = FieldSessionController(session, comp)
        val field = FakeIC()
        val id = controller.onFieldFocused("f", 0, 0)
        controller.applyPartial(id, field, "hello", "")
        assertTrue(controller.commitFinal(id, field, "hello world"))
        assertTrue(field.committed.toString().contains("hello world"))
        // Second commit with same id should be rejected (session ended)
        assertFalse(controller.commitFinal(id, field, "hello world again"))
    }
}
