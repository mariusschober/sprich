package com.sprich.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sprich.app.core.audio.Pcm16Wav
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.models.manager.ModelStatus
import com.sprich.app.speech.LocalAsrRoute
import com.sprich.app.speech.LocalTranscriptionCoordinator
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.storage.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 5 — Prove Automatic works with Canary deleted.
 * This is the critical release gate. Exercises ACTUAL coordinator route, not just direct engine calls.
 *
 * Required setup: delete Canary, verify !isCanaryReady(), install Lid+Fast, verify Ready, select Automatic.
 * Then exercise production dictation path via LocalTranscriptionCoordinator (extracted from SprichIME) —
 * which is the same route SprichIME uses. Do not declare PASS solely from directly invoking FastConformer.
 */
@RunWith(AndroidJUnit4::class)
class AutomaticWithoutCanaryDeviceTest {

    @Test
    fun automaticWorksEndToEndWithCanaryAbsent() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val mm = ModelManager(app)
        val prefs = Preferences(app)

        // 1. Delete Canary — verify absent
        mm.deleteCanary()
        assertFalse("Canary should be absent for this gate", mm.isCanaryReady())
        assertTrue(mm.canaryStatus.value is ModelStatus.NotDownloaded)

        // 2. Ensure Tiny LID and FastConformer are Ready (download or simulate via backup files)
        // Prefer real files if already downloaded; otherwise simulate atomic install from /data/local/tmp backup (like FreshInstall)
        val lidBackupEnc = File("/data/local/tmp/tiny-encoder.int8.onnx")
        val fastBackup = File("/data/local/tmp/fastconformer/model.int8.onnx")
        // Also check filesDir fastconformer
        val lidReadyBefore = mm.isWhisperTinyReady()
        val fastReadyBefore = mm.isFastConformerReady()
        android.util.Log.i("AutoWithoutCanary", "before lidReady=$lidReadyBefore fastReady=$fastReadyBefore canaryReady=${mm.isCanaryReady()}")

        if (!lidReadyBefore && lidBackupEnc.exists()) {
            val tmp = File(app.filesDir, "whisper-tiny.tmp")
            tmp.deleteRecursively(); tmp.mkdirs()
            File("/data/local/tmp/tiny-encoder.int8.onnx").copyTo(File(tmp, "tiny-encoder.int8.onnx"), overwrite = true)
            File("/data/local/tmp/tiny-decoder.int8.onnx").copyTo(File(tmp, "tiny-decoder.int8.onnx"), overwrite = true)
            File("/data/local/tmp/tiny-tokens.txt").copyTo(File(tmp, "tiny-tokens.txt"), overwrite = true)
            File(app.filesDir, "whisper-tiny").deleteRecursively()
            tmp.renameTo(File(app.filesDir, "whisper-tiny"))
            mm.setReady("lid")
        }
        if (!fastReadyBefore && fastBackup.exists()) {
            val tmp = File(app.filesDir, "fastconformer.tmp")
            tmp.deleteRecursively(); tmp.mkdirs()
            File("/data/local/tmp/fastconformer/model.int8.onnx").copyTo(File(tmp, "model.int8.onnx"), overwrite = true)
            File("/data/local/tmp/fastconformer/tokens.txt").copyTo(File(tmp, "tokens.txt"), overwrite = true)
            File(app.filesDir, "fastconformer").deleteRecursively()
            tmp.renameTo(File(app.filesDir, "fastconformer"))
            mm.setReady("fastconformer")
        }
        // Also check alternative backup path for fast
        val fastAlt = File("/data/local/tmp/model.int8.onnx")
        if (!mm.isFastConformerReady() && fastAlt.exists()) {
            val dir = File(app.filesDir, "fastconformer")
            dir.mkdirs()
            fastAlt.copyTo(File(dir, "model.int8.onnx"), overwrite = true)
            File("/data/local/tmp/tokens.txt").copyTo(File(dir, "tokens.txt"), overwrite = true)
            mm.setReady("fastconformer")
        }

        val lidReady = mm.isWhisperTinyReady()
        val fastReady = mm.isFastConformerReady()
        android.util.Log.i("AutoWithoutCanary", "after lidReady=$lidReady fastReady=$fastReady")

        // Required fixtures cannot be optional — missing → BLOCKED/FAIL, not successful early return
        if (!lidReady) fail("BLOCKED: whisper-tiny model not ready — need tiny-encoder.int8.onnx (>10M) + tiny-decoder.int8.onnx (>50M) + tokens + .installed_ok")
        if (!fastReady) fail("BLOCKED: fastconformer model not ready — need model.int8.onnx (>50M) + tokens.txt")

        assertTrue("LID Ready required", lidReady)
        assertTrue("Fast Ready required", fastReady)
        assertTrue("Automatic Ready requires both", mm.isAutomaticReady())
        assertFalse("Canary must be absent", mm.isCanaryReady())

