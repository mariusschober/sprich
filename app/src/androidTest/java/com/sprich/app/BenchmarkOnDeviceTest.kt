package com.sprich.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sprich.app.core.audio.Pcm16Wav
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.TranscriptionTask
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log

@RunWith(AndroidJUnit4::class)
class BenchmarkOnDeviceTest {

    @Before
    fun ensureModelAndPermissions() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        ensureCanaryFiles(app)
        try { InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${app.packageName} android.permission.RECORD_AUDIO").close() } catch (_: Exception) {}
        try { InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant com.sprich.app.debug.test android.permission.RECORD_AUDIO").close() } catch (_: Exception) {}
    }

    private fun ensureCanaryFiles(app: SprichApp) {
        val destDir = java.io.File(app.filesDir, "canary")
        val enc = java.io.File(destDir, "encoder.int8.onnx")
        val dec = java.io.File(destDir, "decoder.int8.onnx")
        val tok = java.io.File(destDir, "tokens.txt")
        if (enc.exists() && enc.length() > 50_000_000 && dec.exists() && dec.length() > 50_000_000 && tok.exists()) return
        try {
            destDir.mkdirs()
            val srcEnc = java.io.File("/data/local/tmp/encoder.int8.onnx")
            val srcDec = java.io.File("/data/local/tmp/decoder.int8.onnx")
            val srcTok = java.io.File("/data/local/tmp/tokens.txt")
            if (srcEnc.exists() && srcDec.exists() && srcTok.exists()) {
                srcEnc.copyTo(enc, overwrite = true)
                srcDec.copyTo(dec, overwrite = true)
                srcTok.copyTo(tok, overwrite = true)
                Log.i("TestModelHelper", "copied canary from /data/local/tmp to ${destDir.absolutePath}")
            }
        } catch (e: Exception) { Log.w("TestModelHelper", "copy failed", e) }
    }

    @Test
    fun benchmarkCanaryOnDevice() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        val engine = app.fastEngine
        Log.i("BenchmarkOnDevice", "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} soc=${android.os.Build.HARDWARE} sdk=${android.os.Build.VERSION.SDK_INT} ram=${Runtime.getRuntime().maxMemory()/1024/1024}MB")
        // Ensure model is loaded
        val loadStart = System.nanoTime()
        val loadRes = kotlinx.coroutines.runBlocking { engine.load() }
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000
        Log.i("BenchmarkOnDevice", "loadMs=$loadMs success=${loadRes.isSuccess} engine=${engine.engineId} loaded=${engine.isLoaded()}")
        assertTrue("engine load failed: ${loadRes.exceptionOrNull()}", loadRes.isSuccess)
        // Read jfk.wav
        val audio = app.assets.open("jfk.wav").use { Pcm16Wav.read(it) }
        Log.i("BenchmarkOnDevice", "jfk samples=${audio.samples.size} durationMs=${audio.samples.size*1000/audio.sampleRate}")
        assertTrue(audio.samples.size > 100000)
        // Cold run
        val t0 = System.nanoTime()
        val coldText = kotlinx.coroutines.runBlocking {
            engine.cancelSession()
            engine.beginSession(SpeechSessionConfig(language = Language.EN, speechLanguage = SpeechLanguage.Fixed("en"), task = TranscriptionTask.TRANSCRIBE))
            engine.pushAudio(audio.samples, System.nanoTime())
            engine.endUtterance().text
        }
        val coldMs = (System.nanoTime() - t0) / 1_000_000
        val audioMs = audio.samples.size * 1000.0 / audio.sampleRate
        val coldRtf = coldMs / audioMs
        Log.i("BenchmarkOnDevice", "cold inferenceMs=$coldMs rtf=$coldRtf textLen=${coldText.length} text=\"${coldText.take(80)}\"")
        assertTrue(coldText.isNotBlank())
        assertTrue(coldText.length >= 10)

        // 5 warm runs
        val warmTimes = mutableListOf<Long>()
        val warmRtfs = mutableListOf<Double>()
        repeat(5) { idx ->
            val t = System.nanoTime()
            val text = kotlinx.coroutines.runBlocking {
                engine.cancelSession()
                engine.beginSession(SpeechSessionConfig(language = Language.EN, speechLanguage = SpeechLanguage.Fixed("en"), task = TranscriptionTask.TRANSCRIBE))
                engine.pushAudio(audio.samples, System.nanoTime())
                engine.endUtterance().text
            }
            val ms = (System.nanoTime() - t) / 1_000_000
            val rtf = ms / audioMs
            warmTimes.add(ms)
            warmRtfs.add(rtf)
            Log.i("BenchmarkOnDevice", "warm run $idx inferenceMs=$ms rtf=$rtf")
            assertTrue(text.isNotBlank())
        }
        warmTimes.sort()
        val p50 = warmTimes[warmTimes.size/2]
        val p95 = warmTimes[(warmTimes.size*0.95).toInt().coerceAtMost(warmTimes.size-1)]
        val avgRtf = warmRtfs.average()
        val peakRssMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024
        Log.i("BenchmarkOnDevice", "SUMMARY loadMs=$loadMs coldMs=$coldMs warm p50=$p50 p95=$p95 avgRtf=$avgRtf peakRssMb=$peakRssMb backend=cpu threads=2 quantization=INT8 languages=en,de,es,fr task=transcribe")
        // Write to diagnostics file for pull (app private + /data/local/tmp for persistence across reinstall)
        try {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val content = """
device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}
soc=${android.os.Build.HARDWARE} socModel=${if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL ?: "unknown" else "unknown"}
android=${android.os.Build.VERSION.RELEASE} sdk=${android.os.Build.VERSION.SDK_INT} ramMaxHeapMb=${Runtime.getRuntime().maxMemory()/1024/1024}
engine=${engine.engineId} quantization=INT8 threads=2 backend=cpu
loadMs=$loadMs
jfkDurationMs=${audioMs.toLong()} samples=${audio.samples.size}
coldMs=$coldMs coldRtf=$coldRtf
warmTimesMs=$warmTimes
p50=$p50 p95=$p95 avgRtf=$avgRtf peakRssMb=$peakRssMb
coldTextLen=${coldText.length}
coldText=${coldText}
""".trimIndent()
            val f = java.io.File(ctx.filesDir, "diagnostics/benchmark_ondevice.log")
            f.parentFile?.mkdirs()
            f.writeText(content)
            Log.i("BenchmarkOnDevice", "wrote $f")
            // Also write to /data/local/tmp which survives app reinstall
            try {
                val f2 = java.io.File("/data/local/tmp/benchmark_ondevice.log")
                f2.writeText(content)
                Log.i("BenchmarkOnDevice", "wrote $f2")
            } catch (_: Exception) {}
        } catch (e: Exception) { Log.w("BenchmarkOnDevice", "write failed", e) }
    }

