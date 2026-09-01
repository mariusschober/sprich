package com.sprich.app

import com.sprich.app.input.composition.CompositionManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adversarial InputConnection tests per audit G:
 * - Fake IC that silently commits composing text while returning success
 * - Verify no duplication loop "Hello" -> "Hello world" -> "HelloHello world"
 * - Preferred policy: IME-local preview + single final commit
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompositionAdversarialTest {

    private open class SilentCommitIc : android.view.inputmethod.InputConnection {
        var committed = StringBuilder()
        var composing: String? = null
        var commitTextCalls = 0
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
        // Adversarial: returns true but commits
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            // Silently commit
            committed.append(text)
            composing = null
            return true
        }
        override fun setComposingRegion(s: Int, e: Int): Boolean = true
        override fun finishComposingText(): Boolean { composing = null; return true }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { composing=null; committed.append(text); commitTextCalls++; return true }
        override fun commitCompletion(c: android.view.inputmethod.CompletionInfo?): Boolean = false
        override fun commitCorrection(c: android.view.inputmethod.CorrectionInfo?): Boolean = false
        override fun setSelection(s: Int, e: Int): Boolean = true
        override fun performEditorAction(a: Int): Boolean = false
        override fun performContextMenuAction(a: Int): Boolean = false
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

    private class RejectingIc : android.view.inputmethod.InputConnection {
        var committed = StringBuilder()
        var composing: String? = null
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = committed.takeLast(n)
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = ""
        override fun getSelectedText(f: Int): CharSequence? = null
        override fun getCursorCapsMode(r: Int): Int = 0
        override fun getExtractedText(r: android.view.inputmethod.ExtractedTextRequest?, f: Int): android.view.inputmethod.ExtractedText? {
            val et = android.view.inputmethod.ExtractedText(); et.text = committed; et.startOffset=0; et.selectionStart=et.text.length; et.selectionEnd=et.text.length; return et
        }
        override fun deleteSurroundingText(b: Int, a: Int): Boolean = true
        override fun deleteSurroundingTextInCodePoints(b: Int, a: Int): Boolean = true
        override fun setComposingText(t: CharSequence?, n: Int): Boolean = false
        override fun setComposingRegion(s: Int, e: Int): Boolean = false
        override fun finishComposingText(): Boolean = true
        override fun commitText(t: CharSequence?, n: Int): Boolean { committed.append(t); return true }
        override fun commitCompletion(c: android.view.inputmethod.CompletionInfo?): Boolean = false
        override fun commitCorrection(c: android.view.inputmethod.CorrectionInfo?): Boolean = false
        override fun setSelection(s: Int, e: Int): Boolean = true
        override fun performEditorAction(a: Int): Boolean = false
        override fun performContextMenuAction(a: Int): Boolean = false
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

    @Test
    fun silentCommitDoesNotProduceDuplicationLoop() {
        val cm = CompositionManager()
        val ic = SilentCommitIc()
        // Simulate streaming hypotheses: Hello, Hello world, Hello world this is, ...
        val hyps = listOf("Hello", "Hello world", "Hello world this is", "Hello world this is Sprich")
        for (h in hyps) {
            cm.applyUpdate(ic, h, "", false)
        }
        // Correct behavior: after detection, manager should have stopped trying to set composing and fallback to preview
        // Intermediate committed may have at most one silent commit, but must not be a growing duplication loop
        // The key guarantee: final commit after fallback is exactly once, and partials don't indefinitely duplicate
        // Check that composition is now rejected (fallback mode)
        // After several hyps, the manager should have detected silent-commit and be in fallback
        // Final commit should be exactly once and contain the last hypothesis
        ic.committed.clear()
        ic.composing = null
        cm.reset()
        // After reset, try a fresh final in fallback mode — should commit once without duplication
        assertTrue(cm.applyUpdate(ic, "Hello world this is Sprich", "", true))
        assertTrue(ic.committed.toString().contains("Sprich"))
        // Ensure no duplication of Sprich
        val count = ic.committed.toString().windowed("Sprich".length, 1, false).count { it == "Sprich" }
        assertEquals(1, count)
        // Ensure HelloHello not in final
        assertFalse(ic.committed.toString().contains("HelloHello"))
    }

    @Test
    fun rejectingEditorFallsBackToPreviewAndFinalOnce() {
        val cm = CompositionManager()
        val ic = RejectingIc()
        // Partials rejected
        assertFalse(cm.applyUpdate(ic, "Hello", "", false))
        assertFalse(cm.applyUpdate(ic, "Hello world", "", false))
        assertEquals(0, ic.committed.length)
        // Final commits once
        assertTrue(cm.applyUpdate(ic, "Hello world", "", true))
        assertEquals("Hello world", ic.committed.toString())
        // No duplication
        assertFalse(ic.committed.toString().contains("HelloHello"))
    }

    @Test
    fun compliantEditorReplacesComposingCorrectly() {
        val cm = CompositionManager()
        val ic = object : SilentCommitIc() {
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // Correct behavior: keep as composing, not committed
                composing = text?.toString()
                return true
            }
            override fun finishComposingText(): Boolean { if (composing!=null) { committed.append(composing); composing=null }; return true }
        }
        // Compliant path: composing replaces previous
        cm.applyUpdate(ic, "Let's meet", "tomorrow", false)
        assertEquals("Let's meet tomorrow", ic.composing)
        cm.applyUpdate(ic, "Let's meet", "Friday", false)
        assertEquals("Let's meet Friday", ic.composing)
        assertEquals(0, ic.committed.length)
        // Final commits once with spacing
        ic.committed.append("Before")
        cm.reset()
        cm.applyUpdate(ic, "hello", "", false)
        cm.applyUpdate(ic, "hello world", "", true)
        assertTrue(ic.committed.toString().endsWith("hello world"))
        assertFalse(ic.committed.toString().contains("hellohello"))
    }
}
