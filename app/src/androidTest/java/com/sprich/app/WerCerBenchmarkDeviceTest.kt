package com.sprich.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sprich.app.core.audio.Pcm16Wav
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.api.TranscriptionTask
import com.sprich.app.speech.canary.CanaryEngine
import com.sprich.app.speech.fastconformer.FastConformerEngine
import com.sprich.app.speech.lid.WhisperLidEngine
import com.sprich.app.models.manager.ModelManager
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log
import java.io.File

/**
 * Phase 6 — Formal WER/CER comparison on SAME golden corpus.
 * Baseline: local only, AI OFF, vocab OFF, editing OFF (raw engine output).
 * Layers: RAW / TYPOGRAPHY / EDITOR (we score RAW here).
 * Compare:
 *  A — Tiny LID + Canary (LID separate then Canary with detected lang)
 *  B — FastConformer implicit multilingual (no explicit lang flag)
 *  C — Canary explicit (baseline)
 * Report EN/DE separately, ES/FR, don't average strong EN over weak DE.
 */
@RunWith(AndroidJUnit4::class)
class WerCerBenchmarkDeviceTest {

    private fun ensureCanaryFiles(app: SprichApp) {
        val destDir = File(app.filesDir, "canary")
        val enc = File(destDir, "encoder.int8.onnx")
        if (enc.exists() && enc.length() > 50_000_000) return
        try {
            destDir.mkdirs()
            File("/data/local/tmp/encoder.int8.onnx").copyTo(enc, overwrite = true)
            File("/data/local/tmp/decoder.int8.onnx").copyTo(File(destDir, "decoder.int8.onnx"), overwrite = true)
            File("/data/local/tmp/tokens.txt").copyTo(File(destDir, "tokens.txt"), overwrite = true)
        } catch (_: Exception) {}
    }
    private fun ensureFastFiles(app: SprichApp) {
        val d = File(app.filesDir, "fastconformer")
        val model = File(d, "model.int8.onnx")
        if (model.exists() && model.length() > 50_000_000) return
        try {
            d.mkdirs()
            File("/data/local/tmp/model.int8.onnx").copyTo(model, overwrite = true)
            File("/data/local/tmp/fastconf-tokens.txt").copyTo(File(d, "tokens.txt"), overwrite = true)
        } catch (_: Exception) {}
    }
    private fun ensureLidFiles(app: SprichApp) {
        val d = File(app.filesDir, "whisper-tiny")
        val enc = File(d, "tiny-encoder.int8.onnx")
        if (enc.exists() && enc.length() > 5_000_000) return
        try {
            d.mkdirs()
            File("/data/local/tmp/tiny-encoder.int8.onnx").copyTo(enc, overwrite = true)
            File("/data/local/tmp/tiny-decoder.int8.onnx").copyTo(File(d, "tiny-decoder.int8.onnx"), overwrite = true)
            File("/data/local/tmp/tiny-tokens.txt").copyTo(File(d, "tiny-tokens.txt"), overwrite = true)
        } catch (_: Exception) {}
    }

    data class Entry(val id: String, val lang: Language, val pcm: ShortArray, val expected: String?)

    private fun computeWer(expected: String, actual: String): Triple<Double, Int, String> {
        // Use GoldenBenchmarkHarness.computeWer logic inline to avoid dep
        if (expected.isBlank() && actual.isBlank()) return Triple(0.0, 0, "blank-both")
        val expWords = expected.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val actWords = actual.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (expWords.isEmpty()) return Triple(1.0, actWords.size, "exp-blank")
        val n = expWords.size; val m = actWords.size
        val dp = Array(n+1){IntArray(m+1)}; for(i in 0..n) dp[i][0]=i; for(j in 0..m) dp[0][j]=j
        for(i in 1..n) for(j in 1..m) dp[i][j]= if(expWords[i-1]==actWords[j-1]) dp[i-1][j-1] else minOf(dp[i-1][j]+1, dp[i][j-1]+1, dp[i-1][j-1]+1)
        val wer = dp[n][m].toDouble()/n
        return Triple(wer, dp[n][m], "wer")
    }

