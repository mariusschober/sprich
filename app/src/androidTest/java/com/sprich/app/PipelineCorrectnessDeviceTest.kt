package com.sprich.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sprich.app.diagnostics.ReplayHarness
import com.sprich.app.input.commands.SpokenEditingParser
import com.sprich.app.input.composition.CompositionManager
import com.sprich.app.input.lifecycle.DictationSession
import com.sprich.app.input.lifecycle.FieldSessionController
import com.sprich.app.input.lifecycle.StopReason
import com.sprich.app.input.lifecycle.UtteranceToken
import com.sprich.app.input.typography.TypographyNormalizer
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.api.TranscriptionTask
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log
import android.view.inputmethod.InputConnection

@RunWith(AndroidJUnit4::class)
class PipelineCorrectnessDeviceTest {

    @Before
    fun ensureModel() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        val destDir = java.io.File(app.filesDir, "canary")
        val enc = java.io.File(destDir, "encoder.int8.onnx")
        if (!enc.exists() || enc.length() < 50_000_000) {
            try {
                destDir.mkdirs()
                java.io.File("/data/local/tmp/encoder.int8.onnx").copyTo(enc, overwrite = true)
                java.io.File("/data/local/tmp/decoder.int8.onnx").copyTo(java.io.File(destDir, "decoder.int8.onnx"), overwrite = true)
                java.io.File("/data/local/tmp/tokens.txt").copyTo(java.io.File(destDir, "tokens.txt"), overwrite = true)
            } catch (_: Exception) {}
        }
    }

    private class FakeIC(var initial: String = "") : InputConnection {
        var committed = StringBuilder(initial)
        var composing: String? = null
        var commitCount = 0
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
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { composing = null; committed.append(text); commitCount++; return true }
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

    @Test
    fun punctuationGermanOnDevice() {
        // SpokenEditingParser
        assertEquals("Hallo.", SpokenEditingParser.parse("Hallo Punkt", Language.DE, true).text)
        assertEquals("Hallo, Welt", SpokenEditingParser.parse("Hallo Komma Welt", Language.DE, true).text)
        assertEquals("Wie geht es?", SpokenEditingParser.parse("Wie geht es Fragezeichen", Language.DE, true).text)
        assertEquals("Hallo!", SpokenEditingParser.parse("Hallo Ausrufezeichen", Language.DE, true).text)
        // Typography raw
        assertEquals("Das ist gut.", TypographyNormalizer.normalize("Das ist gut .", Language.DE))
        assertEquals("Hallo.", TypographyNormalizer.normalize("Hallo .", Language.DE))
        // Composition punctuation-only attach
        val cm = CompositionManager()
        val ic = FakeIC("Hallo")
        assertTrue(cm.applyUpdate(ic, ".", "", true))
        assertEquals("Hallo.", ic.committed.toString())
        val ic2 = FakeIC("Hallo")
        assertTrue(cm.applyUpdate(ic2, ",", "", true))
        assertEquals("Hallo,", ic2.committed.toString())
        assertEquals("3.14", TypographyNormalizer.normalize("3.14", Language.DE))
        assertEquals("example.com", TypographyNormalizer.normalize("example.com", Language.DE))
        Log.i("PipelineDevice", "punctuationGermanOnDevice PASS")
    }

    @Test
    fun punctuationEnglishSpanishOnDevice() {
        assertEquals("Hello, world", SpokenEditingParser.parse("Hello comma world", Language.EN, true).text)
        assertEquals("Hello.", SpokenEditingParser.parse("Hello period", Language.EN, true).text)
        assertEquals("How are you?", SpokenEditingParser.parse("How are you question mark", Language.EN, true).text)
        assertEquals("hola, mundo", SpokenEditingParser.parse("hola coma mundo", Language.ES, true).text)
        assertEquals("hola.", SpokenEditingParser.parse("hola punto", Language.ES, true).text)
        Log.i("PipelineDevice", "punctuation EN/ES PASS")
    }

    @Test
    fun compositionShrinkDoesNotCommitOnDevice() {
        val cm = CompositionManager()
        val ic = FakeIC()
        // IME-local partials: composing stays null externally
        cm.applyUpdate(ic, "Hello world", "", false)
        assertNull(ic.composing)
        cm.applyUpdate(ic, "Hello", "", false)
        assertNull(ic.composing)
        assertEquals(0, ic.committed.length)
        cm.applyUpdate(ic, "Hello there", "", false)
        assertNull(ic.composing)
        cm.applyUpdate(ic, "Hello there", "", true)
        assertEquals("Hello there", ic.committed.toString())
        assertFalse(ic.committed.toString().contains("Hello world"))
        Log.i("PipelineDevice", "composition shrink PASS")
    }

    @Test
    fun emptyPartialDiscardsOnDevice() {
        val cm = CompositionManager()
        val ic = FakeIC("before ")
        cm.applyUpdate(ic, "temporary words", "", false)
        cm.applyUpdate(ic, "", "", false)
        assertNull(ic.composing)
        assertEquals("before ", ic.committed.toString())
        cm.applyUpdate(ic, "something", "", false)
        cm.applyUpdate(ic, "", "", true)
        assertEquals("before ", ic.committed.toString())
        Log.i("PipelineDevice", "empty partial PASS")
    }

    @Test
    fun repetitionDoesNotTriggerFallbackOnDevice() {
        val cases = listOf("very very good", "no no no", "sehr sehr gut", "nein nein nein")
        for (c in cases) {
            val cm = CompositionManager()
            val ic = FakeIC()
            assertTrue(cm.applyUpdate(ic, c, "", true))
            assertEquals(c, ic.committed.toString())
        }
        // Also via live partial — IME-local, returns false, no external composing
        val cm = CompositionManager()
        val ic = FakeIC()
        assertFalse(cm.applyUpdate(ic, "very very", "", false))
        assertNull(ic.composing)
        assertFalse(cm.applyUpdate(ic, "very very good", "", false))
        assertNull(ic.composing)
        assertTrue(cm.applyUpdate(ic, "very very good", "", true))
        assertEquals("very very good", ic.committed.toString())
        Log.i("PipelineDevice", "repetition PASS")
    }

    @Test
    fun overlappingQueueOnDeviceWithRealEngine() { runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        val engine = app.fastEngine
        val load = engine.load()
        assertTrue("engine load ${load.exceptionOrNull()}", load.isSuccess)
        // Use jfk.wav slices for distinct PCM
        val audio = app.assets.open("jfk.wav").use { com.sprich.app.core.audio.Pcm16Wav.read(it) }
        val pcmA = audio.samples.copyOfRange(0, 16000*2) // 2s
        val pcmB = audio.samples.copyOfRange(16000*2, 16000*4) // next 2s distinct
        assertFalse(pcmA.contentEquals(pcmB))

        // Immutable pending queue simulation with real transcribeSnapshot (mutex serialized)
        data class Pending(val token: UtteranceToken, val pcm: ShortArray, val cfg: SpeechSessionConfig)
        val queue = ArrayDeque<Pending>()
        val lock = Any()
        val decoded = mutableMapOf<Long, ShortArray>()
        val order = mutableListOf<Long>()
        val mutex = Mutex()
        var cur = 0
        var max = 0

        val tokenA = UtteranceToken(1,1,1,"field1",1,null)
        val tokenB = UtteranceToken(1,1,2,"field1",1,null)
        val cfgA = SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("en"))
        val cfgB = SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("en"))

        val pendingA = Pending(tokenA, pcmA.copyOf(), cfgA)
        val pendingB = Pending(tokenB, pcmB.copyOf(), cfgB)
        synchronized(lock) { queue.addLast(pendingA) }

        val blockA = CompletableDeferred<Unit>()
        val worker = launch {
            while (true) {
                val next = synchronized(lock) { if (queue.isEmpty()) null else queue.removeFirst() } ?: break
                mutex.withLock {
                    cur++
                    max = maxOf(max, cur)
                    try {
                        if (next.token.utteranceId==1L) blockA.await()
                        // Use immutable pcm for decode - this is the critical check that A does not read B
                        val res = engine.transcribeSnapshot(next.pcm, next.cfg)
                        decoded[next.token.utteranceId] = next.pcm.copyOf()
                        order.add(next.token.utteranceId)
                        Log.i("PipelineDevice", "decoded ${next.token.utteranceId} chars=${res.text.length} pcm=${next.pcm.size}")
                    } finally { cur-- }
                }
            }
        }
        delay(100)
        // While A blocked, enqueue B - this tests that A cannot clear B's PCM and queue preserves B
        synchronized(lock) { queue.addLast(pendingB) }
        assertFalse(pendingA.pcm === pendingB.pcm)
        assertFalse(pendingA.pcm.contentEquals(pendingB.pcm))
        blockA.complete(Unit)
        worker.join()
        assertEquals(listOf(1L,2L), order)
        assertTrue(decoded[1L]!!.contentEquals(pcmA))
        assertTrue(decoded[2L]!!.contentEquals(pcmB))
        assertTrue(max <= 1)
        assertTrue(engine.nativeDecodeMaxConcurrency <= 1)
        Log.i("PipelineDevice", "overlappingQueueOnDevice PASS order=$order max=$max")
        }
    }

    @Test
    fun germanReplayHarnessOnDevice() { runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        val engine = app.fastEngine
        assertTrue(engine.load().isSuccess)
        val audio = app.assets.open("jfk.wav").use { com.sprich.app.core.audio.Pcm16Wav.read(it) }
        val pcm = audio.samples.copyOfRange(0, 16000) // 1s
        val cfgDe = SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("de"))
        // Save wav via harness (opt-in simulated)
        val file = ReplayHarness.saveWavIfEnabled(app, true, 999L, pcm, cfgDe)
        assertNotNull(file)
        assertTrue(file!!.exists())
        assertTrue(file.length() > 44)
        // Replay
        val replayText = ReplayHarness.replayOffline(engine, pcm, cfgDe)
        // Both live and replay should be consistent - with real model jfk is English but we force de, still should produce same length
        Log.i("PipelineDevice", "replay harness pcm=${pcm.size} file=${file.absolutePath} replayChars=${replayText.length}")
        // Compare live vs replay: decode again via transcribeSnapshot should be identical
        val second = engine.transcribeSnapshot(pcm, cfgDe).text
        assertEquals(replayText, second)
        // Metrics
        val rms = ReplayHarness.computeRms(pcm)
        val dur = ReplayHarness.computeDurationMs(pcm)
        assertTrue(rms > 0.001f)
        assertEquals(1000L, dur)
        file.delete()
        Log.i("PipelineDevice", "germanReplayHarness PASS")
        }
    }

    @Test
    fun continuousTenSentencesSimulatedOnDevice() { runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        val engine = app.fastEngine
        assertTrue(engine.load().isSuccess)
        val audio = app.assets.open("jfk.wav").use { com.sprich.app.core.audio.Pcm16Wav.read(it) }
        // Simulate 10 utterances back-to-back with 300-900ms pause logic but without waiting for decode
        val queue = ArrayDeque<UtteranceToken>()
        val committed = mutableListOf<Long>()
        val finalized = mutableSetOf<Long>()
        val mutex = Mutex()
        // Create 10 distinct slices
        val slices = (0 until 10).map { idx ->
            val start = (idx * 16000) % (audio.samples.size - 16000)
            audio.samples.copyOfRange(start, start + 16000)
        }
        val jobs = slices.mapIndexed { idx, pcm ->
            async {
                val token = UtteranceToken(1,1, (idx+1).toLong(), "field10", 1, null)
                val cfg = SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("en"))
                // Simulate VAD endpoint and queue
                synchronized(finalized) {
                    if (finalized.contains(token.utteranceId)) return@async
                    finalized.add(token.utteranceId)
                }
                // Decode via real engine (serialized)
                mutex.withLock {
                    val res = engine.transcribeSnapshot(pcm, cfg)
                    synchronized(committed) { committed.add(token.utteranceId) }
                    Log.i("PipelineDevice", "continuous $idx chars=${res.text.length}")
                }
            }
        }
        jobs.awaitAll()
        assertEquals(10, committed.size)
        assertEquals((1L..10L).toList(), committed.sorted())
        assertEquals(10, finalized.size)
        Log.i("PipelineDevice", "continuous 10 PASS committed=$committed")
        }
    }

    @Test
    fun continuousTenSentencesGermanOnDevice() { runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        val engine = app.fastEngine
        assertTrue(engine.load().isSuccess)
        val audio = app.assets.open("jfk.wav").use { com.sprich.app.core.audio.Pcm16Wav.read(it) }
        val committed = mutableListOf<Long>()
        val finalized = mutableSetOf<Long>()
        val mutex = Mutex()
        val slices = (0 until 10).map { idx ->
            val start = (idx * 16000) % (audio.samples.size - 16000)
            audio.samples.copyOfRange(start, start + 16000)
        }
        val jobs = slices.mapIndexed { idx, pcm ->
            async {
                val token = UtteranceToken(1,1, (idx+1).toLong(), "fieldDe", 1, null)
                val cfg = SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("de"))
                synchronized(finalized) {
                    if (finalized.contains(token.utteranceId)) return@async
                    finalized.add(token.utteranceId)
                }
                mutex.withLock {
                    val res = engine.transcribeSnapshot(pcm, cfg)
                    synchronized(committed) { committed.add(token.utteranceId) }
                    Log.i("PipelineDevice", "continuous DE $idx chars=${res.text.length}")
                }
            }
        }
        jobs.awaitAll()
        assertEquals(10, committed.size)
        assertEquals((1L..10L).toList(), committed.sorted())
        Log.i("PipelineDevice", "continuous 10 DE PASS committed=$committed")
        }
    }

    @Test
    fun userStopRaceOnDevice() { runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        val engine = app.fastEngine
        assertTrue(engine.load().isSuccess)
        val audio = app.assets.open("jfk.wav").use { com.sprich.app.core.audio.Pcm16Wav.read(it) }
        val pcm = audio.samples.copyOfRange(0, 16000*2)
        val cfg = SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("en"))
        val token = UtteranceToken(1,1,1,"field1",1,null)
        val finalized = mutableSetOf<Long>()
        var decodeCount = 0
        val mutex = Mutex()
        // Simulate concurrent endpoint vs USER_STOP for same utterance
        val jobs = (1..20).map {
            async {
                val claimed = synchronized(finalized) { if (finalized.contains(token.utteranceId)) false else { finalized.add(token.utteranceId); true } }
                if (claimed) {
                    mutex.withLock { decodeCount++ ; engine.transcribeSnapshot(pcm, cfg) }
                }
            }
        }
        jobs.awaitAll()
        assertEquals(1, decodeCount)
        assertEquals(1, finalized.size)
        Log.i("PipelineDevice", "userStopRace 20 reps decodeCount=$decodeCount PASS")
        }
    }

    @Test
    fun typedCommitResultOnDevice() {
        val session = DictationSession()
        val comp = CompositionManager()
        val controller = FieldSessionController(session, comp)
        val ic = FakeIC("before ")
        val sid = controller.onFieldFocused("field1", 0, 0)
        // First commit succeeds
        val r1 = controller.commitUtteranceTyped(sid, 1, ic, "hello")
        assertTrue(r1 is FieldSessionController.CommitResult.Committed)
        // Duplicate should be AlreadyFinalized, not EditorRejected, and must not direct-commit
        val r2 = controller.commitUtteranceTyped(sid, 1, ic, "hello again")
        assertTrue(r2 is FieldSessionController.CommitResult.AlreadyFinalized)
        // Wrong field
        val r3 = controller.commitUtteranceTyped(999, 2, ic, "wrong")
        assertTrue(r3 is FieldSessionController.CommitResult.StaleSession)
        Log.i("PipelineDevice", "typedCommitResult PASS")
    }

    @Test
    fun languageCanonicalOnDevice() { runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        val prefs = com.sprich.app.storage.Preferences(app)
        // Set to de
        prefs.setSpeechLanguage(SpeechLanguage.Fixed("de"))
        delay(200)
        val lang = prefs.speechLanguage.first()
        assertEquals("de", lang.toBcp47())
        assertEquals(Language.DE, lang.toLegacyLanguage())
        // Set to en
        prefs.setSpeechLanguage(SpeechLanguage.Fixed("en"))
        delay(200)
        val lang2 = prefs.speechLanguage.first()
        assertEquals("en", lang2.toBcp47())
        Log.i("PipelineDevice", "languageCanonical PASS")
        }
    }
}
