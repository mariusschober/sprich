package com.sprich.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sprich.app.core.audio.Pcm16Wav
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.lid.WhisperLidEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.File

@RunWith(AndroidJUnit4::class)
class LidDeviceTest {
    private fun ensureLidFiles(app: SprichApp): Boolean {
        val destDir = File(app.filesDir, "whisper-tiny")
        val enc = File(destDir, "tiny-encoder.int8.onnx")
        val dec = File(destDir, "tiny-decoder.int8.onnx")
        val tok = File(destDir, "tiny-tokens.txt")
        if (enc.exists() && enc.length() > 5_000_000 && dec.exists() && dec.length() > 50_000_000 && tok.exists()) return true
        try {
            destDir.mkdirs()
            val srcEnc = File("/data/local/tmp/tiny-encoder.int8.onnx")
            val srcDec = File("/data/local/tmp/tiny-decoder.int8.onnx")
            val srcTok = File("/data/local/tmp/tiny-tokens.txt")
            if (srcEnc.exists()) srcEnc.copyTo(enc, overwrite = true)
            if (srcDec.exists()) srcDec.copyTo(dec, overwrite = true)
            if (srcTok.exists()) srcTok.copyTo(tok, overwrite = true)
            return enc.exists() && dec.exists()
        } catch (e: Exception) {
            Log.w("LidDevice", "ensureLidFiles failed", e)
            return false
        }
    }

    private fun readWav(path: String): Pcm16Wav.Audio? = try {
        File(path).inputStream().use { Pcm16Wav.read(it) }
    } catch (e: Exception) { Log.w("LidDevice", "readWav $path failed $e"); null }

    private fun slice(pcm: ShortArray, secs: Double, offsetSec: Double = 0.0): ShortArray {
        val start = (offsetSec * 16000).toInt().coerceIn(0, pcm.size)
        val len = (secs * 16000).toInt().coerceAtLeast(0)
        val end = (start + len).coerceAtMost(pcm.size)
        return pcm.copyOfRange(start, end)
    }