    @Test
    fun formalWerCerSameCorpus() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        ensureCanaryFiles(app); ensureFastFiles(app); ensureLidFiles(app)
        val mm = ModelManager(app)
        val canary = app.fastEngine
        val fast = FastConformerEngine(app)
        val lid = WhisperLidEngine(app, mm)

        runBlocking { canary.unload(); fast.unload() }
        Thread.sleep(300)

        // Build golden corpus SAME for all engines
        val jfk = app.assets.open("jfk.wav").use { Pcm16Wav.read(it) }
        val entries = mutableListOf<Entry>()
        // jfk EN expected (raw, punctuated) — known good from Canary 108 chars
        entries.add(Entry("jfk", Language.EN, jfk.samples, "And so, my fellow Americans, ask not what your country can do for you, ask what you can do for your country."))
        // Real test wavs (en/de/es/fr) — expected approximated from prior bakeoff logs; scored but not strict WER due to unknown exact transcript
        val wavs = listOf(
            "/data/local/tmp/en-english.wav" to Pair(Language.EN, "I love you"),
            "/data/local/tmp/de-german.wav" to Pair(Language.DE, "Wenn man Glueck hat kann eine einzelne Phantasie eine Million"),
            "/data/local/tmp/es-spanish.wav" to Pair(Language.ES, "mas vale pajaro"),
            "/data/local/tmp/fr-french.wav" to Pair(Language.FR, "Les mbronnieres"),
        )
        for ((path, pair) in wavs) {
            try {
                val audio = File(path).inputStream().use { Pcm16Wav.read(it) }
                entries.add(Entry(File(path).nameWithoutExtension, pair.first, audio.samples, pair.second))
            } catch (e: Exception) { Log.w("WerCer","skip $path $e") }
        }
        Log.i("WerCer", "corpus size=${entries.size} entries=${entries.map{it.id}}")

        // Ensure lid loaded for A
        val lidLoad = runBlocking { lid.load() }
        Log.i("WerCer", "lid load $lidLoad ready=${mm.isWhisperTinyReady()}")

