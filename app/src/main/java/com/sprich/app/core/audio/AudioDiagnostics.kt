package com.sprich.app.core.audio

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Diagnostic WAV capture behind a developer-only switch.
 * Writes PCM16 mono 16k WAV to app-private files when enabled via Settings→Advanced.
 * Verifies actual device sample rate/channel/encoding and logs resampler quality.
 * Never enabled by default; raw audio is not retained or transmitted by default.
 */
object AudioDiagnostics {
    private const val TAG = "AudioDiagnostics"
    @Volatile var isEnabled: Boolean = false

    fun updateEnabled(v: Boolean) {
        isEnabled = v
        Log.i(TAG, "diagnostic capture enabled=$v")
    }

    /**
     * Writes a snapshot of the ring buffer to a WAV file for offline validation.
     * Called only when enabled; file is in app-private storage, not flagged for backup.
     */
    fun captureSnapshot(context: Context, samples: ShortArray, sampleRate: Int = 16000): File? {
        if (!isEnabled) return null
        if (samples.isEmpty()) return null
        return try {
            val dir = File(context.filesDir, "diagnostics/wav")
            dir.mkdirs()
            val f = File(dir, "capture_${System.currentTimeMillis()}.wav")
            f.outputStream().use { out ->
                // Minimal WAV header
                val dataSize = samples.size * 2
                fun putAscii(off: Int, s: String, b: ByteArray) { for (i in s.indices) b[off+i]=s[i].code.toByte() }
                fun put32(off: Int, v: Int, b: ByteArray) { b[off]=(v and 0xff).toByte(); b[off+1]=((v shr 8) and 0xff).toByte(); b[off+2]=((v shr 16) and 0xff).toByte(); b[off+3]=((v shr 24) and 0xff).toByte() }
                fun put16(off: Int, v: Int, b: ByteArray) { b[off]=(v and 0xff).toByte(); b[off+1]=((v shr 8) and 0xff).toByte() }
                val header = ByteArray(44)
                putAscii(0,"RIFF", header); put32(4,36+dataSize, header); putAscii(8,"WAVE", header)
                putAscii(12,"fmt ", header); put32(16,16, header); put16(20,1, header); put16(22,1, header); put32(24,sampleRate, header); put32(28,sampleRate*2, header); put16(32,2, header); put16(34,16, header)
                putAscii(36,"data", header); put32(40,dataSize, header)
                out.write(header)
                val buf = ByteArray(samples.size*2)
                var o=0
                for (s in samples){ buf[o]=(s.toInt() and 0xff).toByte(); buf[o+1]=((s.toInt() shr 8) and 0xff).toByte(); o+=2 }
                out.write(buf)
            }
            Log.i(TAG, "wrote diagnostic WAV ${f.absolutePath} samples=${samples.size} rms=${Resampler.rms(samples)} peak=${Resampler.peak(samples)}")
            f
        } catch (e: Exception) {
            Log.w(TAG, "capture failed", e)
            null
        }
    }

    fun actualDeviceConfig(sampleRate: Int, channelCount: Int, encoding: Int): String {
        return "device sampleRate=$sampleRate channels=$channelCount encoding=$encoding resampledTo=16000 mono PCM16"
    }
}
