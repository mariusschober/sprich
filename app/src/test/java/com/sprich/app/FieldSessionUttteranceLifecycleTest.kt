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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FieldSessionUtteranceLifecycleTest {

    private class FakeIc : InputConnection by FakeIcImpl() {
        private class FakeIcImpl : InputConnection {
            var committed = StringBuilder()
            var composing: String? = null
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = committed.takeLast(n)
            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = ""
            override fun getSelectedText(flags: Int): CharSequence? = null
            override fun getCursorCapsMode(reqModes: Int): Int = 0
            override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int): android.view.inputmethod.ExtractedText? {
                val et = android.view.inputmethod.ExtractedText(); et.text = committed; et.startOffset=0; et.selectionStart=et.text.length; et.selectionEnd=et.text.length; return et
            }
            override fun deleteSurroundingText(b: Int, a: Int): Boolean { if (committed.length>=b) committed.delete(committed.length-b, committed.length); return true }
            override fun deleteSurroundingTextInCodePoints(b:Int,a:Int):Boolean = deleteSurroundingText(b,a)
            override fun setComposingText(t: CharSequence?, n: Int): Boolean { composing = t?.toString(); return true }
            override fun setComposingRegion(s:Int,e:Int):Boolean = true
            override fun finishComposingText(): Boolean { if (composing!=null) { committed.append(composing); composing=null }; return true }
            override fun commitText(t: CharSequence?, n: Int): Boolean { composing=null; committed.append(t); return true }
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
    }

    private fun makeIc(): FakeIcImpl2 {
        return FakeIcImpl2()
    }

    private class FakeIcImpl2 : InputConnection {
        var committed = StringBuilder()
        var composing: String? = null
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = (committed.toString() + (composing?:"")).takeLast(n)
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getCursorCapsMode(reqModes: Int): Int = 0
        override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int): android.view.inputmethod.ExtractedText? {
            val et = android.view.inputmethod.ExtractedText(); et.text = (committed.toString() + (composing?:"")); et.startOffset=0; et.selectionStart=et.text.length; et.selectionEnd=et.text.length; return et
        }
        override fun deleteSurroundingText(b: Int, a: Int): Boolean { if (committed.length>=b) committed.delete(committed.length-b, committed.length); return true }
        override fun deleteSurroundingTextInCodePoints(b:Int,a:Int):Boolean = deleteSurroundingText(b,a)
        override fun setComposingText(t: CharSequence?, n: Int): Boolean { composing = t?.toString(); return true }
        override fun setComposingRegion(s:Int,e:Int):Boolean = true
        override fun finishComposingText(): Boolean { composing=null; return true }
        override fun commitText(t: CharSequence?, n: Int): Boolean { composing=null; committed.append(t); return true }
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
    fun fiveUtterancesInOneFieldSession() {
        val session = DictationSession()
        val comp = CompositionManager()
        val controller = FieldSessionController(session, comp)
        val ic = makeIc()

        val fieldId = "field_1_test"
        val sessionId = controller.onFieldFocused(fieldId, 0, 0)
        assertTrue(session.isSessionValid(sessionId))
        assertTrue(controller.isCurrentSession(sessionId))
        assertTrue(session.state.value is SessionState.Preparing)
        session.onAudioStarted()
        assertTrue(session.state.value is SessionState.Listening)

        // Simulate 5 sentences: each utterance is a distinct utteranceId, commit, then listening again
        for (utteranceNum in 1..5) {
            session.onSpeechOnset()
            assertTrue(session.state.value is SessionState.Speech)
            session.onFinalizing()
            assertTrue(session.state.value is SessionState.Finalizing)
            val utteranceId = utteranceNum.toLong()
            val ok = controller.commitUtterance(sessionId, utteranceId, ic, "sentence $utteranceNum")
            assertTrue("utterance $utteranceNum commit should succeed", ok)
            assertTrue(controller.isUtteranceFinalized(utteranceId))
            // After commit, session must be Listening again, not Idle, and same sessionId must remain valid
            assertTrue("after utterance $utteranceNum session should be Listening, was ${session.state.value}", session.state.value is SessionState.Listening)
            assertTrue(controller.isCurrentSession(sessionId))
            assertTrue(session.isSessionValid(sessionId))
            assertEquals(sessionId, controller.currentSessionId())
            // No dropped audio: next utterance can start immediately without new field session
            // committed text should be exactly once per utterance
            assertTrue(ic.committed.toString().contains("sentence $utteranceNum"))
        }

        // Verify exactly-once: duplicate commit of same utteranceId must be rejected
        assertFalse(controller.commitUtterance(sessionId, 3L, ic, "duplicate"))
        // Total commits: 5, committed string should contain each once
        val text = ic.committed.toString()
        for (i in 1..5) {
            val count = text.windowed("sentence $i".length, 1, false).count { it == "sentence $i" }
            assertEquals("sentence $i should appear exactly once", 1, count)
        }

        // Listening continues after commits 1-4, no new field session required
        assertTrue(session.requireActive())
        assertEquals(sessionId, controller.currentSessionId())

        // Only actual field/session termination should end
        controller.onFieldLost(fieldId)
        assertTrue(session.state.value is SessionState.Idle)
        assertFalse(session.requireActive())
        assertFalse(controller.isCurrentSession(sessionId))
    }

    @Test
    fun fieldSwitchDuringFinalizationDropsStale() {
        val session = DictationSession()
        val comp = CompositionManager()
        val controller = FieldSessionController(session, comp)
        val fieldA = makeIc()
        val fieldB = makeIc()

        val idA = controller.onFieldFocused("fieldA", 0, 0)
        session.onAudioStarted()
        session.onSpeechOnset()
        session.onFinalizing()
        // Simulate decode started for utterance 1
        val utteranceId1 = 1L
        controller.commitUtterance(idA, utteranceId1, fieldA, "hello A")
        assertTrue(session.state.value is SessionState.Listening)

        // Field switches while next utterance decode might be running
        controller.onFieldLost("fieldA")
        val idB = controller.onFieldFocused("fieldB", 0, 0)
        session.onAudioStarted()
        // Old token's commit must be rejected
        assertFalse(controller.commitUtterance(idA, 2L, fieldB, "stale from A"))
        assertFalse(fieldB.committed.toString().contains("stale"))
        // New field's commit should succeed
        session.onSpeechOnset()
        session.onFinalizing()
        assertTrue(controller.commitUtterance(idB, 1L, fieldB, "fresh B"))
        assertTrue(fieldB.committed.toString().contains("fresh B"))
    }

    @Test
    fun cancellationMustDiscardNotCommitPartial() {
        val session = DictationSession()
        val comp = CompositionManager()
        val controller = FieldSessionController(session, comp)
        val ic = makeIc()
        val id = controller.onFieldFocused("field", 0, 0)
        session.onAudioStarted()
        // IME-local partial: external composing stays null
        controller.applyPartial(id, ic, "This is only a par", "")
        assertNull(ic.composing)
        // FIELD_LOST must discard, not commit — still no committed text
        controller.onFieldLost("field")
        assertFalse(ic.committed.toString().contains("This is only a par"))
        // And session should be Idle
        assertTrue(session.state.value is SessionState.Idle)
    }
}
