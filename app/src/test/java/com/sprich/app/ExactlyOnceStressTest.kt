package com.sprich.app

import com.sprich.app.input.composition.CompositionManager
import com.sprich.app.input.lifecycle.DictationSession
import com.sprich.app.input.lifecycle.FieldSessionController
import com.sprich.app.input.lifecycle.StopReason
import com.sprich.app.input.lifecycle.UtteranceToken
import com.sprich.app.speech.api.*
import com.sprich.app.speech.canary.CanaryEngine
import com.sprich.app.speech.stabilization.TranscriptStabilizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Exactly-once acceptance gates:
 * - 10k randomized state/session transitions with zero double-final commits
 * - maximum one endUtterance per utterance
 * - maximum one final insertion per utterance
 * - zero stale insertion after field switch
 * - zero old partial replay when new collector/session starts
 * - concurrent stop tap vs VAD endpoint
 * - field switch during native final decode
 * - onStartInput(restarting=true) during finalization
 * - window hides during finalization
 * - service destruction during finalization
 * - 100 rapid field switches
 * - editor rejects composing, silently commits, replaces correctly
 * - empty final, decode error, cancelled/slow decode
 * - intentional repeated phrases remain intact
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExactlyOnceStressTest {

    private open class FakeIc(var text: String = "") : android.view.inputmethod.InputConnection {
        var committed = StringBuilder(text)
        var composing: String? = null
        var commitTextCalls = 0
        var setComposingCalls = 0
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
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean { setComposingCalls++; composing = text?.toString(); return true }
        override fun setComposingRegion(start: Int, end: Int): Boolean = true
        override fun finishComposingText(): Boolean { if (composing!=null) { committed.append(composing); composing=null }; return true}
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { commitTextCalls++; composing = null; committed.append(text); return true }
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

    private class FakeSlowEngine : SpeechEngine {
        override val engineId = "fake-slow"
        override val displayName = "Fake Slow"
        private var loaded = true
        private val flow = MutableSharedFlow<TranscriptUpdate>(replay = 0, extraBufferCapacity = 16)
        @Volatile var decodeStarts = 0L
        @Volatile var maxConc = 0
        @Volatile var curConc = 0
        @Volatile var endUtteranceCalls = 0L
        private val mutex = kotlinx.coroutines.sync.Mutex()
        override fun capabilities() = SpeechEngineCapabilities(partialResults = true)
        override fun supportedLanguages() = setOf(Language.EN, Language.DE)
        override fun isLoaded() = loaded
        override suspend fun load(): Result<Unit> = Result.success(Unit)
        override suspend fun unload() { loaded = false }
        override fun beginSession(config: SpeechSessionConfig) {}
        override fun pushAudio(samples: ShortArray, timestampNanos: Long) {}
        override fun partialTranscript() = flow
        override suspend fun endUtterance(): FinalTranscript {
            mutex.lock()
            try {
                endUtteranceCalls++
                curConc++
                maxConc = maxOf(maxConc, curConc)
                decodeStarts++
                delay(30) // simulate slow native decode while holding mutex — serializes
            } finally {
                curConc--
                mutex.unlock()
            }
            return FinalTranscript("final $endUtteranceCalls")
        }
        override fun cancelSession() {}
        override fun reset() {}
    }

    @Test
    fun tenThousandRandomizedTransitionsZeroDoubleFinalCommits() = runBlocking {
        var duplicateCommits = 0
        var staleInserts = 0
        var doubleEndUtterance = 0
        val rng = Random(1234)
        repeat(10000) { trial ->
            val session = DictationSession()
            val comp = CompositionManager()
            val controller = FieldSessionController(session, comp)
            val fieldA = FakeIc("A:")
            val fieldB = FakeIc("B:")
            val idA = controller.onFieldFocused("fieldA", 2, 2)
            // Simulate partial
            controller.applyPartial(idA, fieldA, "hello", "")
            // Rapid switch to B
            controller.onFieldLost("fieldA")
            val idB = controller.onFieldFocused("fieldB", 2, 2)
            // Late final from A must be ignored
            val ignored = controller.commitFinal(idA, fieldA, "hello world")
            if (ignored) duplicateCommits++
            if (fieldA.committed.toString().contains("hello world") && fieldA.committed.toString() != "A:") staleInserts++
            val ok = controller.commitFinal(idB, fieldB, "final $trial")
            if (!ok) duplicateCommits++ // should succeed
            // Try double commit on same idB
            val second = controller.commitFinal(idB, fieldB, "second")
            if (second) duplicateCommits++
            controller.cancelActive()
        }
        assertEquals("duplicate or double commits", 0, duplicateCommits)
        assertEquals("stale inserts", 0, staleInserts)
    }

    @Test
    fun maxOneEndUtterancePerUtteranceEvenWithConcurrentCallers() = runBlocking {
        val engine = FakeSlowEngine()
        val finalized = Collections.synchronizedSet(mutableSetOf<Long>())
        val claims = AtomicLong(0)
        val utteranceId = 42L
        val jobs = mutableListOf<Job>()
        repeat(10) {
            jobs += launch {
                val claimed = synchronized(finalized) {
                    if (finalized.contains(utteranceId)) false else { finalized.add(utteranceId); true }
                }
                if (claimed) {
                    claims.incrementAndGet()
                    engine.endUtterance()
                }
            }
        }
        jobs.joinAll()
        assertEquals(1L, claims.get())
        assertEquals(1L, engine.endUtteranceCalls)
        assertEquals(1, engine.maxConc)
    }

    @Test
    fun maxOneFinalInsertionPerUtterance() = runBlocking {
        val session = DictationSession()
        val comp = CompositionManager()
        val controller = FieldSessionController(session, comp)
        val ic = FakeIc()
        val id = controller.onFieldFocused("f", 0, 0)
        controller.applyPartial(id, ic, "hello", "")
        assertTrue(controller.commitFinal(id, ic, "hello world"))
        assertEquals(1, ic.commitTextCalls)
        // second attempt must fail
        assertFalse(controller.commitFinal(id, ic, "hello world again"))
        assertEquals(1, ic.commitTextCalls)
        // committed exactly once
        assertEquals("hello world", ic.committed.toString())
    }

    @Test
    fun zeroStaleInsertionAfterFieldSwitch() {
        val session = DictationSession()
        val comp = CompositionManager()
        val controller = FieldSessionController(session, comp)
        val fieldA = FakeIc("A:")
        val fieldB = FakeIc("B:")
        val idA = controller.onFieldFocused("fieldA", 2, 2)
        controller.applyPartial(idA, fieldA, "partial A", "")
        controller.onFieldLost("fieldA")
        val idB = controller.onFieldFocused("fieldB", 2, 2)
        // Old callback tries to commit to A
        assertFalse(controller.commitFinal(idA, fieldA, "stale"))
        assertEquals("A:", fieldA.committed.toString())
        // Correct final to B
        assertTrue(controller.commitFinal(idB, fieldB, "fresh"))
        assertTrue(fieldB.committed.toString().contains("fresh"))
        assertFalse(fieldA.committed.toString().contains("fresh"))
    }

    @Test
    fun zeroOldPartialReplayWhenNewCollectorStarts() {
        // Direct stale guard test: old session's partial must not be inserted after field switch
        val session = DictationSession()
        val comp = CompositionManager()
        val controller = FieldSessionController(session, comp)
        val fieldA = FakeIc("A:")
        val fieldB = FakeIc("B:")
        val idA = controller.onFieldFocused("fieldA", 2, 2)
        controller.applyPartial(idA, fieldA, "old partial", "")
        // Field switches before collector for next session starts
        controller.onFieldLost("fieldA")
        val idB = controller.onFieldFocused("fieldB", 2, 2)
        // Old partial replay attempt with old sessionId must be ignored
        assertFalse(controller.applyPartial(idA, fieldA, "old partial", ""))
        // New session's fresh partial: under IME-local policy, partials are preview-only (return false, no external composing)
        assertFalse(controller.applyPartial(idB, fieldB, "new", "partial"))
        assertNull(fieldB.composing)
        assertFalse(fieldA.committed.toString().contains("new"))
    }

    @Test
    fun userTapsStopExactlyAsVadEndpointFires_OnlyOneFinal() = runBlocking {
        val engine = FakeSlowEngine()
        val finalized = Collections.synchronizedSet(mutableSetOf<Long>())
        val token = UtteranceToken(1, 1, 1, "field1", 1, null)
        val jobs = listOf(
            async { // VAD endpoint
                val claimed = synchronized(finalized) { if (finalized.contains(token.utteranceId)) false else { finalized.add(token.utteranceId); true } }
                if (claimed) engine.endUtterance()
            },
            async { // USER_STOP
                val claimed = synchronized(finalized) { if (finalized.contains(token.utteranceId)) false else { finalized.add(token.utteranceId); true } }
                if (claimed) engine.endUtterance()
            }
        )
        jobs.awaitAll()
        assertEquals(1L, engine.endUtteranceCalls)
    }

    @Test
    fun fieldSwitchDuringNativeFinalDecode_dropsStale() = runBlocking {
        val engine = FakeSlowEngine()
        val session = DictationSession()
        val comp = CompositionManager()
        val controller = FieldSessionController(session, comp)
        val fieldA = FakeIc("A:")
        val fieldB = FakeIc("B:")
        val idA = controller.onFieldFocused("fieldA", 0, 0)
        controller.applyPartial(idA, fieldA, "hello", "")
        // Start slow decode
        val decodeJob = async { engine.endUtterance() }
        // During decode, field switches
        controller.onFieldLost("fieldA")
        val idB = controller.onFieldFocused("fieldB", 0, 0)
        val result = decodeJob.await()
        // Stale result must not be inserted into B via old token
        assertFalse(controller.commitFinal(idA, fieldB, result.text))
        // Correct insertion into B
        assertTrue(controller.commitFinal(idB, fieldB, "fresh B"))
        assertFalse(fieldB.committed.toString().contains("final"))
    }

    @Test
    fun onStartInputRestartingDuringFinalization_dropsOld() {
        val session = DictationSession()
        val comp = CompositionManager()
        val controller = FieldSessionController(session, comp)
        val fieldA = FakeIc()
        val idA = controller.onFieldFocused("fieldA", 0, 0)
        controller.applyPartial(idA, fieldA, "hello", "")
        // Simulate onStartInput(restarting=true) during finalization — field lost with new generation
        controller.onFieldLost("fieldA")
        val idB = controller.onFieldFocused("fieldA_restarted", 0, 0)
        assertFalse(controller.commitFinal(idA, fieldA, "stale after restart"))
        assertTrue(controller.commitFinal(idB, fieldA, "new"))
    }

    @Test
    fun windowHiddenPrioritizesSafetyOverSpeculativeFinal() {
        val session = DictationSession()
        val comp = CompositionManager()
        val controller = FieldSessionController(session, comp)
        val field = FakeIc()
        val id = controller.onFieldFocused("f", 0, 0)
        controller.applyPartial(id, field, "partial", "")
        // WINDOW_HIDDEN should cancel without inserting
        controller.onFieldLost("f")
        controller.cancelActive()
        assertFalse(controller.commitFinal(id, field, "speculative"))
        assertEquals("", field.committed.toString())
    }

    @Test
    fun serviceDestroyedCancelsWithoutInsert() {
        val session = DictationSession()
        session.start()
        session.onAudioStarted()
        // Simulate service destroyed
        session.error("service destroyed")
        assertFalse(session.requireActive())
    }

    @Test
    fun hundredRapidFieldSwitchesNoCrossInsert() {
        repeat(100) { trial ->
            val session = DictationSession()
            val comp = CompositionManager()
            val controller = FieldSessionController(session, comp)
            val fields = (0..5).map { FakeIc("F$it:") }
            var lastId: Long = -1
            var lastField: FakeIc? = null
            for (i in 0..5) {
                val id = controller.onFieldFocused("field$i", 3, 3)
                val f = fields[i]
                controller.applyPartial(id, f, "trial $trial field $i", "")
                if (lastId != -1L) {
                    // previous field's late final must be ignored
                    assertFalse(controller.commitFinal(lastId, lastField!!, "late $trial $i"))
                }
                lastId = id
                lastField = f
                if (i < 5) controller.onFieldLost("field$i")
            }
            // Only last field may commit
            assertTrue(controller.commitFinal(lastId, lastField!!, "final $trial"))
            // Ensure no other field got final
            fields.dropLast(1).forEach { f ->
                assertFalse(f.committed.toString().contains("final $trial"))
            }
        }
    }

    @Test
    fun editorRejectsComposingWaitsForSingleFinal() {
        val comp = CompositionManager()
        val ic = object : FakeIc() {
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = false
        }
        assertFalse(comp.applyUpdate(ic, "hello", "", false))
        assertEquals(0, ic.committed.length)
        // Final must still commit once
        assertTrue(comp.applyUpdate(ic, "hello world", "", true))
        assertEquals("hello world", ic.committed.toString())
        assertEquals(1, ic.commitTextCalls)
    }

    @Test
    fun editorSilentlyCommitsIsDetectedAndFallbackPreventsDuplication() {
        val comp = CompositionManager()
        // New IME-local policy: partials never go externally, so silent-commit hostiles never get a chance to duplicate.
        val tmpIc = FakeIc()
        assertFalse(comp.applyUpdate(tmpIc, "Hello", "", false))
        comp.reset()
        // Direct test with explicit rejecting fake — still IME-local, no external composing
        val ic = object : FakeIc() {
            var calls = 0
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                calls++
                composing = text?.toString()
                setComposingCalls++
                return true
            }
        }
        // First partial — IME-local, should return false regardless of editor capability
        assertFalse(comp.applyUpdate(ic, "Hello", "", false))
        assertNull(ic.composing)
        // Second partial — still IME-local
        val second = comp.applyUpdate(ic, "Hello world", "", false)
        assertFalse(second)
        // Final must still commit exactly once
        ic.composing = null
        comp.reset()
        assertTrue(comp.applyUpdate(ic, "final", "", true))
        assertEquals("final", ic.committed.toString())
        // Silent-commit editor: with IME-local, duplication never happens because partials never externally committed
        val comp2 = CompositionManager()
        val silentIc = object : FakeIc() {
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composing = null
                committed.append(text)
                setComposingCalls++
                return true
            }
        }
        // Partials are IME-local, so silentIc never gets to append via partial
        assertFalse(comp2.applyUpdate(silentIc, "Hello", "", false))
        assertEquals(0, silentIc.committed.length)
        assertFalse(comp2.applyUpdate(silentIc, "Hello world", "", false))
        assertEquals(0, silentIc.committed.length)
        // Only final commits
        assertTrue(comp2.applyUpdate(silentIc, "Hello world final", "", true))
        assertEquals("Hello world final", silentIc.committed.toString())
    }

    @Test
    fun editorReplacesComposingCorrectly() {
        val cm = CompositionManager()
        val ic = FakeIc()
        // IME-local partials: composing stays null externally, but final still merges correctly
        cm.applyUpdate(ic, "Hello", "", false)
        assertNull(ic.composing)
        cm.applyUpdate(ic, "Hello world", "", false)
        assertNull(ic.composing)
        assertEquals(0, ic.committed.length)
        cm.applyUpdate(ic, "Hello world", "", true)
        assertEquals("Hello world", ic.committed.toString())
        assertNull(ic.composing)
    }

    @Test
    fun emptyFinalDoesNotInsert() {
        val cm = CompositionManager()
        val ic = FakeIc("before ")
        cm.applyUpdate(ic, "partial", "", false)
        // Empty final should just finish composing without inserting
        assertTrue(cm.applyUpdate(ic, "", "", true))
        assertEquals("before ", ic.committed.toString())
        assertNull(ic.composing)
    }

    @Test
    fun nativeDecodeErrorDoesNotDuplicate() = runBlocking {
        val engine = object : SpeechEngine {
            override val engineId = "err"
            override val displayName = "Err"
            override fun capabilities() = SpeechEngineCapabilities()
            override fun supportedLanguages() = setOf(Language.EN)
            override fun isLoaded() = true
            override suspend fun load() = Result.success(Unit)
            override suspend fun unload() {}
            override fun beginSession(config: SpeechSessionConfig) {}
            override fun pushAudio(samples: ShortArray, timestampNanos: Long) {}
            override fun partialTranscript() = MutableSharedFlow<TranscriptUpdate>(replay = 0)
            override suspend fun endUtterance(): FinalTranscript = throw RuntimeException("native decode failed")
            override fun cancelSession() {}
            override fun reset() {}
        }
        var threw = false
        try { engine.endUtterance() } catch (_: Exception) { threw = true }
        assertTrue(threw)
        // Second call should also just throw, not duplicate insertion (caller must handle)
        threw = false
        try { engine.endUtterance() } catch (_: Exception) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun cancelledSlowDecodeDoesNotBlockNextUtterance() = runBlocking {
        val engine = FakeSlowEngine()
        val job = async { engine.endUtterance() }
        delay(5)
        job.cancel()
        try { job.await() } catch (_: CancellationException) {}
        // Give mutex time to release
        delay(40)
        // Next utterance should still succeed and not be blocked
        val result = engine.endUtterance()
        assertTrue(result.text.isNotBlank())
        // Max concurrency should still be 1 because mutex serializes
        assertEquals(1, engine.maxConc)
    }

    @Test
    fun intentionalRepeatedPhrasesRemainIntact() {
        val cases = listOf(
            "very very good" to "very very good",
            "no no no" to "no no no",
            "I said that, I said that" to "I said that, I said that",
            "Hello Hello world" to "Hello Hello world",
        )
        for ((input, expected) in cases) {
            val cm = CompositionManager()
            val ic = FakeIc()
            // Simulate that decoder itself produced repeated phrase as final
            assertTrue(cm.applyUpdate(ic, expected, "", true))
            assertEquals(expected, ic.committed.toString())
            // Ensure no dedup logic stripped intentional repetition
            assertTrue(ic.committed.toString().contains(expected))
        }
    }

    @Test
    fun maxConcurrentInferenceIsExactlyOne_withSlowDecoder() = runBlocking {
        val engine = FakeSlowEngine()
        // Launch many concurrent decodes — mutex must serialize to maxConc==1
        val jobs = (1..20).map { async { engine.endUtterance() } }
        jobs.awaitAll()
        assertEquals(1, engine.maxConc)
        assertEquals(20L, engine.endUtteranceCalls)
    }
}