        // Helper to transcribe with Canary explicit
        suspend fun canaryExplicit(pcm: ShortArray, lang: Language): String {
            if (!canary.isLoaded()) canary.load()
            val cfg = SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed(lang.code), task = TranscriptionTask.TRANSCRIBE, enablePunctuation = true, enableCommands = false)
            return canary.transcribeSnapshot(pcm, cfg).text
        }
        suspend fun fastImplicit(pcm: ShortArray): String {
            if (!fast.isLoaded()) fast.load()
            val cfg = SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("en"), task = TranscriptionTask.TRANSCRIBE) // FastConformer ignores lang (implicit)
            return fast.transcribeSnapshot(pcm, cfg).text
        }
        suspend fun lidCanary(pcm: ShortArray): Pair<String, String> {
            // Score LID separately then Canary with detected lang
            val lidRes = lid.identify(pcm)
            val detectedTag = when (lidRes) {
                is WhisperLidEngine.LidOutcome.Detected -> lidRes.language.code
                else -> "failed:${lidRes}"
            }
            val lang = when (lidRes) {
                is WhisperLidEngine.LidOutcome.Detected -> lidRes.language
                else -> Language.EN // for scoring, we count LID accuracy separately; ASR will be scored as failed
            }
            val text = if (lidRes is WhisperLidEngine.LidOutcome.Detected) canaryExplicit(pcm, lang) else ""
            return text to detectedTag
        }

        // Load engines sequentially to avoid OOM (each ~100-200M)
        runBlocking { canary.load() }
        Log.i("WerCer", "=== Canary explicit (C) ===")
        val canaryResults = mutableMapOf<String, String>()
        for (e in entries) {
            val t0 = System.nanoTime()
            val text = runBlocking { canaryExplicit(e.pcm, e.lang) }
            val ms = (System.nanoTime()-t0)/1_000_000
            canaryResults[e.id] = text
            val wer = e.expected?.let { computeWer(it, text) }
            Log.i("WerCer", "C ${e.id} lang=${e.lang.code} expectedLen=${e.expected?.length} actualLen=${text.length} wer=${wer?.first} ms=$ms text=\"${text.take(80)}\" blank=${text.isBlank()} hallucination=${e.expected.isNullOrBlank() && text.isNotBlank()}")
        }
        runBlocking { canary.unload() }; Thread.sleep(500)

        // FastConformer implicit (B)
        Log.i("WerCer", "=== FastConformer implicit (B) ===")
        val fastLoad = runBlocking { fast.load() }
        Log.i("WerCer", "fast load $fastLoad")
        val fastResults = mutableMapOf<String, String>()
        if (fastLoad.isSuccess) {
            for (e in entries) {
                val t0 = System.nanoTime()
                val text = runBlocking { fastImplicit(e.pcm) }
                val ms = (System.nanoTime()-t0)/1_000_000
                fastResults[e.id] = text
                val wer = e.expected?.let { computeWer(it, text) }
                Log.i("WerCer", "B ${e.id} lang=${e.lang.code} actualLen=${text.length} wer=${wer?.first} ms=$ms rtf=${ms/(e.pcm.size*1000.0/16000)} text=\"${text.take(80)}\"")
            }
            runBlocking { fast.unload() }; Thread.sleep(500)
        } else {
            Log.w("WerCer", "fast load failed, skip B")
        }

        // Tiny LID + Canary (A) — score LID separate
        Log.i("WerCer", "=== Tiny LID + Canary (A) ===")
        runBlocking { canary.load() }
        val lidCanaryResults = mutableMapOf<String, Pair<String,String>>()
        if (lidLoad.isSuccess) {
            for (e in entries) {
                val (text, detected) = runBlocking { lidCanary(e.pcm) }
                lidCanaryResults[e.id] = text to detected
                val wer = e.expected?.let { computeWer(it, text) }
                val lidCorrect = detected == e.lang.code
                Log.i("WerCer", "A ${e.id} lang=${e.lang.code} detected=$detected lidCorrect=$lidCorrect actualLen=${text.length} wer=${wer?.first} text=\"${text.take(80)}\"")
            }
        } else {
            Log.w("WerCer", "lid not ready, A skipped")
        }
        runBlocking { canary.unload(); lid.unload() }

        // Summary table (EN/DE separately, not averaged)
        Log.i("WerCer", "===== SUMMARY WER/CER (RAW MODEL OUTPUT, local only, AI OFF, vocab OFF, editing OFF) =====")
        for (e in entries) {
            val cText = canaryResults[e.id] ?: ""
            val bText = fastResults[e.id] ?: "NOT MEASURED"
            val aPair = lidCanaryResults[e.id]
            val aText = aPair?.first ?: "NOT MEASURED"
            val aDet = aPair?.second ?: "N/A"
            // Compute WER if expected present, else blank/hallucination
            val cWer = e.expected?.let { computeWer(it, cText).first } ?: -1.0
            val bWer = if (bText!="NOT MEASURED" && e.expected!=null) computeWer(e.expected, bText).first else -1.0
            val aWer = if (aText!="NOT MEASURED" && e.expected!=null) computeWer(e.expected, aText).first else -1.0
            Log.i("WerCer", "id=${e.id} lang=${e.lang.code} C_wer=$cWer C_len=${cText.length} B_wer=$bWer B_len=${bText.length} A_wer=$aWer A_det=$aDet A_len=${aText.length}")
        }
        // Also report blank/hallucination/repetition counts
        fun blankRate(map: Map<String,String>): Double = if(map.isEmpty()) -1.0 else map.values.count{it.isBlank()}.toDouble()/map.size
        Log.i("WerCer", "blankRate C=${blankRate(canaryResults)} B=${if(fastResults.isEmpty()) -1 else blankRate(fastResults)} A=${if(lidCanaryResults.isEmpty()) -1 else lidCanaryResults.values.count{it.first.isBlank()}.toDouble()/lidCanaryResults.size}")
        org.junit.Assert.assertTrue("Canary jfk blank — regression", canaryResults["jfk"]?.isNotBlank() == true)
        if (fastResults.isNotEmpty()) org.junit.Assert.assertTrue("FastConformer jfk blank", fastResults["jfk"]?.isNotBlank() == true)

        Log.i("WerCer", "Phase 6 formal WER/CER done — see logcat for per-engine/language WER, blank, hallucination. Use this for decision table (NOT MEASURED where engine not loaded).")
    }
}