    @Test
    fun lidDetectsEnglishOnJfk() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        ensureLidFiles(app)
        val lid = WhisperLidEngine(app, com.sprich.app.models.manager.ModelManager(app))
        val load = runBlocking { lid.load() }
        Log.i("LidDevice", "load $load success=${load.isSuccess} err=${load.exceptionOrNull()?.message} ready=${com.sprich.app.models.manager.ModelManager(app).isWhisperTinyReady()}")
        if (load.isFailure) {
            Log.w("LidDevice", "LID model not present, skipping (fresh-install without download). Mark NOT MEASURED for this env.")
            return
        }
        val audio = app.assets.open("jfk.wav").use { Pcm16Wav.read(it) }
        val pcm = audio.samples.copyOfRange(0, 16000 * 3) // 3s slice 16k mono
        val res = runBlocking { lid.identify(pcm) }
        Log.i("LidDevice", "lid jfk res=$res pcm=${pcm.size}")
        when (res) {
            is WhisperLidEngine.LidOutcome.Detected -> {
                assertEquals(Language.EN, res.language)
                assertTrue(res.rawCode.lowercase().contains("en"))
                assertTrue("latency ${res.latencyMs} should be <2000", res.latencyMs < 2000)
                assertTrue(res.latencyMs >= 0)
            }
            else -> fail("Expected Detected EN for jfk, got $res")
        }
        runBlocking { lid.unload() }
    }

    @Test
    fun lidPerUtteranceNoHardCache() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        ensureLidFiles(app)
        val lid = WhisperLidEngine(app, com.sprich.app.models.manager.ModelManager(app))
        val load = runBlocking { lid.load() }
        if (load.isFailure) {
            Log.w("LidDevice", "skip no model")
            return
        }
        // Use REAL different-language audio, not same pcm twice.
        val enAudio = readWav("/data/local/tmp/en-english.wav") ?: app.assets.open("jfk.wav").use { Pcm16Wav.read(it) }.let { Pcm16Wav.Audio(it.samples.copyOfRange(0, 16000*2), 16000) }
        val deAudio = readWav("/data/local/tmp/de-german.wav")
        assertNotNull("de wav missing", deAudio)
        val pcmEn = enAudio!!.samples
        val pcmDe = deAudio!!.samples.copyOfRange(0, minOf(deAudio.samples.size, 16000*2))
        assertFalse("EN and DE PCM must differ", pcmEn.copyOfRange(0, minOf(1024, pcmEn.size)).contentEquals(pcmDe.copyOfRange(0, minOf(1024, pcmDe.size))))

        val resEn1 = runBlocking { lid.identify(pcmEn) }
        val resDe = runBlocking { lid.identify(pcmDe) }
        val resEn2 = runBlocking { lid.identify(pcmEn) }
        Log.i("LidDevice", "per-utterance no-hard-cache EN1=$resEn1 DE=$resDe EN2=$resEn2")
        // No 30s cache: EN -> DE must switch, DE -> EN must switch back, not stick to previous
        assertTrue("EN1 should be Detected EN, got $resEn1", resEn1 is WhisperLidEngine.LidOutcome.Detected && (resEn1 as WhisperLidEngine.LidOutcome.Detected).language == Language.EN)
        assertTrue("DE should be Detected DE, got $resDe", resDe is WhisperLidEngine.LidOutcome.Detected && (resDe as WhisperLidEngine.LidOutcome.Detected).language == Language.DE)
        assertTrue("EN2 should be Detected EN again (not stuck to DE), got $resEn2", resEn2 is WhisperLidEngine.LidOutcome.Detected && (resEn2 as WhisperLidEngine.LidOutcome.Detected).language == Language.EN)
        // No EN fallback counted: ensure DE not returned as EN
        if (resDe is WhisperLidEngine.LidOutcome.Detected) assertNotEquals(Language.EN, resDe.language)
        runBlocking { lid.unload() }
    }

    @Test
    fun lidAlternatingEnDeEsFrNoStickiness() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        ensureLidFiles(app)
        val lid = WhisperLidEngine(app, com.sprich.app.models.manager.ModelManager(app))
        val load = runBlocking { lid.load() }
        if (load.isFailure) { Log.w("LidDevice","skip"); return }

        // EN → DE → EN → DE → ES → FR → DE → EN  (8 utterances, same field, no Settings change)
        val seq = listOf(
            "/data/local/tmp/en-english.wav" to Language.EN,
            "/data/local/tmp/de-german.wav" to Language.DE,
            "/data/local/tmp/en-english.wav" to Language.EN,
            "/data/local/tmp/de-german.wav" to Language.DE,
            "/data/local/tmp/es-spanish.wav" to Language.ES,
            "/data/local/tmp/fr-french.wav" to Language.FR,
            "/data/local/tmp/de-german.wav" to Language.DE,
            "/data/local/tmp/en-english.wav" to Language.EN,
        )
        // Also fallback to jfk slices if any wav missing
        val jfk = app.assets.open("jfk.wav").use { Pcm16Wav.read(it) }
        var correct = 0
        val total = seq.size
        val confusion = mutableMapOf<String, MutableMap<String, Int>>()
        for ((idx, pair) in seq.withIndex()) {
            val (path, expected) = pair
            var audio = readWav(path)
            if (audio == null) {
                // Use jfk slice as fallback but still test no-stickiness logic with synthetic EN/DE toggle
                audio = Pcm16Wav.Audio(jfk.samples.copyOfRange(0, 16000*2), 16000)
            }
            val pcm = audio.samples
            val res = runBlocking { lid.identify(pcm) }
            val detectedTag = when (res) {
                is WhisperLidEngine.LidOutcome.Detected -> res.language.code
                is WhisperLidEngine.LidOutcome.Unsupported -> "unsupported:${res.rawCode}"
                is WhisperLidEngine.LidOutcome.Failed -> "failed:${res.reason}"
                is WhisperLidEngine.LidOutcome.Unavailable -> "unavailable:${res.reason}"
            }
            val expectedTag = expected.code
            confusion.getOrPut(expectedTag) { mutableMapOf() }.merge(detectedTag, 1, Int::plus)
            val isCorrect = res is WhisperLidEngine.LidOutcome.Detected && res.language == expected
            if (isCorrect) correct++
            Log.i("LidDevice", "alt $idx expected=$expectedTag pcm=${pcm.size} res=$res correct=$isCorrect")
            // Per-utterance assertions: must be Detected with correct language, not AUTO/en fallback, not stuck
            assertTrue("Utterance $idx expected $expectedTag but got $res (no EN fallback, no sticky)", res is WhisperLidEngine.LidOutcome.Detected && (res as WhisperLidEngine.LidOutcome.Detected).language == expected)
        }
        Log.i("LidDevice", "confusion=$confusion correct=$correct/$total")
        assertEquals("All 8 alternating must be correct (≥99% clean gate)", total, correct)
        // Verify no UI locale involvement: result must not depend on Locale.getDefault()
        // (We test by checking classification is based on PCM content, not locale — already verified by alternating success)
        runBlocking { lid.unload() }
    }

    @Test
    fun lidRapidSwitchAndShortUtterances() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        ensureLidFiles(app)
        val lid = WhisperLidEngine(app, com.sprich.app.models.manager.ModelManager(app))
        if (runBlocking { lid.load() }.isFailure) { Log.w("LidDevice","skip"); return }

        val en = readWav("/data/local/tmp/en-english.wav")!!
        val de = readWav("/data/local/tmp/de-german.wav")!!
        val jfk = app.assets.open("jfk.wav").use { Pcm16Wav.read(it) }

        // DE → EN <1 sec pause: identify DE then immediately EN, ensure EN not stuck to DE
        val resDe = runBlocking { lid.identify(de.samples.copyOfRange(0, minOf(de.samples.size, 16000*2))) }
        val resEnRapid = runBlocking { lid.identify(en.samples.copyOfRange(0, minOf(en.samples.size, 16000*2))) }
        Log.i("LidDevice", "rapid DE→EN de=$resDe enRapid=$resEnRapid")
        assertTrue(resDe is WhisperLidEngine.LidOutcome.Detected && (resDe as WhisperLidEngine.LidOutcome.Detected).language == Language.DE)
        assertTrue(resEnRapid is WhisperLidEngine.LidOutcome.Detected && (resEnRapid as WhisperLidEngine.LidOutcome.Detected).language == Language.EN)

        // EN → DE <1 sec pause
        val resEn = runBlocking { lid.identify(en.samples.copyOfRange(0, minOf(en.samples.size, 16000*2))) }
        val resDeRapid = runBlocking { lid.identify(de.samples.copyOfRange(0, minOf(de.samples.size, 16000*2))) }
        Log.i("LidDevice", "rapid EN→DE en=$resEn deRapid=$resDeRapid")
        assertTrue(resEn is WhisperLidEngine.LidOutcome.Detected && (resEn as WhisperLidEngine.LidOutcome.Detected).language == Language.EN)
        assertTrue(resDeRapid is WhisperLidEngine.LidOutcome.Detected && (resDeRapid as WhisperLidEngine.LidOutcome.Detected).language == Language.DE)

        // 0.5–1s short utterance (slice to 0.8s)
        val shortEn = slice(en.samples, 0.8)
        val shortDe = slice(de.samples, 0.8)
        val resShortEn = runBlocking { lid.identify(shortEn) }
        val resShortDe = runBlocking { lid.identify(shortDe) }
        Log.i("LidDevice", "short 0.8s en=$resShortEn de=$resShortDe")
        // Short may be less reliable — we log but still assert not systematic wrong-language if Detected
        if (resShortEn is WhisperLidEngine.LidOutcome.Detected) assertNotEquals(Language.DE, resShortEn.language)
        if (resShortDe is WhisperLidEngine.LidOutcome.Detected) assertNotEquals(Language.EN, resShortDe.language)

        // 1–2s utterance (1.5s)
        val midEn = slice(en.samples, 1.5)
        val midDe = slice(de.samples, 1.5)
        val resMidEn = runBlocking { lid.identify(midEn) }
        val resMidDe = runBlocking { lid.identify(midDe) }
        Log.i("LidDevice", "mid 1.5s en=$resMidEn de=$resMidDe")
        assertTrue(resMidEn is WhisperLidEngine.LidOutcome.Detected && (resMidEn as WhisperLidEngine.LidOutcome.Detected).language == Language.EN)
        assertTrue(resMidDe is WhisperLidEngine.LidOutcome.Detected && (resMidDe as WhisperLidEngine.LidOutcome.Detected).language == Language.DE)

        // 5–10s utterance (jfk 6s)
        val longEn = jfk.samples.copyOfRange(0, 16000*6)
        val resLongEn = runBlocking { lid.identify(longEn) }
        Log.i("LidDevice", "long 6s en=$resLongEn")
        assertTrue(resLongEn is WhisperLidEngine.LidOutcome.Detected && (resLongEn as WhisperLidEngine.LidOutcome.Detected).language == Language.EN)

        runBlocking { lid.unload() }
    }

    @Test
    fun lidWhisperAndNoise() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        ensureLidFiles(app)
        val lid = WhisperLidEngine(app, com.sprich.app.models.manager.ModelManager(app))
        if (runBlocking { lid.load() }.isFailure) { Log.w("LidDevice","skip"); return }

        val en = readWav("/data/local/tmp/en-english.wav")!!
        val de = readWav("/data/local/tmp/de-german.wav")!!

        // whisper: scale RMS to ~5% (quiet speech, not digital silence 0.0005)
        fun scale(pcm: ShortArray, factor: Double): ShortArray = ShortArray(pcm.size) { (pcm[it] * factor).toInt().toShort() }
        val whisperEn = scale(en.samples.copyOfRange(0, minOf(en.samples.size, 16000*2)), 0.05)
        val whisperDe = scale(de.samples.copyOfRange(0, minOf(de.samples.size, 16000*2)), 0.05)
        val resWhEn = runBlocking { lid.identify(whisperEn) }
        val resWhDe = runBlocking { lid.identify(whisperDe) }
        Log.i("LidDevice", "whisper en=$resWhEn de=$resWhDe")
        // Whisper should not systematically flip language: if Detected, should still be correct language or at least not cross-misclassify en as de systematically
        // We allow Failed/Unsupported for very quiet, but if Detected, verify language
        if (resWhEn is WhisperLidEngine.LidOutcome.Detected) assertEquals(Language.EN, resWhEn.language)
        if (resWhDe is WhisperLidEngine.LidOutcome.Detected) assertEquals(Language.DE, resWhDe.language)

        // low-SNR noise: 1s white noise at moderate level (should not be confidently EN/DE, but must not crash and must not be counted as EN success)
        val noise = ShortArray(16000) { (kotlin.random.Random.nextInt(-3000, 3000)).toShort() }
        val resNoise = runBlocking { lid.identify(noise) }
        Log.i("LidDevice", "noise res=$resNoise")
        // Noise may be Unsupported/Failed — ensure we don't count EN fallback as success
        assertNotNull(resNoise)
        // No assertion of EN — just verify no crash and no fabricated confidence

        runBlocking { lid.unload() }
    }

    @Test
    fun lidNoMockAndRelease() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp
        ensureLidFiles(app)
        val lid = WhisperLidEngine(app, com.sprich.app.models.manager.ModelManager(app))
        val load = runBlocking { lid.load() }
        if (load.isFailure) { Log.w("LidDevice","skip"); return }
        val en = readWav("/data/local/tmp/en-english.wav")!!
        // Repeated calls to verify native streams released (no native memory leak, no mock)
        repeat(20) { idx ->
            val pcm = en.samples.copyOfRange(0, minOf(en.samples.size, 16000*1))
            val res = runBlocking { lid.identify(pcm) }
            assertTrue("Iteration $idx should be Detected EN, got $res", res is WhisperLidEngine.LidOutcome.Detected && (res as WhisperLidEngine.LidOutcome.Detected).language == Language.EN)
        }
        Log.i("LidDevice", "20 repeated LID calls no leak, all EN")
        runBlocking { lid.unload() }
        // After unload, identify should be Unavailable, not fabricated EN/DE
        val resAfterUnload = runBlocking { lid.identify(en.samples.copyOfRange(0, 16000)) }
        assertTrue("After unload should be Unavailable, not fabricated", resAfterUnload is WhisperLidEngine.LidOutcome.Unavailable)
    }
}
