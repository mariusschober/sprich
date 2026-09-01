package com.sprich.app.speech.lid

import android.content.Context
import android.util.Log
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.speech.api.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Whisper Tiny per-utterance spoken language ID — production-safe.
 * Uses sherpa-onnx SpokenLanguageIdentification (tiny) — not transcription heuristics.
 *
 * Requirements:
 * - per utterance, no hard cache; no 30s cache
 * - never use Android UI locale
 * - uses sherpa's actual SLID API
 * - production never fabricates language when lid == null
 * - streams released in finally, native memory bounded
 * - readiness via ModelManager single source (both encoder+decoder)
 * - no fake confidence; upstream only returns language code
 * - never fallback failed Auto to EN
 */
class WhisperLidEngine(
    private val context: Context,
    private val modelManager: ModelManager,
) {
    private val mutex = Mutex()
    private var lid: Any? = null // SpokenLanguageIdentification
    private var loaded = false

    sealed class LidOutcome {
        data class Detected(val language: Language, val rawCode: String, val latencyMs: Long) : LidOutcome()
        data class Unsupported(val rawCode: String, val latencyMs: Long) : LidOutcome() // e.g., "zh" not in EN/DE/ES/FR
        data class Failed(val reason: String, val latencyMs: Long) : LidOutcome()
        data class Unavailable(val reason: String) : LidOutcome()
    }

    private fun isSherpaSlidAvailable(): Boolean = try {
        Class.forName("com.k2fsa.sherpa.onnx.SpokenLanguageIdentification")
        Class.forName("com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig")
        true
    } catch (_: Throwable) { false }

    // Single source of truth: ModelManager.isWhisperTinyReady() checks both files + size
    // Fallback direct check for tests that bypass ModelManager
    private fun isTinyReady(): Boolean {
        // Use ModelManager as authoritative source if available
        try {
            if (modelManager.isWhisperTinyReady()) return true
        } catch (_: Exception) {}
        // Direct filesystem check (same logic as ModelManager)
        val d = java.io.File(context.filesDir, "whisper-tiny")
        val enc = java.io.File(d, "tiny-encoder.int8.onnx")
        val dec = java.io.File(d, "tiny-decoder.int8.onnx")
        return enc.exists() && enc.length() > 5_000_000 && dec.exists() && dec.length() > 50_000_000
    }

    suspend fun load(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loaded) return@withContext Result.success(Unit)
            if (!isSherpaSlidAvailable()) {
                Log.w("WhisperLid", "sherpa SLID not available")
                return@withContext Result.failure(Exception("sherpa SLID not available"))
            }
            if (!isTinyReady()) {
                Log.w("WhisperLid", "tiny model not ready (both encoder+decoder required)")
                return@withContext Result.failure(Exception("whisper tiny not ready"))
            }
            val dir = java.io.File(context.filesDir, "whisper-tiny")
            // Also check /data/local/tmp for instrumentation tests
            val effectiveDir = when {
                dir.exists() && java.io.File(dir, "tiny-encoder.int8.onnx").exists() -> dir
                java.io.File("/data/local/tmp/whisper-tiny").exists() -> java.io.File("/data/local/tmp/whisper-tiny")
                else -> dir
            }
            val enc = java.io.File(effectiveDir, "tiny-encoder.int8.onnx").absolutePath
            val dec = java.io.File(effectiveDir, "tiny-decoder.int8.onnx").absolutePath
            // Verify both exist
            if (!java.io.File(enc).exists() || !java.io.File(dec).exists()) {
                return@withContext Result.failure(Exception("tiny encoder/decoder missing"))
            }
            try {
                val whisperConfigClass = Class.forName("com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig")
                val whisperConfig = whisperConfigClass.getConstructor().newInstance()
                whisperConfigClass.getDeclaredField("encoder").apply { isAccessible = true; set(whisperConfig, enc) }
                whisperConfigClass.getDeclaredField("decoder").apply { isAccessible = true; set(whisperConfig, dec) }

                val lidConfigClass = Class.forName("com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig")
                val lidConfig = lidConfigClass.getConstructor().newInstance()
                lidConfigClass.getDeclaredField("whisper").apply { isAccessible = true; set(lidConfig, whisperConfig) }
                lidConfigClass.getDeclaredField("numThreads").apply { isAccessible = true; set(lidConfig, 1) }
                try { lidConfigClass.getDeclaredField("debug").apply { isAccessible = true; set(lidConfig, false) } } catch (_: Exception) {}
                try { lidConfigClass.getDeclaredField("provider").apply { isAccessible = true; set(lidConfig, "cpu") } } catch (_: Exception) {}

                val slidClass = Class.forName("com.k2fsa.sherpa.onnx.SpokenLanguageIdentification")
                val ctor = slidClass.getConstructor(lidConfigClass)
                lid = ctor.newInstance(lidConfig)
                loaded = lid != null
                if (loaded) Result.success(Unit) else Result.failure(Exception("SLID create failed"))
            } catch (e: Throwable) {
                Log.w("WhisperLid", "load failed", e)
                Result.failure(Exception("SLID load failed: ${e.message}", e))
            }
        }
    }

    fun isLoaded(): Boolean = loaded

    suspend fun identify(pcm: ShortArray, sampleRate: Int = 16000): LidOutcome = withContext(Dispatchers.Default) {
        mutex.withLock {
            val t0 = System.nanoTime()
            val rec = lid
            if (rec == null) {
                return@withContext LidOutcome.Unavailable("LID not loaded")
            }
            if (pcm.isEmpty()) {
                return@withContext LidOutcome.Failed("empty PCM", (System.nanoTime() - t0) / 1_000_000)
            }
            var stream: Any? = null
            try {
                stream = rec.javaClass.getMethod("createStream").invoke(rec)
                val floats = FloatArray(pcm.size) { pcm[it] / 32768f }
                stream.javaClass.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType).invoke(stream, floats, sampleRate)
                val langObj = rec.javaClass.getMethod("compute", stream.javaClass).invoke(rec, stream)
                val rawCode: String = try {
                    langObj as String
                } catch (_: Throwable) {
                    try { langObj.javaClass.getDeclaredField("lang").apply { isAccessible = true }.get(langObj) as String } catch (_: Throwable) { "auto" }
                }
                val latency = (System.nanoTime() - t0) / 1_000_000
                val lang = when (rawCode.lowercase()) {
                    "en", "en-us", "en-gb" -> Language.EN
                    "de", "de-de" -> Language.DE
                    "es", "es-es" -> Language.ES
                    "fr", "fr-fr" -> Language.FR
                    else -> null // Unsupported or auto
                }
                if (lang == null) {
                    Log.i("WhisperLid", "identify unsupported raw=$rawCode latencyMs=$latency pcm=${pcm.size}")
                    return@withContext LidOutcome.Unsupported(rawCode, latency)
                }
                Log.i("WhisperLid", "identify raw=$rawCode -> $lang latencyMs=$latency pcm=${pcm.size}")
                return@withContext LidOutcome.Detected(lang, rawCode, latency)
            } catch (e: Throwable) {
                Log.w("WhisperLid", "identify failed", e)
                val latency = (System.nanoTime() - t0) / 1_000_000
                return@withContext LidOutcome.Failed(e.message ?: "identify failed", latency)
            } finally {
                // Release native stream in finally to avoid native memory leak
                if (stream != null) {
                    try { stream.javaClass.getMethod("release").invoke(stream) } catch (_: Exception) {}
                }
            }
        }
    }

    suspend fun unload() {
        mutex.withLock {
            try { lid?.javaClass?.getMethod("release")?.invoke(lid) } catch (_: Exception) {}
            lid = null
            loaded = false
        }
    }
}
