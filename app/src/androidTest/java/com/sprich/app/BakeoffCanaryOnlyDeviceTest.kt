package com.sprich.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sprich.app.core.audio.Pcm16Wav
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.speech.LocalAsrRoute
import com.sprich.app.speech.LocalTranscriptionCoordinator
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.canary.CanaryEngine
import com.sprich.app.speech.lid.WhisperLidEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * R1: Canary-only re-run of the frozen FLEURS subset after the silence-gate and long-utterance
 * segmentation fix. Blanks are recorded separately from WER (host scoring reports both).
 */
@RunWith(AndroidJUnit4::class)
class BakeoffCanaryOnlyDeviceTest {

    private fun app() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
    private fun root() = File(app().filesDir, "bakeoff")

    @Test
    fun canaryFrozenRecheck(): Unit = runBlocking {
        val app = app()
        val mm = ModelManager(app)
        assumeTrue("LID model missing", mm.verifyInstalled("lid"))
        assumeTrue("Canary model missing", mm.verifyInstalled("accurate"))
        val manifest = File(root(), "manifest.json")
        assumeTrue("bake-off manifest missing", manifest.isFile)
        val clips = JSONObject(manifest.readText()).getJSONArray("clips")
        val lid = WhisperLidEngine(app, mm)
        val canary = CanaryEngine(app, mm)
        assertTrue(lid.load().isSuccess)
        assertTrue(canary.load().isSuccess)
        val coordinator = LocalTranscriptionCoordinator(lid, canary)
        val results = JSONArray()
        var processed = 0
        for (i in 0 until clips.length()) {
            val clip = clips.getJSONObject(i)
            if (clip.has("kind") && clip.getString("kind") != "speech") continue
            val file = File(File(root(), "wav"), clip.getString("audio"))
            if (!file.isFile) continue
            val pcm = file.inputStream().use { Pcm16Wav.read(it).samples }
            val start = android.os.SystemClock.elapsedRealtime()
            val outcome = coordinator.transcribe(pcm, LocalAsrRoute.AutomaticCanary, SpeechSessionConfig())
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            results.put(
                JSONObject()
                    .put("id", clip.getString("id"))
                    .put("language", clip.getString("language"))
                    .put("durationMs", clip.optInt("durationMs"))
                    .put("lid", outcome.lidRaw)
                    .put("lidMs", outcome.lidLatencyMs)
                    .put("text", outcome.text)
                    .put("blank", outcome.text.isBlank())
                    .put("decodeMs", elapsed)
            )
            processed++
            if (processed % 20 == 0) android.util.Log.i("CanaryRecheck", "progress $processed")
        }
        val dir = File(app.filesDir, "bakeoff").apply { mkdirs() }
        File(dir, "canary-results.json").writeText(
            JSONObject()
                .put("device", android.os.Build.MODEL)
                .put("api", android.os.Build.VERSION.SDK_INT)
                .put("method", "frozen PCM16; Whisper LID + Canary with segmentation fix")
                .put("clips", results.length())
                .put("results", results)
                .toString())
        android.util.Log.i("CanaryRecheck", "wrote canary-results.json clips=${results.length()}")
        Unit
    }
}