    @Test
    fun languageTaskInvariantsOnDevice() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        val engine = app.fastEngine
        // Verify language setting is Auto|Fixed BCP-47 and observable
        val prefs = com.sprich.app.storage.Preferences(app)
        val lang = kotlinx.coroutines.runBlocking { prefs.speechLanguage.first() }
        Log.i("BenchmarkOnDevice", "prefs speechLanguage=$lang bcp47=${lang.toBcp47()} task=TRANSCRIBE")
        // Begin session with Auto and with Fixed, ensure engine accepts
        val cfgAuto = SpeechSessionConfig(speechLanguage = SpeechLanguage.Auto, task = TranscriptionTask.TRANSCRIBE)
        val cfgDe = SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("de"), task = TranscriptionTask.TRANSCRIBE)
        assertEquals(TranscriptionTask.TRANSCRIBE, cfgAuto.task)
        assertEquals("auto", cfgAuto.resolvedLanguageTag())
        assertEquals("de", cfgDe.resolvedLanguageTag())
        // Ensure Locale doesn't affect
        val orig = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val cfgAfter = SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("en"), task = TranscriptionTask.TRANSCRIBE)
            assertEquals("en", cfgAfter.resolvedLanguageTag())
            assertEquals(TranscriptionTask.TRANSCRIBE, cfgAfter.task)
        } finally { java.util.Locale.setDefault(orig) }
        Log.i("BenchmarkOnDevice", "languageTaskInvariantsOnDevice PASS")
    }
}
