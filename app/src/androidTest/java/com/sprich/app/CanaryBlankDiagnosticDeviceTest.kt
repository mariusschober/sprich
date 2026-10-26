package com.sprich.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.sprich.app.core.audio.Pcm16Wav
import com.sprich.app.models.manager.ModelManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/**
 * R1 diagnostic for the remaining blank English clips. Not a gate; writes variants so the host
 * side can choose a deterministic fix (gain, forced split, language switch) instead of guessing.
 */
@RunWith(AndroidJUnit4::class)
class CanaryBlankDiagnosticDeviceTest {

    private fun app() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp

    private fun recognizer(dir: File): OfflineRecognizer = OfflineRecognizer(
        null,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(16000, 80, 0.0f),
            modelConfig = OfflineModelConfig(
                canary = OfflineCanaryModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").absolutePath,
                    decoder = File(dir, "decoder.int8.onnx").absolutePath,
                    srcLang = "en", tgtLang = "en", usePnc = true,
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = 2, provider = "cpu", debug = false,
            ),
        ),
    )

    private fun decode(rec: OfflineRecognizer, pcm: ShortArray): String {
        val stream = rec.createStream()
        return try {
            stream.acceptWaveform(FloatArray(pcm.size) { pcm[it] / 32768f }, 16000)
            rec.decode(stream)
            rec.getResult(stream).text.trim()
        } finally { stream.release() }
    }

    private fun gain(pcm: ShortArray, targetRms: Double): ShortArray {
        var sum = 0.0
        for (s in pcm) { val f = s / 32768.0; sum += f * f }
        val rms = sqrt(sum / pcm.size)
        if (rms <= 1e-6) return pcm.copyOf()
        val factor = (targetRms / rms).coerceAtMost(40.0)
        return ShortArray(pcm.size) { (pcm[it] * factor).coerceIn(-32768.0, 32767.0).toInt().toShort() }
    }

    @Test
    fun diagnosePreviouslyBlankClips() = runBlocking {
        val app = app()
        val mm = ModelManager(app)
        assumeTrue("canary missing", mm.verifyInstalled("accurate"))
        val dir = mm.canaryDir() ?: return@runBlocking
        val ids = listOf(
            "en_us-156130586016414879", "en_us-2939761992885557861",
            "en_us-1277588711703410007", "en_us-9431561488681290113",
        )
        val manifest = JSONObject(File(File(app.filesDir, "bakeoff"), "manifest.json").readText())
        val clips = manifest.getJSONArray("clips")
        val rec = recognizer(dir)
        val out = JSONArray()
        try {
            for (i in 0 until clips.length()) {
                val clip = clips.getJSONObject(i)
                if (clip.getString("id") !in ids) continue
                val pcm = File(File(app.filesDir, "bakeoff/wav"), clip.getString("audio"))
                    .inputStream().use { Pcm16Wav.read(it).samples }
                var sum = 0.0
                for (s in pcm) { val f = s / 32768.0; sum += f * f }
                val mid = pcm.size / 2
                val variants = linkedMapOf(
                    "original" to decode(rec, pcm),
                    "gain10x" to decode(rec, gain(pcm, 0.10)),
                    "firstHalf" to decode(rec, pcm.copyOfRange(0, mid)),
                    "secondHalf" to decode(rec, pcm.copyOfRange(mid, pcm.size)),
                    "twoSegments" to (decode(rec, pcm.copyOfRange(0, mid)).trim() + " " +
                        decode(rec, pcm.copyOfRange(mid, pcm.size)).trim()).trim(),
                )
                val row = JSONObject()
                    .put("id", clip.getString("id"))
                    .put("durationMs", clip.optInt("durationMs"))
                    .put("rms", sqrt(sum / pcm.size))
                    .put("variants", JSONObject(variants as Map<*, *>))
                out.put(row)
                android.util.Log.i("BlankDiag", row.toString())
            }
        } finally {
            rec.release()
        }
        val target = File(File(app.filesDir, "bakeoff"), "blank-diagnostic.json")
        target.writeText(JSONObject().put("rows", out).toString())
        assertTrue(true)
    }
}
