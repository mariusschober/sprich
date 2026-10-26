package com.sprich.app.speech.parakeet

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/**
 * T4 bake-off only: NVIDIA Parakeet TDT 0.6B v3 INT8 via sherpa-onnx (debug source set).
 *
 * Never part of the production configuration. The archive is pinned here and in
 * docs/BAKEOFF_2026-10-25.md:
 *   sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2
 *   bytes 487,170,055 · SHA-256 5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf
 *   weights CC-BY-4.0 (https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3)
 *
 * Files are expected under /data/local/tmp/parakeet from the extracted archive; they are not
 * downloaded by the app and not stored in the APK. Output language is whatever the model detects
 * internally; sherpa does not expose it, so the bake-off measures decoded text and the shared LID
 * gate, never a claimed internal language field.
 */
class ParakeetEngine(private val modelDir: String = MODEL_DIR) {
    private var recognizer: OfflineRecognizer? = null
    @Volatile private var loaded = false
    @Volatile private var loadMs: Long = 0

    fun isLoaded(): Boolean = loaded
    fun load(): Result<Unit> = runCatching {
        if (loaded) return@runCatching
        val dir = File(modelDir)
        val encoder = File(dir, "encoder.int8.onnx")
        val decoder = File(dir, "decoder.int8.onnx")
        val joiner = File(dir, "joiner.int8.onnx")
        val tokens = File(dir, "tokens.txt")
        require(encoder.isFile && encoder.length() > 600_000_000L) { "Parakeet encoder missing/truncated" }
        require(decoder.isFile && decoder.length() > 1_000_000L) { "Parakeet decoder missing" }
        require(joiner.isFile && joiner.length() > 1_000_000L) { "Parakeet joiner missing" }
        require(tokens.isFile) { "Parakeet tokens missing" }
        val started = android.os.SystemClock.elapsedRealtime()
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(16000, 80, 0.0f),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath,
                ),
                tokens = tokens.absolutePath,
                numThreads = 2,
                debug = false,
                modelType = "nemo_transducer",
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
        recognizer = OfflineRecognizer(null, config)
        loaded = true
        loadMs = android.os.SystemClock.elapsedRealtime() - started
        Log.i(ENGINE_ID, "load ok ms=$loadMs")
    }

    fun loadMs(): Long = loadMs

    /** One offline decode of frozen 16 kHz mono PCM16. Empty string on blank/failure. */
    fun transcribe(pcm: ShortArray): String {
        val rec = recognizer ?: return ""
        val stream = rec.createStream()
        return try {
            val samples = FloatArray(pcm.size) { pcm[it] / 32768.0f }
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            rec.getResult(stream).text.trim()
        } catch (e: Exception) {
            Log.w(ENGINE_ID, "decode failed", e)
            ""
        } finally {
            runCatching { stream.release() }
        }
    }

    fun unload() {
        runCatching { recognizer?.release() }
        recognizer = null
        loaded = false
    }

    companion object {
        const val ENGINE_ID = "parakeet-tdt-0.6b-v3-int8"
        const val MODEL_DIR = "/data/local/tmp/parakeet"
        const val SAMPLE_RATE = 16000
        const val ARCHIVE_BYTES = 487_170_055L
        const val ARCHIVE_SHA256 = "5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf"
        const val LICENSE = "CC-BY-4.0 (nvidia/parakeet-tdt-0.6b-v3)"
    }
}