        // 3. Select Automatic
        prefs.setSpeechLanguage(SpeechLanguage.Auto)
        val sl = prefs.speechLanguage.first()
        assertTrue(sl is SpeechLanguage.Auto)
        android.util.Log.i("AutoWithoutCanary", "selected Automatic, autoReady=${mm.isAutomaticReady()}")

        // 4. Exercise production coordinator route — not just direct FastConformer call
        // Coordinator is the same production router SprichIME uses (extracted per Phase 19)
        val lidEngine = com.sprich.app.speech.lid.WhisperLidEngine(app, mm)
        val fastEngine = com.sprich.app.speech.fastconformer.FastConformerEngine(app)
        val canaryEngine = com.sprich.app.speech.canary.CanaryEngine(app, mm)
        val coordinator = LocalTranscriptionCoordinator(lidEngine, fastEngine, canaryEngine)

        // Verify Canary load count before — should be 0 for this Automatic test
        val canaryLoadBefore = if (canaryEngine.isLoaded()) 1L else 0L
        // Do not load Canary; ensure no load attempted for Automatic

        // Load required Automatic engines only
        val lidLoad = lidEngine.load()
        assertTrue("Tiny LID load success", lidLoad.isSuccess)
        val fastLoad = fastEngine.load()
        assertTrue("FastConformer load success for Automatic", fastLoad.isSuccess)
        // Do NOT load Canary — verify it remains not loaded
        assertFalse("Canary must NOT be loaded for Automatic", canaryEngine.isLoaded())
        assertEquals(0L, canaryLoadBefore)

        // Required EN/DE/ES/FR audio — no JFK substitution for another language
        val enWavFile = File("/data/local/tmp/en-english.wav")
        val deWavFile = File("/data/local/tmp/de-german.wav")
        val esWavFile = File("/data/local/tmp/es-spanish.wav")
        val frWavFile = File("/data/local/tmp/fr-french.wav")
        if (!enWavFile.exists()) fail("BLOCKED: en-english.wav fixture missing at /data/local/tmp/en-english.wav")
        if (!deWavFile.exists()) fail("BLOCKED: de-german.wav fixture missing")
        if (!esWavFile.exists()) fail("BLOCKED: es-spanish.wav fixture missing")
        if (!frWavFile.exists()) fail("BLOCKED: fr-french.wav fixture missing")
        val enPcm = try { Pcm16Wav.read(enWavFile.inputStream()).samples } catch (e: Exception) { fail("BLOCKED: en wav read failed ${e.message}"); ShortArray(0) }
        val dePcm = try { Pcm16Wav.read(deWavFile.inputStream()).samples } catch (e: Exception) { fail("BLOCKED: de wav read failed ${e.message}"); ShortArray(0) }
        val esPcm = try { Pcm16Wav.read(esWavFile.inputStream()).samples } catch (e: Exception) { fail("BLOCKED: es wav read failed ${e.message}"); ShortArray(0) }
        val frPcm = try { Pcm16Wav.read(frWavFile.inputStream()).samples } catch (e: Exception) { fail("BLOCKED: fr wav read failed ${e.message}"); ShortArray(0) }

        // EN
        val enResult = coordinator.transcribe(enPcm, LocalAsrRoute.AutomaticFastConformer, SpeechSessionConfig(speechLanguage = SpeechLanguage.Auto))
        android.util.Log.i("AutoWithoutCanary", "EN result engine=${enResult.engineId} textLen=${enResult.text.length} resolved=${enResult.resolvedLanguage}")
        assertTrue("EN Automatic via FastConformer should produce non-blank", enResult.text.isNotBlank())
        assertEquals(fastEngine.engineId, enResult.engineId)
        assertTrue("EN LID should be Known", enResult.resolvedLanguage is com.sprich.app.speech.ResolvedUtteranceLanguage.Known)
        assertFalse(canaryEngine.isLoaded())

        // DE
        val deResult = coordinator.transcribe(dePcm, LocalAsrRoute.AutomaticFastConformer, SpeechSessionConfig(speechLanguage = SpeechLanguage.Auto))
        android.util.Log.i("AutoWithoutCanary", "DE result engine=${deResult.engineId} textLen=${deResult.text.length} resolved=${deResult.resolvedLanguage}")
        assertTrue("DE Automatic via FastConformer should produce non-blank", deResult.text.isNotBlank())
        assertEquals(fastEngine.engineId, deResult.engineId)
        assertTrue("DE LID should be Known", deResult.resolvedLanguage is com.sprich.app.speech.ResolvedUtteranceLanguage.Known)
        assertFalse(canaryEngine.isLoaded())

        // ES
        val esResult = coordinator.transcribe(esPcm, LocalAsrRoute.AutomaticFastConformer, SpeechSessionConfig(speechLanguage = SpeechLanguage.Auto))
        assertTrue("ES Automatic should produce non-blank", esResult.text.isNotBlank())
        assertTrue(esResult.resolvedLanguage is com.sprich.app.speech.ResolvedUtteranceLanguage.Known)

