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

    private class FakeIC(private val acceptsComposition: Boolean = true) : InputConnection {
        var committed = StringBuilder()
        var composing: String? = null
        var finishCount = 0
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
            (committed.toString() + (composing ?: "")).takeLast(n)
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
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean { if (committed.isNotEmpty() && beforeLength <= committed.length) committed.delete(committed.length - beforeLength, committed.length); return true }
        override fun deleteSurroundingTextInCodePoints(b: Int, a: Int): Boolean = deleteSurroundingText(b,a)
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (!acceptsComposition) return false
            composing = text?.toString()
            return true
        }
        override fun setComposingRegion(start: Int, end: Int): Boolean = true
        override fun finishComposingText(): Boolean { if (composing!=null) { committed.append(composing); composing=null; finishCount++ }; return true}
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composing = null
            committed.append(text)
            return true
        }
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
        // P0: partials are IME-local — never externally set composing, no duplication risk
        val res = cm.applyUpdate(ic, "Hello", "world", false)
        assertFalse(res)
        assertNull(ic.composing)
        assertEquals(0, ic.committed.length)
    }

    @Test
    fun finalCommitMerges() {
        val cm = CompositionManager()
        val ic = FakeIC()
        cm.applyUpdate(ic, "Hello", "world", false)
        // Partial is IME-local, composing stays null
        assertNull(ic.composing)
        cm.applyUpdate(ic, "Hello world", "", true)
        assertEquals("Hello world", ic.committed.toString())
        assertNull(ic.composing)
    }

    @Test
    fun noDuplicationOnOverlappingHypothesis() {
        val cm = CompositionManager()
        val ic = FakeIC()
        cm.applyUpdate(ic, "Let's meet", "tomorrow", false)
        // IME-local, no external composing
        assertNull(ic.composing)
        cm.applyUpdate(ic, "Let's meet", "Friday", false)
        assertNull(ic.composing)
        assertEquals(0, ic.committed.length)
        // Final must not duplicate
        cm.applyUpdate(ic, "Let's meet Friday", "", true)
        assertEquals("Let's meet Friday", ic.committed.toString())
        assertFalse(ic.committed.toString().contains("Let's meetLet's meet"))
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
        // composing never active externally, still clears internal state
        cm.finishIfActive(ic)
        assertNull(ic.composing)
    }

    @Test
    fun rejectedCompositionWaitsForSingleFinalCommit() {
        val cm = CompositionManager()
        val ic = FakeIC(acceptsComposition = false)
        assertFalse(cm.applyUpdate(ic, "Hello", "", false))
        assertFalse(cm.applyUpdate(ic, "Hello world", "", false))
        assertEquals("", ic.committed.toString())

        assertTrue(cm.applyUpdate(ic, "Hello world", "", true))
        assertEquals("Hello world", ic.committed.toString())
    }

    @Test
    fun finalAfterPartialUsesOriginalCursorForSpacing() {
        val cm = CompositionManager()
        val emptyField = FakeIC()
        cm.applyUpdate(emptyField, "Hello", "", false)
        // Partial IME-local, no composing externally
        assertNull(emptyField.composing)
        cm.applyUpdate(emptyField, "Hello world", "", true)
        assertEquals("Hello world", emptyField.committed.toString())

        val populatedField = FakeIC().apply { committed.append("Before") }
        cm.reset()
        cm.applyUpdate(populatedField, "hello", "", false)
        // Partial IME-local, so composing stays null, but leadingSpaceNeeded cached
        assertNull(populatedField.composing)
        cm.applyUpdate(populatedField, "hello world", "", true)
        assertEquals("Before hello world", populatedField.committed.toString())
    }
}
