package com.sprich.app.diagnostics

import android.content.Context
import android.util.Log
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.canary.CanaryEngine
import java.io.File
import kotlin.math.sqrt

/**
 * Reproducible raw-ASR harness for German blank triage.
 *
 * For a failing live German utterance:
 * 1. explicitly enable developer WAV capture (opt-in)
 * 2. capture the exact frozen PCM that was passed to final decode
 * 3. save it locally/app-private
 * 4. record the resolved language/config
 * 5. replay that identical PCM directly through Canary de→de, outside IME lifecycle
 * 6. compare results
 *
 * Interpretation:
 * - If live final is blank AND offline replay is blank → model/acoustic/segmentation limitation plausible
 * - If live final is blank BUT offline replay is correct → pipeline/config/state bug
 *
 * Also records per-utterance: duration, RMS, endpoint silence duration, raw final chars (debug only, not default)
 */
object ReplayHarness {

    private const val TAG = "ReplayHarness"
    private const val DIR_NAME = "sprich_replay"

    data class UtteranceMetrics(
        val utteranceId: Long,
        val sessionId: Long,
        val durationMs: Long,
        val rms: Float,
        val endpointSilenceMs: Long,
        val resolvedLanguage: String,
        val rawChars: Int, // debug only
        val pcmSamples: Int,
        val timestampNanos: Long,
    )

    fun computeRms(pcm: ShortArray): Float {
        if (pcm.isEmpty()) return 0f
        var sum = 0.0
        for (s in pcm) { val f = s / 32768.0; sum += f * f }
        return sqrt(sum / pcm.size).toFloat()
    }

    fun computeDurationMs(pcm: ShortArray, sampleRate: Int = 16000): Long =
        (pcm.size * 1000L) / sampleRate

    private fun encodeWav(pcm: ShortArray, sampleRate: Int = 16000): ByteArray {
        val dataSize = pcm.size * 2
        val totalSize = 44 + dataSize
        val buf = ByteArray(totalSize)
        fun writeAscii(off: Int, s: String) { for (i in s.indices) buf[off + i] = s[i].code.toByte() }
        fun writeLeInt(off: Int, v: Int) {
            buf[off] = (v and 0xff).toByte()
            buf[off + 1] = ((v shr 8) and 0xff).toByte()
            buf[off + 2] = ((v shr 16) and 0xff).toByte()
            buf[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun writeLeShort(off: Int, v: Int) {
            buf[off] = (v and 0xff).toByte()
            buf[off + 1] = ((v shr 8) and 0xff).toByte()
        }
        writeAscii(0, "RIFF")
        writeLeInt(4, totalSize - 8)
        writeAscii(8, "WAVE")
        writeAscii(12, "fmt ")
        writeLeInt(16, 16)
        writeLeShort(20, 1) // PCM
        writeLeShort(22, 1) // mono
        writeLeInt(24, sampleRate)
        writeLeInt(28, sampleRate * 2) // byteRate
        writeLeShort(32, 2) // blockAlign
        writeLeShort(34, 16) // bits
        writeAscii(36, "data")
        writeLeInt(40, dataSize)
        var off = 44
        for (s in pcm) {
            writeLeShort(off, s.toInt())
            off += 2
        }
        return buf
    }

    /**
     * Save frozen PCM as WAV to app-private dir if developer capture enabled.
     * Returns File if saved, null if disabled or failed.
     * Caller must check opt-in flag (prefs) before calling.
     */
    /** Returns the diagnostic replay dir — MUST be noBackupFilesDir per privacy P1 (never enters backup). */
    fun replayDir(context: Context): File = File(context.noBackupFilesDir, DIR_NAME).apply { mkdirs() }

    fun saveWavIfEnabled(
        context: Context,
        enabled: Boolean,
        utteranceId: Long,
        pcm: ShortArray,
        config: SpeechSessionConfig,
    ): File? {
        if (!com.sprich.app.BuildConfig.DEBUG || !enabled) return null
        if (pcm.isEmpty()) return null
        return try {
            val dir = replayDir(context)
            val name = "utt_${utteranceId}_${config.resolvedLanguageTag()}_${System.currentTimeMillis()}.wav"
            val file = File(dir, name)
            val bytes = encodeWav(pcm, 16000)
            file.writeBytes(bytes)
            Log.i(TAG, "saved wav utt=$utteranceId samples=${pcm.size} lang=${config.resolvedLanguageTag()} file=${file.absolutePath}")
            // Also save sidecar config — atomic pair
            val meta = File(dir, "$name.meta.txt")
            meta.writeText("utteranceId=$utteranceId\nlanguage=${config.resolvedLanguageTag()}\ntask=${config.resolvedTask()}\nsamples=${pcm.size}\nrms=${computeRms(pcm)}\n")
            file
        } catch (e: Exception) {
            Log.w(TAG, "save wav failed utt=$utteranceId", e)
            null
        }
    }

    /**
     * Offline replay: decode identical PCM directly through Canary, outside IME lifecycle.
     * Returns raw transcript. Caller can compare with live final.
     *
     * This is the deterministic replay harness — no VAD, no composition, no post-processing.
     * Just raw engine decode with explicit de→de.
     */
    suspend fun replayOffline(
        engine: CanaryEngine,
        pcm: ShortArray,
        config: SpeechSessionConfig,
    ): String {
        return try {
            val result = engine.transcribeSnapshot(pcm, config)
            Log.i(TAG, "offline replay samples=${pcm.size} lang=${config.resolvedLanguageTag()} chars=${result.text.length}")
            result.text
        } catch (e: Exception) {
            Log.w(TAG, "offline replay failed", e)
            ""
        }
    }

    fun listSavedWavs(context: Context): List<File> {
        // Prefer noBackup location; also check legacy filesDir for migration cleanup
        val dirs = listOf(File(context.noBackupFilesDir, DIR_NAME), File(context.filesDir, DIR_NAME))
        val all = mutableListOf<File>()
        for (d in dirs) {
            if (d.exists()) d.listFiles { f -> f.extension == "wav" }?.let { all.addAll(it) }
        }
        return all
    }

    /** Delete WAV and its sidecar .meta.txt together — no orphaning. */
    fun deleteWavWithMeta(wavFile: File) {
        try { wavFile.delete() } catch (_: Exception) {}
        try { File(wavFile.absolutePath + ".meta.txt").delete() } catch (_: Exception) {}
        // Also handle case where meta is "name.meta.txt" with extra suffix? Already covered
    }

    fun cleanupOld(context: Context, keepLast: Int = 20) {
        val wavs = listSavedWavs(context).sortedBy { it.lastModified() }
        if (wavs.size > keepLast) {
            wavs.take(wavs.size - keepLast).forEach { deleteWavWithMeta(it) }
        }
    }

    /** Remove all replay data — used for Clear local data. Ensures WAV + meta + traces all deleted atomically. */
    fun clearAll(context: Context) {
        for (dir in listOf(File(context.noBackupFilesDir, DIR_NAME), File(context.filesDir, DIR_NAME))) {
            if (!dir.exists()) continue
            try {
                dir.listFiles()?.forEach { f ->
                    try { f.delete() } catch (_: Exception) {}
                }
                // Also clear any .meta.txt remnants that may not have paired wav after crash
                try { dir.delete() } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
        // Also clear debug transcript traces if any (future)
        try {
            File(context.noBackupFilesDir, "sprich_traces").deleteRecursively()
        } catch (_: Exception) {}
        try {
            File(context.filesDir, "sprich_traces").deleteRecursively()
        } catch (_: Exception) {}
    }
}
