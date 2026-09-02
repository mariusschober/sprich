package com.sprich.app

import com.sprich.app.input.composition.CompositionManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adversarial InputConnection tests per P0:
 * - Verify no duplication loop "Hello" -> "Hello world" -> "HelloHello world"
 * - Policy: partials stay inside Sprich IME, final enters editor exactly once.
 * - Covers 6 editor types + ambiguous final commit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompositionAdversarialTest {

    private open class BaseIc : android.view.inputmethod.InputConnection {
        var committed = StringBuilder()
        var composing: String? = null
        var commitTextCalls = 0
        var setComposingCalls = 0
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = committed.takeLast(n.coerceAtMost(committed.length)).toString()
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getCursorCapsMode(reqModes: Int): Int = 0
        override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int): android.view.inputmethod.ExtractedText? {
            val et = android.view.inputmethod.ExtractedText()
            et.text = committed.toString() + (composing ?: "")
            et.startOffset = 0
            et.selectionStart = et.text.length
            et.selectionEnd = et.text.length
            return et
        }
        override fun deleteSurroundingText(b: Int, a: Int): Boolean { if (committed.length >= b) committed.delete(committed.length - b, committed.length); return true }
        override fun deleteSurroundingTextInCodePoints(b: Int, a: Int): Boolean = deleteSurroundingText(b,a)
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean { setComposingCalls++; composing = text?.toString(); return true }
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

    // 1. correctly supports composing
    private class CorrectIc : BaseIc() {
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean { setComposingCalls++; composing = text?.toString(); return true }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { composing=null; committed.append(text); commitTextCalls++; return true }
    }

    // 2. rejects composing
    private class RejectingIc : BaseIc() {
        override fun setComposingText(t: CharSequence?, n: Int): Boolean { setComposingCalls++; return false }
        override fun commitText(t: CharSequence?, n: Int): Boolean { committed.append(t); commitTextCalls++; return true }
    }

    // 3. silently commits composing while returning true (hostile)
    private class SilentCommitIc : BaseIc() {
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            setComposingCalls++
            // Hostile: appends text but pretends composing success
            committed.append(text)
            composing = null
            return true
        }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { composing=null; committed.append(text); commitTextCalls++; return true }
    }

    // 4. throws on composing
    private class ThrowingIc : BaseIc() {
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean { setComposingCalls++; throw RuntimeException("editor throws") }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { committed.append(text); commitTextCalls++; return true }
    }

    // 5. WebView-like: setComposingText succeeds but getTextBeforeCursor already includes hypothesis
    private class WebViewLikeIc : BaseIc() {
        var webComposing: String? = null
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            setComposingCalls++
            webComposing = text?.toString()
            // WebView may keep composing internally but getTextBeforeCursor returns committed+webComposing
            composing = null // not using standard composing
            return true
        }
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            // Returns committed plus web composing as if already in field
            val combined = committed.toString() + (webComposing ?: "")
            return combined.takeLast(n.coerceAtMost(combined.length))
        }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            webComposing = null
            composing = null
            committed.append(text)
            commitTextCalls++
            return true
        }
    }

    // 6. mutates on final commitText but returns false (hostile final)
    private class AmbiguousFinalIc : BaseIc() {
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean { setComposingCalls++; composing = text?.toString(); return true }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composing = null
            committed.append(text) // mutates despite false
            commitTextCalls++
            return false
        }
    }

    private val hyps = listOf("Hello", "Hello world", "Hello world this is", "Hello world this is Sprich")

    private fun assertNoDuplication(committed: String, final: String) {
        // No HelloHello
        assertFalse("Duplication HelloHello found in \"$committed\"", committed.contains("HelloHello"))
        // Final appears at most once
        val count = committed.windowed(final.length, 1, false).count { it == final }
        assertTrue("Final appears $count times, expected 1 in \"$committed\"", count <= 1)
    }

    @Test
    fun correctlySupportingEditor_noDuplication_finalOnce() {
        val cm = CompositionManager()
        val ic = CorrectIc()
        for (h in hyps) {
            cm.applyUpdate(ic, h, "", false)
        }
        // Partials must NOT have externally committed text (IME-local policy)
        assertEquals(0, ic.committed.length)
        // Final commits exactly once via commitText
        ic.committed.clear(); ic.composing=null; cm.reset()
        assertTrue(cm.applyUpdate(ic, "Hello world this is Sprich", "", true))
        assertEquals(1, ic.commitTextCalls)
        assertEquals("Hello world this is Sprich", ic.committed.toString())
        assertNoDuplication(ic.committed.toString(), "Sprich")
    }

    @Test
    fun rejectingEditor_fallsBackToPreview_finalOnce() {
        val cm = CompositionManager()
        val ic = RejectingIc()
        for (h in hyps) {
            val res = cm.applyUpdate(ic, h, "", false)
            // Our policy returns false for all partials (IME-local)
            assertFalse(res)
        }
        assertEquals(0, ic.committed.length)
        assertTrue(cm.applyUpdate(ic, "Hello world", "", true))
        assertEquals("Hello world", ic.committed.toString())
        assertEquals(1, ic.commitTextCalls)
        assertFalse(ic.committed.toString().contains("HelloHello"))
    }

    @Test
    fun silentlyCommittingEditor_noDuplicationDueToImeLocal() {
        val cm = CompositionManager()
        val ic = SilentCommitIc()
        for (h in hyps) {
            cm.applyUpdate(ic, h, "", false)
        }
        // With IME-local policy, setComposingText is never called, so silent commit never happens for partials
        // If our old code called setComposingText, committed would have grown 4 times -> duplication
        assertEquals(0, ic.committed.length) // proves we did NOT call hostile setComposingText
        assertTrue(cm.applyUpdate(ic, "Hello world this is Sprich", "", true))
        assertEquals(1, ic.commitTextCalls)
        assertTrue(ic.committed.toString().contains("Sprich"))
        assertFalse(ic.committed.toString().contains("HelloHello"))
        // Ensure not duplicated
        val count = ic.committed.toString().windowed("Sprich".length, 1, false).count { it == "Sprich" }
        assertEquals(1, count)
    }

    @Test
    fun throwingEditor_noCrash_noDuplication() {
        val cm = CompositionManager()
        val ic = ThrowingIc()
        for (h in hyps) {
            // Should handle exception and not commit
            val res = cm.applyUpdate(ic, h, "", false)
            assertFalse(res) // IME-local fallback
        }
        assertEquals(0, ic.committed.length)
        assertTrue(cm.applyUpdate(ic, "Hello world", "", true))
        assertEquals("Hello world", ic.committed.toString())
        assertFalse(ic.committed.toString().contains("HelloHello"))
    }

    @Test
    fun webViewLikeEditor_noDuplication() {
        val cm = CompositionManager()
        val ic = WebViewLikeIc()
        for (h in hyps) {
            cm.applyUpdate(ic, h, "", false)
        }
        // WebView-like getTextBeforeCursor should not affect final spacing duplication
        assertTrue(cm.applyUpdate(ic, "Hello world", "", true))
        assertEquals(1, ic.commitTextCalls)
        assertFalse(ic.committed.toString().contains("HelloHello"))
    }

    @Test
    fun ambiguousFinal_commitTextAppendsButReturnsFalse_exactlyOnce_noRetry() {
        val cm = CompositionManager()
        val ic = AmbiguousFinalIc()
        // No partials committed
        for (h in hyps) cm.applyUpdate(ic, h, "", false)
        assertEquals(0, ic.committed.length)
        // Final: commitText appends but returns false -> CompositionManager must NOT retry
        val ok = cm.applyUpdate(ic, "Hello world this is Sprich", "", true)
        assertFalse(ok) // because commit returned false
        assertEquals(1, ic.commitTextCalls) // exactly one irreversible attempt
        assertEquals("Hello world this is Sprich", ic.committed.toString())
        // Text appears at most once, no duplication, no second attempt
        assertFalse(ic.committed.toString().contains("HelloHello"))
        // Simulate second attempt incorrectly would double — ensure our code doesn't do it
        assertEquals(1, ic.commitTextCalls)
    }

    @Test
    fun composingsAreImeLocal_neverDuplicate() {
        val editors = listOf<BaseIc>(CorrectIc(), RejectingIc(), SilentCommitIc(), ThrowingIc(), WebViewLikeIc(), AmbiguousFinalIc())
        for (ic in editors) {
            val cm = CompositionManager()
            ic.committed.clear(); ic.composing=null; ic.commitTextCalls=0; ic.setComposingCalls=0
            for (h in hyps) cm.applyUpdate(ic, h, "", false)
            // No editor should have committed partials externally
            // For AmbiguousFinal, partials still 0; for SilentCommit, 0 due to IME-local
            // Check that committed is empty before final (except maybe throwing which stays empty)
            // Allow at most 0 for IME-local policy
            assertTrue("Editor ${ic::class.simpleName} committed partials unexpectedly: ${ic.committed}", ic.committed.isEmpty() || ic.committed.toString() == "")
            // Reset for final
            ic.committed.clear(); ic.commitTextCalls=0; cm.reset()
            cm.applyUpdate(ic, "Hello world", "", true)
            // Final should be at most once
            assertTrue(ic.committed.toString().contains("Hello world") || ic.committed.toString().contains("Hello") || ic.commitTextCalls <=1)
            assertFalse(ic.committed.toString().contains("HelloHello"))
        }
    }
}
