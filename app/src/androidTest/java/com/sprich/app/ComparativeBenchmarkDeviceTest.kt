package com.sprich.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sprich.app.core.audio.Pcm16Wav
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.api.TranscriptionTask
import com.sprich.app.speech.fastconformer.FastConformerEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log
import java.io.File

@RunWith(AndroidJUnit4::class)
class ComparativeBenchmarkDeviceTest {
    private fun ensureFastConformerFiles(app: SprichApp) {
        val destDir = File(app.filesDir, "fastconformer")
        val model = File(destDir, "model.int8.onnx")
        val tokens = File(destDir, "tokens.txt")
        if (model.exists() && model.length() > 50_000_000 && tokens.exists()) return
        try {
            destDir.mkdirs()
            File("/data/local/tmp/model.int8.onnx").copyTo(model, overwrite = true)
            File("/data/local/tmp/fastconf-tokens.txt").copyTo(tokens, overwrite = true)
            if (!tokens.exists() || tokens.length() < 1000) File("/data/local/tmp/tokens.txt").copyTo(tokens, overwrite = true)
        } catch (e: Exception) { Log.w("Comparative", "copy fastconformer failed", e) }
    }

    @Test
    fun compareCanaryAndFastConformerOnSameCorpus() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        // Ensure canary files
        val canaryDir = File(app.filesDir, "canary")
        if (!File(canaryDir, "encoder.int8.onnx").exists()) {
            try {
                canaryDir.mkdirs()
                File("/data/local/tmp/canary-encoder.int8.onnx").copyTo(File(canaryDir, "encoder.int8.onnx"), overwrite = true)
                File("/data/local/tmp/canary-decoder.int8.onnx").copyTo(File(canaryDir, "decoder.int8.onnx"), overwrite = true)
                File("/data/local/tmp/canary-tokens.txt").copyTo(File(canaryDir, "tokens.txt"), overwrite = true)
            } catch (e: Exception) { Log.w("Comparative", "canary copy failed", e) }
        }
        ensureFastConformerFiles(app)
        val canary = app.fastEngine
        val fast = FastConformerEngine(app)

        // Unload canary before loading fast to avoid OOM, but we need both sequentially
        runBlocking { canary.unload() }
        Thread.sleep(500)
        val fastLoad = runBlocking { fast.load() }
        Log.i("Comparative", "fast load ${fastLoad.isSuccess} err=${fastLoad.exceptionOrNull()?.message}")
        org.junit.Assert.assertTrue(fastLoad.isSuccess)

        val jfk = app.assets.open("jfk.wav").use { Pcm16Wav.read(it) }
        // FastConformer JFK
        var fastJfkText = ""
        var fastMs = 0L
        runBlocking {
            fast.beginSession(SpeechSessionConfig())
            fast.pushAudio(jfk.samples, System.nanoTime())
            val t0 = System.nanoTime()
            fastJfkText = fast.endUtterance().text
            fastMs = (System.nanoTime()-t0)/1_000_000
        }
        val fastRtf = fastMs / (jfk.samples.size*1000.0/jfk.sampleRate)
        Log.i("Comparative", "FastConformer JFK ms=$fastMs rtf=$fastRtf textLen=${fastJfkText.length} text=\"${fastJfkText.take(100)}\"")

        // Now unload fast, load canary
        runBlocking { fast.unload() }
        Thread.sleep(500)
        val canaryLoad = runBlocking { canary.load() }
        Log.i("Comparative", "canary load ${canaryLoad.isSuccess}")
        var canaryJfkText = ""
        var canaryMs = 0L
        runBlocking {
            canary.beginSession(SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("en"), task = TranscriptionTask.TRANSCRIBE))
            canary.pushAudio(jfk.samples, System.nanoTime())
            val t0 = System.nanoTime()
            canaryJfkText = canary.endUtterance().text
            canaryMs = (System.nanoTime()-t0)/1_000_000
        }
        val canaryRtf = canaryMs / (jfk.samples.size*1000.0/jfk.sampleRate)
        Log.i("Comparative", "Canary JFK ms=$canaryMs rtf=$canaryRtf textLen=${canaryJfkText.length} text=\"${canaryJfkText.take(100)}\"")

        // Multilingual wavs via both
        val wavs = listOf(
            "/data/local/tmp/en-english.wav" to "en",
            "/data/local/tmp/de-german.wav" to "de",
            "/data/local/tmp/es-spanish.wav" to "es",
            "/data/local/tmp/fr-french.wav" to "fr"
        )
        for ((path, lang) in wavs) {
            val audio = try { File(path).inputStream().use { Pcm16Wav.read(it) } } catch (e: Exception) { Log.w("Comparative","skip $path $e"); continue }
            // FastConformer (implicit)
            runBlocking { fast.load() }
            var fastText = ""
            runBlocking {
                fast.beginSession(SpeechSessionConfig())
                fast.pushAudio(audio.samples, System.nanoTime())
                fastText = fast.endUtterance().text
            }
            runBlocking { fast.unload() }
            Thread.sleep(200)
            // Canary explicit
            runBlocking { canary.load() }
            var canaryText = ""
            runBlocking {
                val cfg = SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed(lang), task = TranscriptionTask.TRANSCRIBE)
                canary.beginSession(cfg)
                canary.pushAudio(audio.samples, System.nanoTime())
                canaryText = canary.endUtterance().text
            }
            runBlocking { canary.unload() }
            Thread.sleep(200)
            Log.i("Comparative", "lang $lang fastLen=${fastText.length} canaryLen=${canaryText.length} fast=\"${fastText.take(50)}\" canary=\"${canaryText.take(50)}\"")
            // Reload fast for next iteration
            runBlocking { fast.load() }
            runBlocking { fast.unload() }
        }

        // Memory after
        try {
            val proc = Runtime.getRuntime().exec("dumpsys meminfo com.sprich.app.debug")
            val out = proc.inputStream.bufferedReader().readText()
            Log.i("Comparative", "meminfo ${out.take(800)}")
        } catch (_: Exception) {}
    }
}
