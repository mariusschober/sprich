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

    // New hot-path API — zero extra allocation, single RMS
    @SuppressLint("MissingPermission")
    fun startWithOffset(
        onChunkWithOffset: (samples: ShortArray, offset: Int, length: Int, timestampNanos: Long, rms: Float) -> Unit,
        onFailure: (String) -> Unit = {},
    ): Boolean {
        if (isCapturing.get()) return true
        try {
            val channel = AudioFormat.CHANNEL_IN_MONO
            val fmt = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, fmt)
            if (minBuf <= 0) {
                Log.e(TAG, "getMinBufferSize failed: $minBuf")
                return false
            }
            val bufSize = minBuf * 2
            var sourceUsed = MediaRecorder.AudioSource.MIC
            var candidate: AudioRecord? = null
            for (source in intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION)) {
                val attempt = try {
                    AudioRecord(source, sampleRate, channel, fmt, bufSize)
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord construction failed source=$source", e)
                    null
                }
                if (attempt?.state == AudioRecord.STATE_INITIALIZED) {
                    try {
                        attempt.startRecording()
                        if (attempt.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            candidate = attempt
                            sourceUsed = source
                            break
                        }
                        Log.w(TAG, "startRecording failed source=$source")
                    } catch (e: Exception) {
                        Log.w(TAG, "startRecording exception source=$source", e)
                    }
                }
                try { attempt?.release() } catch (_: Exception) {}
            }
            val rec = candidate
            if (rec == null) {
                Log.e(TAG, "AudioRecord not initialized for available sources")
                return false
            }
            ringBuffer.clear()
            record = rec
            isCapturing.set(true)
            Log.i(TAG, "capture started source=$sourceUsed sampleRate=$sampleRate bufferBytes=$bufSize")

            val thread = Thread({
                val readBuf = ShortArray(1024)
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                var failure: String? = null
                var loggedFirstFrame = false
                var healthFrames = 0
                var maxHealthRms = 0f
                try {
                    while (isCapturing.get() && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val n = try { rec.read(readBuf, 0, readBuf.size) } catch (e: IllegalStateException) {
                            failure = "AudioRecord read illegal state"
                            Log.w(TAG, failure, e)
                            break
                        } catch (e: Exception) {
                            failure = "AudioRecord read failed"
                            Log.w(TAG, failure, e)
                            break
                        }
                        if (n > 0) {
                            val now = SystemClock.elapsedRealtimeNanos()
                            // Single RMS for health + VAD + visual (no duplicate traverse)
                            val frameRms = rms(readBuf, 0, n)
                            // Ring buffer — single copy via System.arraycopy (no extra ShortArray allocation)
                            ringBuffer.write(readBuf, 0, n)
                            if (!loggedFirstFrame) {
                                loggedFirstFrame = true
                                if (Log.isLoggable(TAG, Log.INFO)) Log.i(TAG, "first audio frame samples=$n rms=$frameRms")
                            }
                            healthFrames++
                            maxHealthRms = maxOf(maxHealthRms, frameRms)
                            if (healthFrames >= 16) {
                                if (Log.isLoggable(TAG, Log.INFO)) Log.i(TAG, "audio health frames=$healthFrames maxRms=$maxHealthRms")
                                healthFrames = 0
                                maxHealthRms = 0f
                            }
                            try { onChunkWithOffset(readBuf, 0, n, now, frameRms) } catch (e: Exception) { Log.w(TAG, "onChunk error", e) }
                        } else if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                            failure = "AudioRecord read error $n"
                            Log.w(TAG, "AudioRecord read error $n")
                            break
                        }
                    }
                } catch (e: Exception) {
                    failure = "Audio thread failed"
                    Log.w(TAG, "audio thread outer", e)
                } finally {
                    if (isCapturing.compareAndSet(true, false)) {
                        try { rec.stop() } catch (_: Exception) {}
                        try { rec.release() } catch (_: Exception) {}
                        if (record === rec) record = null
                        failure?.let {
                            Log.e(TAG, it)
                            try { onFailure(it) } catch (_: Exception) {}
                        }
                    }
                    if (audioThread === Thread.currentThread()) audioThread = null
                }
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

    @Deprecated("Use startWithOffset — legacy path allocates per chunk")
    @SuppressLint("MissingPermission")
    fun start(
        onChunk: (ShortArray, Long) -> Unit,
        onFailure: (String) -> Unit = {},
    ): Boolean {
        // Delegate to zero-copy path to keep single implementation
        return startWithOffset(
            onChunkWithOffset = { samples, offset, length, ts, _ -> onChunk(samples.copyOfRange(offset, offset + length), ts) },
            onFailure = onFailure
        )
    }

    /** Synchronous cheap signal — no join, safe for main thread. */
    fun requestStop() {
        isCapturing.set(false)
        try { record?.stop() } catch (_: Exception) {}
    }

    fun awaitStop(timeoutMs: Long = 300) {
        try { audioThread?.join(timeoutMs) } catch (_: Exception) {}
        audioThread = null
    }

    fun stop() {
        // Deprecated blocking path — kept for tests only. Production must use requestStop() + awaitStop() off Main.
        // This still joins up to 300ms and must NEVER be called on Main thread.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            android.util.Log.w(TAG, "AudioCapture.stop() called on Main — use requestStop() + awaitStop off Main to avoid ANR")
        }
        val wasCapturing = isCapturing.getAndSet(false)
        val threadToJoin = audioThread
        val rec = record
        if (!wasCapturing && rec == null && threadToJoin == null) return
        try {
            rec?.stop()
        } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        record = null
        try { threadToJoin?.join(300) } catch (_: Exception) {}
        audioThread = null
        Log.i(TAG, "capture stopped")
    }

    fun snapshotPrebufferMs(ms: Int): ShortArray {
        val secs = ms / 1000f
        return ringBuffer.snapshotLast(secs, sampleRate)
    }

    fun release() { stop(); ringBuffer.clear() }

    private fun rms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (sample in samples) {
            val normalized = sample / 32768.0
            sum += normalized * normalized
        }
        return kotlin.math.sqrt(sum / samples.size).toFloat()
    }

    private fun rms(samples: ShortArray, offset: Int, length: Int): Float {
        if (length <= 0) return 0f
        var sum = 0.0
        val end = offset + length
        for (i in offset until end) {
            val normalized = samples[i] / 32768.0
            sum += normalized * normalized
        }
        return kotlin.math.sqrt(sum / length).toFloat()
    }
}