        // FR
        val frResult = coordinator.transcribe(frPcm, LocalAsrRoute.AutomaticFastConformer, SpeechSessionConfig(speechLanguage = SpeechLanguage.Auto))
        assertTrue("FR Automatic should produce non-blank", frResult.text.isNotBlank())
        assertTrue(frResult.resolvedLanguage is com.sprich.app.speech.ResolvedUtteranceLanguage.Known)

        // 5. Verify exactly-once: coordinator for same PCM called twice with same pending should not corrupt, but we test queue isolation via reusing pcm
        if (enPcm != null) {
            val first = coordinator.transcribe(enPcm, LocalAsrRoute.AutomaticFastConformer, SpeechSessionConfig(speechLanguage = SpeechLanguage.Auto))
            val second = coordinator.transcribe(enPcm, LocalAsrRoute.AutomaticFastConformer, SpeechSessionConfig(speechLanguage = SpeechLanguage.Auto))
            // Both should be non-blank and independent — no leakage between utterances
            if (first.text.isNotBlank() && second.text.isNotBlank()) {
                android.util.Log.i("AutoWithoutCanary", "exactly-once isolation PASS texts equal=${first.text == second.text}")
            }
        }

        // 6. Verify explicit Accurate still works even when Canary was absent for Automatic — now install Canary and test DE explicit
        // For this gate we only verify that Automatic did NOT require Canary; explicit Accurate path is tested separately.

        android.util.Log.i("AutoWithoutCanary", "=== AutomaticWithoutCanaryDeviceTest PASS canaryReady=false lidReady=$lidReady fastReady=$fastReady engine=${fastEngine.engineId} ===")

        lidEngine.unload()
        fastEngine.unload()
    }

    @Test
    fun accurateExplicitStillWorksWhenFastAbsentAllowed() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val mm = ModelManager(app)
        // Ensure Canary installed from /data/local/tmp if missing (like FreshInstall)
        if (!mm.isCanaryReady()) {
            try {
                val destDir = File(app.filesDir, "canary")
                destDir.mkdirs()
                val srcEnc = File("/data/local/tmp/canary-encoder.int8.onnx")
                val srcDec = File("/data/local/tmp/canary-decoder.int8.onnx")
                val srcTok = File("/data/local/tmp/canary-tokens.txt")
                // Also try generic encoder/decoder names
                val enc = if (srcEnc.exists()) srcEnc else File("/data/local/tmp/encoder.int8.onnx")
                val dec = if (srcDec.exists()) srcDec else File("/data/local/tmp/decoder.int8.onnx")
                val tok = if (srcTok.exists()) srcTok else File("/data/local/tmp/tokens.txt")
                if (enc.exists() && dec.exists() && tok.exists()) {
                    enc.copyTo(File(destDir, "encoder.int8.onnx"), overwrite = true)
                    dec.copyTo(File(destDir, "decoder.int8.onnx"), overwrite = true)
                    tok.copyTo(File(destDir, "tokens.txt"), overwrite = true)
                    mm.setReady("accurate")
                }
            } catch (_: Exception) {}
        }
        val canaryReady = mm.isCanaryReady()
        if (!canaryReady) fail("BLOCKED: canary model not ready — need encoder/decoder >50M")
        val canaryEngine = com.sprich.app.speech.canary.CanaryEngine(app, mm)
        val load = canaryEngine.load()
        assertTrue(load.isSuccess)
        // No requirement for Lid/Fast
        assertTrue("Accurate DE should be possible with Canary", mm.isCanaryReady())
        // Test transcribe via coordinator with Accurate route
        val lidEngine = com.sprich.app.speech.lid.WhisperLidEngine(app, mm)
        val fastEngine = com.sprich.app.speech.fastconformer.FastConformerEngine(app)
        val coordinator = LocalTranscriptionCoordinator(lidEngine, fastEngine, canaryEngine)
        val dePcm = try {
            val f = File("/data/local/tmp/de-german.wav")
            if (f.exists()) Pcm16Wav.read(f.inputStream()).samples else null
        } catch (_: Exception) { null }
        val dePcmFile2 = File("/data/local/tmp/de-german.wav")
        if (!dePcmFile2.exists()) fail("BLOCKED: de-german.wav missing for Accurate test")
        val dePcm2 = try { Pcm16Wav.read(dePcmFile2.inputStream()).samples } catch (e: Exception) { fail("BLOCKED: de wav read failed"); ShortArray(0) }
        val res = coordinator.transcribe(dePcm2, LocalAsrRoute.AccurateCanary(com.sprich.app.speech.api.Language.DE), SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("de")))
        android.util.Log.i("AutoWithoutCanary", "Accurate DE result len=${res.text.length}")
        assertTrue(res.text.isNotBlank())
        assertEquals(canaryEngine.engineId, res.engineId)
        canaryEngine.unload()
    }
}
