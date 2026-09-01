package com.sprich.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sprich.app.core.audio.Pcm16Wav
import com.sprich.app.speech.fastconformer.FastConformerEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.File

@RunWith(AndroidJUnit4::class)
class FastConformerDeviceTest {
    private fun ensureFastConformerFiles(app: SprichApp) {
        val destDir = File(app.filesDir, "fastconformer")
        val model = File(destDir, "model.int8.onnx")
        val tokens = File(destDir, "tokens.txt")
        if (model.exists() && model.length() > 50_000_000 && tokens.exists()) return
        try {
            destDir.mkdirs()
            File("/data/local/tmp/model.int8.onnx").copyTo(model, overwrite = true)
            File("/data/local/tmp/fastconf-tokens.txt").copyTo(tokens, overwrite = true)
            // Fallback to old path if needed
            if (!tokens.exists() || tokens.length() < 1000) {
                File("/data/local/tmp/tokens.txt").copyTo(tokens, overwrite = true)
            }
        } catch (e: Exception) { android.util.Log.w("FastConfDevice", "copy failed", e) }
    }
    private fun readWav(path: String): Pcm16Wav.Audio {
        return File(path).inputStream().use { Pcm16Wav.read(it) }
    }
    @Test
    fun fastConformerLoadAndJfkBenchmark() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        ensureFastConformerFiles(app)
        // Free Canary first to avoid OOM (both 126M+198M)
        runBlocking { try { app.fastEngine.unload() } catch (_:Exception) {} }
        Thread.sleep(500)
        val engine = FastConformerEngine(app)
        val load = runBlocking { engine.load() }
        Log.i("FastConfDevice", "load $load success=${load.isSuccess} err=${load.exceptionOrNull()?.message}")
        assertTrue("load failed ${load.exceptionOrNull()}", load.isSuccess)
        // JFK
        val audio = app.assets.open("jfk.wav").use { Pcm16Wav.read(it) }
        val t0 = System.nanoTime()
        val text = runBlocking {
            engine.beginSession(com.sprich.app.speech.api.SpeechSessionConfig())
            engine.pushAudio(audio.samples, System.nanoTime())
            engine.endUtterance().text
        }
        val ms = (System.nanoTime()-t0)/1_000_000
        val rtf = ms / (audio.samples.size*1000.0/audio.sampleRate)
        Log.i("FastConfDevice", "jfk ms=$ms rtf=$rtf textLen=${text.length} text=\"${text.take(80)}\"")
        assertTrue(text.isNotBlank())
        // Check punctuation/capitalization: CTC may be lower-case without punctuation - log it
        Log.i("FastConfDevice", "jfk raw text: $text")
        // Memory
        val pss = try { val am = app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager; val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi); mi.totalMem } catch (_:Exception){0L}
        Log.i("FastConfDevice", "pssTotal $pss")
    }

    @Test
    fun fastConformerMultilingualWavs() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        ensureFastConformerFiles(app)
        runBlocking { try { app.fastEngine.unload() } catch (_:Exception) {} }
        Thread.sleep(300)
        val engine = FastConformerEngine(app)
        assertTrue(runBlocking { engine.load() }.isSuccess)
        val files = listOf(
            "/data/local/tmp/en-english.wav" to "en",
            "/data/local/tmp/de-german.wav" to "de",
            "/data/local/tmp/es-spanish.wav" to "es",
            "/data/local/tmp/fr-french.wav" to "fr"
        )
        for ((path, lang) in files) {
            val audio = try { readWav(path) } catch (e: Exception) { Log.w("FastConfDevice","skip $path $e"); continue }
            val text = runBlocking {
                engine.beginSession(com.sprich.app.speech.api.SpeechSessionConfig())
                engine.pushAudio(audio.samples, System.nanoTime())
                engine.endUtterance().text
            }
            Log.i("FastConfDevice", "lang $lang file $path samples=${audio.samples.size} textLen=${text.length} text=\"${text.take(60)}\"")
            assertTrue("blank for $lang", text.isNotBlank())
        }
    }

    @Test
    fun fastConformerRtfAndMemory() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        ensureFastConformerFiles(app)
        runBlocking { try { app.fastEngine.unload() } catch (_:Exception) {} }
        Thread.sleep(300)
        val engine = FastConformerEngine(app)
        runBlocking { engine.load() }
        val audio = app.assets.open("jfk.wav").use { Pcm16Wav.read(it) }
        repeat(3) {
            val t = System.nanoTime()
            runBlocking {
                engine.beginSession(com.sprich.app.speech.api.SpeechSessionConfig())
                engine.pushAudio(audio.samples, System.nanoTime())
                engine.endUtterance()
            }
            val ms = (System.nanoTime()-t)/1_000_000
            Log.i("FastConfDevice", "warm $it ms=$ms")
        }
        // Dump meminfo
        try {
            val proc = Runtime.getRuntime().exec("dumpsys meminfo com.sprich.app.debug")
            val out = proc.inputStream.bufferedReader().readText()
            Log.i("FastConfDevice", "meminfo ${out.take(500)}")
        } catch (_: Exception) {}
    }
}
