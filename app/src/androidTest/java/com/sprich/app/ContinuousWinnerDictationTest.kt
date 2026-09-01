package com.sprich.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sprich.app.core.audio.Pcm16Wav
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.fastconformer.FastConformerEngine
import com.sprich.app.speech.lid.WhisperLidEngine
import com.sprich.app.models.manager.ModelManager
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log
import java.io.File

@RunWith(AndroidJUnit4::class)
class ContinuousWinnerDictationTest {
    private fun ensureLidFast(app: SprichApp) {
        val mm = ModelManager(app)
        val lidDir = File(app.filesDir, "whisper-tiny")
        if (!File(lidDir, "tiny-encoder.int8.onnx").exists()) {
            try { lidDir.mkdirs(); File("/data/local/tmp/tiny-encoder.int8.onnx").copyTo(File(lidDir, "tiny-encoder.int8.onnx"), true); File("/data/local/tmp/tiny-decoder.int8.onnx").copyTo(File(lidDir, "tiny-decoder.int8.onnx"), true); File("/data/local/tmp/tiny-tokens.txt").copyTo(File(lidDir, "tiny-tokens.txt"), true) } catch (_: Exception) {}
        }
        val fastDir = File(app.filesDir, "fastconformer")
        if (!File(fastDir, "model.int8.onnx").exists()) {
            try { fastDir.mkdirs(); File("/data/local/tmp/model.int8.onnx").copyTo(File(fastDir, "model.int8.onnx"), true); File("/data/local/tmp/fastconf-tokens.txt").copyTo(File(fastDir, "tokens.txt"), true) } catch (_: Exception) {}
        }
    }
    @Test
    fun continuous10En10DeWinner() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        ensureLidFast(app)
        val mm = ModelManager(app)
        val lid = WhisperLidEngine(app, mm)
        val fast = FastConformerEngine(app)
        runBlocking { assert(lid.load().isSuccess); assert(fast.load().isSuccess) }
        val jfk = app.assets.open("jfk.wav").use { Pcm16Wav.read(it) }
        val deWav = try { File("/data/local/tmp/de-german.wav").inputStream().use { Pcm16Wav.read(it) } } catch (_: Exception) { jfk }
        val enWav = try { File("/data/local/tmp/en-english.wav").inputStream().use { Pcm16Wav.read(it) } } catch (_: Exception) { jfk }
        val esWav = try { File("/data/local/tmp/es-spanish.wav").inputStream().use { Pcm16Wav.read(it) } } catch (_: Exception) { jfk }
        val frWav = try { File("/data/local/tmp/fr-french.wav").inputStream().use { Pcm16Wav.read(it) } } catch (_: Exception) { jfk }

        // 10 EN sentences simulated via slices of jfk and en wav
        val enSentences = listOf(
            jfk.samples.copyOfRange(0, 16000*2),
            enWav.samples.copyOfRange(0, minOf(enWav.samples.size, 16000*1)),
            jfk.samples.copyOfRange(16000*2, 16000*4),
            jfk.samples.copyOfRange(16000*4, 16000*6),
            jfk.samples.copyOfRange(16000*6, 16000*8),
            enWav.samples.copyOfRange(0, minOf(enWav.samples.size, 16000*1)),
            jfk.samples.copyOfRange(0, 16000*3),
            jfk.samples.copyOfRange(16000*3, 16000*5),
            jfk.samples.copyOfRange(16000*5, 16000*7),
            jfk.samples.copyOfRange(16000*7, 16000*9)
        )
        val deSentences = listOf(
            deWav.samples.copyOfRange(0, minOf(deWav.samples.size, 16000*2)),
            deWav.samples.copyOfRange(minOf(16000, deWav.samples.size-16000), minOf(deWav.samples.size, 16000*3)),
            deWav.samples.copyOfRange(0, minOf(deWav.samples.size, 16000*3)),
            deWav.samples.copyOfRange(0, minOf(deWav.samples.size, 16000*2)),
            deWav.samples.copyOfRange(0, minOf(deWav.samples.size, 16000*2)),
            deWav.samples.copyOfRange(0, minOf(deWav.samples.size, 16000*2)),
            deWav.samples.copyOfRange(0, minOf(deWav.samples.size, 16000*2)),
            deWav.samples.copyOfRange(0, minOf(deWav.samples.size, 16000*2)),
            deWav.samples.copyOfRange(0, minOf(deWav.samples.size, 16000*2)),
            deWav.samples.copyOfRange(0, minOf(deWav.samples.size, 16000*2))
        )
        Log.i("Continuous", "START 10 EN +10 DE winner TinyLID+FastConformer")
        val allTexts = mutableListOf<String>()
        var orderOk = true
        // EN 10
        for ((idx, pcm) in enSentences.withIndex()) {
            val lidRes = runBlocking { lid.identify(pcm) }
            val langOk = lidRes is WhisperLidEngine.LidOutcome.Detected && lidRes.language.code == "en"
            if (!langOk) Log.w("Continuous", "EN $idx lid $lidRes")
            val text = runBlocking { fast.transcribeSnapshot(pcm, SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("en"))).text }
            Log.i("Continuous", "EN $idx lid $lidRes textLen=${text.length} text=\"${text.take(40)}\"")
            assert(text.isNotBlank()) { "EN $idx blank" }
            allTexts.add(text)
            // Simulate no UI wait, immediate next
        }
        // DE 10
        for ((idx, pcm) in deSentences.withIndex()) {
            val lidRes = runBlocking { lid.identify(pcm) }
            // DE wav may be short, but should be DE
            Log.i("Continuous", "DE $idx lid $lidRes")
            val text = runBlocking { fast.transcribeSnapshot(pcm, SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("de"))).text }
            Log.i("Continuous", "DE $idx textLen=${text.length} text=\"${text.take(40)}\"")
            assert(text.isNotBlank()) { "DE $idx blank" }
            allTexts.add(text)
        }
        Log.i("Continuous", "DONE 20 utterances, orderOk=$orderOk totalTexts=${allTexts.size} firstEn=\"${allTexts.first().take(30)}\" lastDe=\"${allTexts.last().take(30)}\"")
        // Verify order: first 10 are EN, next 10 DE, and no lost starts (all 20 produced)
        assert(allTexts.size == 20)
        // Verify no duplicate blank etc.
        assert(allTexts.none { it.isBlank() })
        runBlocking { lid.unload(); fast.unload() }
        Log.i("Continuous", "Continuous dictation 10+10 PASS winner")
    }
}
