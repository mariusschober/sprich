package com.sprich.app

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sprich.app.core.audio.Pcm16Wav
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.speech.canary.CanaryEngine
import com.sprich.app.speech.lid.WhisperLidEngine
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.parakeet.ParakeetEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T4 bake-off on frozen PCM (debug build only, public research corpus).
 *
 * Candidate A: Whisper Tiny LID -> Canary 180M Flash in the detected language (production
 * behavior after T3, including fail-closed on undetected language).
 * Candidate B: debug-only Parakeet TDT 0.6B v3 INT8.
 *
 * Audio and results are pushed to /data/local/tmp and pulled by the maintainer's workstation;
 * only the clip manifest (ids, references, hashes) is committed. Corpus: FLEURS test split
 * (CC-BY-4.0) plus synthetic silence/noise (generated locally, not redistributed).
 */
@RunWith(AndroidJUnit4::class)
class BakeoffFrozenPcmDeviceTest {

    private fun ctx() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun app() = ctx().applicationContext as SprichApp

    private data class Clip(
        val id: String,
        val language: String,
        val kind: String,
        val reference: String,
        val audio: File,
        val durationMs: Int,
    )

    // Corpus and model files are copied into the app's private storage via run-as because
    // /data/local/tmp and shell-pushed external files are not readable by the app process.
    private fun bakeoffRoot(): File = File(app().filesDir, "bakeoff")

