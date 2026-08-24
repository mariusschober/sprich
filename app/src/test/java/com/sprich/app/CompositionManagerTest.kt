package com.sprich.app

import com.sprich.app.input.composition.CompositionManager
import android.view.inputmethod.InputConnection
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.ExtractedText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompositionManagerTest {

    private class FakeIC : InputConnection {
        var committed = StringBuilder()
        var composing: String? = null
        var finishCount = 0
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = committed.toString().takeLast(n)
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
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean { committed.delete(committed.length - beforeLength, committed.length); return true }
        override fun deleteSurroundingTextInCodePoints(b: Int, a: Int): Boolean = deleteSurroundingText(b,a)
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean { composing = text?.toString(); return true }
        override fun setComposingRegion(start: Int, end: Int): Boolean = true
        override fun finishComposingText(): Boolean { if (composing!=null) { committed.append(composing); composing=null; finishCount++ }; return true}
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { if (composing!=null){ committed.append(composing); composing=null } ; committed.append(text); return true}
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
    fun applyUpdateSetsComposing() {
        val cm = CompositionManager()
        val ic = FakeIC()
        cm.applyUpdate(ic, "Hello", "world", false)
        assertEquals("Hello world", ic.composing)
        assertEquals(0, ic.committed.length)
    }

    @Test
    fun finalCommitMerges() {
        val cm = CompositionManager()
        val ic = FakeIC()
        cm.applyUpdate(ic, "Hello", "world", false)
        cm.applyUpdate(ic, "Hello world", "", true)
        assertTrue(ic.committed.toString().contains("Hello world"))
        assertNull(ic.composing)
    }

    @Test
    fun noDuplicationOnOverlappingHypothesis() {
        val cm = CompositionManager()
        val ic = FakeIC()
        cm.applyUpdate(ic, "Let's meet", "tomorrow", false)
        assertEquals("Let's meet tomorrow", ic.composing)
        cm.applyUpdate(ic, "Let's meet", "Friday", false)
        assertEquals("Let's meet Friday", ic.composing)
        assertEquals(0, ic.committed.length)
    }

    @Test
    fun handlesEmptyGracefully() {
        val cm = CompositionManager()
        val ic = FakeIC()
        assertTrue(cm.applyUpdate(ic, "", "", false))
    }

    @Test
    fun finishIfActiveClears() {
        val cm = CompositionManager()
        val ic = FakeIC()
        cm.applyUpdate(ic, "a", "b", false)
        cm.finishIfActive(ic)
        assertNull(ic.composing)
    }
}
