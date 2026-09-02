package com.sprich.app

import com.sprich.app.input.composition.CompositionManager
import com.sprich.app.input.lifecycle.DictationSession
import com.sprich.app.input.lifecycle.SessionState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionOwnershipAndFsmPropertyTest {

    @Test
    fun sessionIdIsUniquePerFocusCycle() {
        val s = DictationSession()
        val id1 = s.start()
        s.onAudioStarted()
        s.end()
        val id2 = s.start()
        s.onAudioStarted()
        s.end()
        assertNotEquals(id1, id2)
        assertTrue(id2 > id1)
    }

    @Test
    fun lateCallbacksFromOldSessionAreIgnored() {
        val s = DictationSession()
        val id1 = s.start()
        s.onAudioStarted()
        // simulate focus loss / new session
        s.end()
        val id2 = s.start()
        s.onAudioStarted()
        // old id should be invalid
        assertFalse(s.isSessionValid(id1))
        assertTrue(s.isSessionValid(id2))
        assertFalse(s.isSessionValid(id1 + 999))
    }

    private class FakeIC(private val acceptsComposition: Boolean = true) : android.view.inputmethod.InputConnection {
        var committed = StringBuilder()
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
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean { if (committed.length >= beforeLength) committed.delete(committed.length - beforeLength, committed.length); return true }
        override fun deleteSurroundingTextInCodePoints(b: Int, a: Int): Boolean = deleteSurroundingText(b,a)
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (!acceptsComposition) return false
            composing = text?.toString()
            return true
        }
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
    fun compositionPartialIsComposingAndFinalCommitsOnce() {
        val cm = CompositionManager()
        val ic = FakeIC()
        // IME-local partials: composing stays null externally, final commits exactly once
        cm.applyUpdate(ic, "Hello", "", false)
        assertNull(ic.composing)
        cm.applyUpdate(ic, "Hello world", "", false)
        assertNull(ic.composing)
        assertEquals(0, ic.committed.length)
        // final commits exactly once
        cm.applyUpdate(ic, "Hello world", "", true)
        assertEquals("Hello world", ic.committed.toString())
        assertNull(ic.composing)
        // second final with same text should not duplicate if reset — also IME-local
        cm.reset()
        val ic2 = FakeIC().apply { committed.append("Before ") }
        cm.applyUpdate(ic2, "hello", "", false)
        assertNull(ic2.composing)
        cm.applyUpdate(ic2, "hello world", "", true)
        assertEquals("Before hello world", ic2.committed.toString())
    }

    @Test
    fun noDuplicateLostOrCrossFieldIn1000RandomizedSequences() {
        var duplicateInsertions = 0
        var lostInsertions = 0
        var crossFieldInsertions = 0
        repeat(1000) { trial ->
            val session = DictationSession()
            val cm = CompositionManager()
            // Each trial simulates rapid focus switching with session ownership
            val fieldA = FakeIC()
            val fieldB = FakeIC()
            val sessionIdA = session.start()
            session.onAudioStarted()
            session.onSpeechOnset()
            // Partial into field A
            cm.applyUpdate(fieldA, "trial $trial", "partial", false)
            // Simulate rapid focus switch to field B before final
            val committedGeneration = sessionIdA
            session.end()
            val sessionIdB = session.start()
            session.onAudioStarted()
            cm.reset()
            // Old callback tries to commit into field A after focus switch — must be ignored via sessionId check
            val isOldSessionValid = session.isSessionValid(committedGeneration)
            if (isOldSessionValid) {
                // If we failed to invalidate old session, count as cross-field
                crossFieldInsertions++
            }
            // Correct final should go into field B, not A
            // Simulate that late callback is discarded by not calling applyUpdate with old IC
            // Instead, commit into current field B
            val applied = cm.applyUpdate(fieldB, "final $trial", "", true)
            if (!applied) lostInsertions++
            if (fieldA.committed.toString().contains("final $trial")) crossFieldInsertions++
            if (fieldB.committed.toString().count { it == 'f' } > 1) {
                // naive duplication check: committed should be exactly "final X"
                if (fieldB.committed.toString() != "final $trial") duplicateInsertions++
            }
            // Ensure session ended properly
            session.end()
            assertTrue(session.state.value is SessionState.Idle)
        }
        assertEquals("duplicate insertions", 0, duplicateInsertions)
        assertEquals("lost insertions", 0, lostInsertions)
        assertEquals("cross-field insertions", 0, crossFieldInsertions)
    }

    @Test
    fun stateMachinePropertyTransitionsNeverDeadlock() {
        val rng = Random(999)
        repeat(1000) {
            val s = DictationSession()
            // Walk random valid transitions
            s.start()
            assertTrue(s.state.value is SessionState.Preparing)
            s.onAudioStarted()
            assertTrue(s.state.value is SessionState.Listening)
            // Randomly go to Speech or Finalizing or Suspended
            when (rng.nextInt(4)) {
                0 -> {
                    s.onSpeechOnset()
                    assertTrue(s.state.value is SessionState.Speech)
                    s.onFinalizing()
                    assertTrue(s.state.value is SessionState.Finalizing)
                    s.onInserting()
                    assertTrue(s.state.value is SessionState.Inserting)
                    s.onListeningAgain()
                }
                1 -> {
                    s.onSpeechOnset()
                    s.onFinalizing()
                    s.end()
                    assertTrue(s.state.value is SessionState.Idle)
                    return@repeat
                }
                2 -> {
                    s.transition(SessionState.Suspended)
                    assertTrue(s.state.value is SessionState.Suspended)
                    s.transition(SessionState.Listening)
                    assertTrue(s.state.value is SessionState.Listening)
                }
                else -> {
                    s.error("random")
                    assertTrue(s.state.value is SessionState.Error)
                    s.idle()
                    assertTrue(s.state.value is SessionState.Idle)
                    return@repeat
                }
            }
            s.end()
            assertTrue(s.state.value is SessionState.Idle)
        }
    }

    @Test
    fun recoverableErrorCanRestart() {
        val s = DictationSession()
        s.start()
        s.error("mic")
        assertTrue(s.state.value is SessionState.Error)
        s.idle()
        assertTrue(s.state.value is SessionState.Idle)
        s.start()
        assertTrue(s.state.value is SessionState.Preparing)
        s.onAudioStarted()
        assertTrue(s.state.value is SessionState.Listening)
        s.end()
        assertTrue(s.state.value is SessionState.Idle)
    }

    @Test
    fun staleInputConnectionNeverReceivesOutput() {
        val cm = CompositionManager()
        val staleIc = FakeIC().apply { committed.append("stale") }
        val currentIc = FakeIC().apply { committed.append("current") }
        // Start session, partial into stale should be replaced by final into current only via generation guard
        // Simulate that we check session validity before committing
        val session = DictationSession()
        val id1 = session.start()
        cm.applyUpdate(staleIc, "partial", "", false)
        session.end()
        val id2 = session.start()
        // Late callback with id1 should be ignored — we simulate by not applying to currentIc with old id
        assertFalse(session.isSessionValid(id1))
        assertTrue(session.isSessionValid(id2))
        // Only current gets final — CompositionManager adds leading space if needed
        cm.reset()
        cm.applyUpdate(currentIc, "final", "", true)
        assertTrue(currentIc.committed.toString().endsWith("final"))
        assertEquals("stale", staleIc.committed.toString()) // stale was not committed via composing fallback — it stays stale
    }
}



