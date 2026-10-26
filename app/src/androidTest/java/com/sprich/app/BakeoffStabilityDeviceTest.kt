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

/** T4: 15-minute continuous Canary+Parakeet decode stability on frozen PCM (debug only). */
@RunWith(AndroidJUnit4::class)
class BakeoffStabilityDeviceTest {

    private fun app() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp

    private fun pssKb(): Int {
        val am = app().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()?.totalPss ?: -1
    }
    @Test
    fun fifteenMinuteStability() = runBlocking {
        val app = app()
        val mm = ModelManager(app)
        assumeTrue("models not provisioned", mm.verifyInstalled("lid") && mm.verifyInstalled("accurate"))
        val lid = WhisperLidEngine(app, mm)
        val canary = CanaryEngine(app, mm)
        val parakeet = ParakeetEngine(File(app.filesDir, "parakeet").absolutePath)
        assertTrue(lid.load().isSuccess)
        assertTrue(canary.load().isSuccess)
        assertTrue(parakeet.isLoaded() || parakeet.load().isSuccess)
        val jfk = app.assets.open("jfk.wav").use { Pcm16Wav.read(it) }
        val pcm = jfk.samples.copyOfRange(0, 16000 * 6)
        val cfg = SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("en"), language = Language.EN)
        val samples = JSONArray()
        val start = android.os.SystemClock.elapsedRealtime()
        var canaryCount = 0
        var parakeetCount = 0
        while (android.os.SystemClock.elapsedRealtime() - start < 15 * 60 * 1000L) {
            canary.transcribeSnapshot(pcm, cfg); canaryCount++
            parakeet.transcribe(pcm); parakeetCount++
            if (samples.length() < 15 * 60) {
                samples.put(
                    JSONObject()
                        .put("elapsedSec", (android.os.SystemClock.elapsedRealtime() - start) / 1000)
                        .put("pssKb", pssKb())
                        .put("canaryDecodes", canaryCount)
                        .put("parakeetDecodes", parakeetCount),
                )
            }
        }
        val dir = File(app().filesDir, "bakeoff").apply { mkdirs() }
        File(dir, "stability-15m.json").writeText(JSONObject()
            .put("device", android.os.Build.MODEL)
            .put("method", "continuous alternating 6 s frozen jfk decode, 15 min, PSS sampled after each pair")
            .put("canaryDecodes", canaryCount)
            .put("parakeetDecodes", parakeetCount)
            .put("pssSamples", samples).toString())
        parakeet.unload(); canary.unload(); lid.unload()
    }}