    private fun clips(): List<Clip> {
        val manifest = File(bakeoffRoot(), "manifest.json")
        assumeTrue("bake-off manifest missing", manifest.isFile)
        val root = JSONObject(manifest.readText())
        val array = root.getJSONArray("clips")
        val out = ArrayList<Clip>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val lang = o.getString("language")
            out += Clip(
                id = o.getString("id"),
                language = lang,
                kind = if (o.has("kind")) o.getString("kind") else "speech",
                reference = o.optString("reference", ""),
                audio = File(File(bakeoffRoot(), "wav"), o.getString("audio")),
                durationMs = o.optInt("durationMs", 0),
            )
        }
        return out
    }

    private fun pssKb(): Int {
        val am = app().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()?.totalPss ?: -1
    }

    private fun writeResults(name: String, json: JSONObject) {
        val dir = File(app().filesDir, "bakeoff").apply { mkdirs() }
        File(dir, name).writeText(json.toString())
        android.util.Log.i("Bakeoff", "wrote $name bytes=${File(dir, name).length()}")
    }

    private fun readPcm(file: File): ShortArray =
        file.inputStream().use { Pcm16Wav.read(it).samples }

    @Test
    fun frozenPcmBakeoff() = runBlocking {
        val app = app()
        val mm = ModelManager(app)
        // Readiness is verified lazily; do not race the process-wide background refresh.
        assumeTrue("LID model not provisioned", mm.verifyInstalled("lid"))
        assumeTrue("Canary model not provisioned", mm.verifyInstalled("accurate"))
        android.util.Log.i("Bakeoff", "externalDir=${app.getExternalFilesDir(null)} manifest=${File(bakeoffRoot(), "manifest.json").isFile} lid=${mm.isWhisperTinyReady()} canary=${mm.isCanaryReady()}")
        val items = clips()
        android.util.Log.i("Bakeoff", "clips=${items.size}")
        assumeTrue("empty corpus", items.isNotEmpty())
        val lid = WhisperLidEngine(app, mm)
        val canary = CanaryEngine(app, mm)
        val parakeet = ParakeetEngine(File(app.filesDir, "parakeet").absolutePath)
        val pssBefore = pssKb()
        val t0 = android.os.SystemClock.elapsedRealtime()
        assertTrue("LID load", lid.load().isSuccess)
        val lidLoadMs = android.os.SystemClock.elapsedRealtime() - t0
        val t1 = android.os.SystemClock.elapsedRealtime()
        assertTrue("Canary load", canary.load().isSuccess)
        val canaryLoadMs = android.os.SystemClock.elapsedRealtime() - t1
        val pssAfterCanary = pssKb()
        val t2 = android.os.SystemClock.elapsedRealtime()
        assertTrue("Parakeet load: ${parakeet.load().exceptionOrNull()}", parakeet.isLoaded())
        val parakeetLoadMs = android.os.SystemClock.elapsedRealtime() - t2
        val pssAfterParakeet = pssKb()

        val results = JSONArray()
        var processed = 0
        for (clip in items) {
            val pcm = readPcm(clip.audio)
            // Candidate A: production-shaped LID -> Canary, fail-closed when LID is unresolved.
            var lidCode = "unknown"
            var lidMs = 0L
            var lidDetected: Language? = null
            when (val outcome = lid.identify(pcm)) {
                is WhisperLidEngine.LidOutcome.Detected -> {
                    lidCode = outcome.language.code
                    lidMs = outcome.latencyMs
                    lidDetected = outcome.language
                }
                is WhisperLidEngine.LidOutcome.Unsupported -> { lidCode = "unsupported:${outcome.rawCode}"; lidMs = outcome.latencyMs }
                is WhisperLidEngine.LidOutcome.Failed -> { lidCode = "failed"; lidMs = outcome.latencyMs }
                is WhisperLidEngine.LidOutcome.Unavailable -> lidCode = "unavailable"
            }
            var canaryText = ""
            var canaryMs = 0L
            // For synthetic silence/noise use explicit EN so the decoder is exercised; production
            // would fail closed (recorded as blank) — the report marks this method difference.
            val measuredLanguage = lidDetected
            if (measuredLanguage != null) {
                val cfg = SpeechSessionConfig(
                    speechLanguage = SpeechLanguage.Fixed(measuredLanguage.code),
                    language = measuredLanguage,
                )
                val start = android.os.SystemClock.elapsedRealtime()
                canaryText = canary.transcribeSnapshot(pcm, cfg).text.trim()
                canaryMs = android.os.SystemClock.elapsedRealtime() - start
            }
            val paraStart = android.os.SystemClock.elapsedRealtime()
            val parakeetText = parakeet.transcribe(pcm)
            val parakeetMs = android.os.SystemClock.elapsedRealtime() - paraStart
            results.put(
                JSONObject()
                    .put("id", clip.id)
                    .put("language", clip.language)
                    .put("kind", clip.kind)
                    .put("reference", clip.reference)
                    .put("durationMs", clip.durationMs)
                    .put("lid", lidCode)
                    .put("lidMs", lidMs)
                    .put("canaryText", canaryText)
                    .put("canaryDecodeMs", canaryMs)
                    .put("parakeetText", parakeetText)
                    .put("parakeetDecodeMs", parakeetMs),
            )
            processed++
            if (processed % 20 == 0) android.util.Log.i("Bakeoff", "progress $processed/${items.size}")
        }
        val report = JSONObject()
            .put("corpus", "FLEURS test split (google/fleurs, CC-BY-4.0) + synthetic silence/noise")
            .put("method", "frozen PCM16 16 kHz mono; LID per clip; Canary decode with detected language (blank if LID unresolved); Parakeet auto language")
            .put("device", android.os.Build.MODEL)
            .put("api", android.os.Build.VERSION.SDK_INT)
            .put("lidLoadMs", lidLoadMs)
            .put("canaryLoadMs", canaryLoadMs)
            .put("parakeetLoadMs", parakeetLoadMs)
            .put("parakeetModelBytes", ParakeetEngine.ARCHIVE_BYTES)
            .put("parakeetArchiveSha256", ParakeetEngine.ARCHIVE_SHA256)
            .put("parakeetLicense", ParakeetEngine.LICENSE)
            .put("pssKbBefore", pssBefore)
            .put("pssKbAfterCanaryLoad", pssAfterCanary)
            .put("pssKbAfterParakeetLoad", pssAfterParakeet)
            .put("pssKbEnd", pssKb())
            .put("results", results)
        writeResults("frozen-results.json", report)
        parakeet.unload(); canary.unload(); lid.unload()
    }


}
