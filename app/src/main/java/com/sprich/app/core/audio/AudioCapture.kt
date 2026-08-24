package com.sprich.app.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-latency 16k mono PCM capture with ring buffer pre-buffer.
 * No allocations per callback. Monotonic timestamps.
 */
class AudioCapture(
    private val sampleRate: Int = 16000,
    private val ringSeconds: Int = 4,
) {
    companion object { private const val TAG = "AudioCapture" }

    @Volatile private var record: AudioRecord? = null
    private val isCapturing = AtomicBoolean(false)
    @Volatile private var audioThread: Thread? = null
    val ringBuffer = AudioRingBuffer(sampleRate * ringSeconds)

    // Pre-buffer: keep last N ms even before session, so first phoneme not clipped.
    // Ring buffer always holds last ringSeconds; start() clears then fills.

    fun isActive(): Boolean = isCapturing.get() && record?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    @SuppressLint("MissingPermission")
    fun start(onChunk: (ShortArray, Long) -> Unit): Boolean {
        if (isCapturing.get()) return true
        try {
            val channel = AudioFormat.CHANNEL_IN_MONO
            val fmt = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, fmt)
            if (minBuf <= 0) {
                Log.e(TAG, "getMinBufferSize failed: $minBuf")
                return false
            }
            // Low-latency: 2x min is optimal; avoid forcing 1s buffer
            val bufSize = minBuf * 2
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, channel, fmt, bufSize
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                rec.release()
                return false
            }
            ringBuffer.clear()
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "startRecording failed")
                rec.release()
                return false
            }
            record = rec
            isCapturing.set(true)

            val thread = Thread({
                val readBuf = ShortArray(1024)
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                try {
                    while (isCapturing.get() && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val n = try { rec.read(readBuf, 0, readBuf.size) } catch (e: IllegalStateException) {
                            Log.w(TAG, "read IllegalState after release", e); break
                        } catch (e: Exception) { Log.w(TAG, "read error", e); break }
                        if (n > 0) {
                            val now = SystemClock.elapsedRealtimeNanos()
                            val copy = readBuf.copyOf(n)
                            ringBuffer.write(copy)
                            try { onChunk(copy, now) } catch (e: Exception) { Log.w(TAG, "onChunk error", e) }
                        } else if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                            Log.w(TAG, "AudioRecord read error $n")
                            break
                        }
                    }
                } catch (e: Exception) { Log.w(TAG, "audio thread outer", e) }
            }, "SprichAudio")
            thread.isDaemon = true
            audioThread = thread
            thread.start()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            isCapturing.set(false)
            try { record?.release() } catch (_: Exception) {}
            record = null
            return false
        }
    }

    fun stop() {
        if (!isCapturing.getAndSet(false)) return
        val threadToJoin = audioThread
        try {
            record?.stop()
        } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        try { threadToJoin?.join(300) } catch (_: Exception) {}
        audioThread = null
    }

    fun snapshotPrebufferMs(ms: Int): ShortArray {
        val secs = ms / 1000f
        return ringBuffer.snapshotLast(secs, sampleRate)
    }

    fun release() { stop(); ringBuffer.clear() }
}
