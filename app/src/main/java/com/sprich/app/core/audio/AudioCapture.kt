package com.sprich.app.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A recorder, reader and prebuffer have exactly one capture owner. */
class AudioCapture internal constructor(
    private val sampleRate: Int = 16000,
    private val ringSeconds: Int = 4,
    private val openRecorder: (Int) -> Recorder = ::androidRecorder,
) {
    internal interface Recorder {
        fun read(buffer: ShortArray): Int
        fun stop()
        fun release()
    }

    class Capture internal constructor(internal val recorder: Recorder, internal val buffer: AudioRingBuffer) {
        internal val stopped = AtomicBoolean(false)
        internal val cleanupLock = Any()
        internal var released = false
        internal lateinit var thread: Thread

        /** Only this capture's recorder is touched, even after another capture starts. */
        internal fun requestStop() {
            if (!stopped.compareAndSet(false, true)) return
            stopExecutor.execute {
                synchronized(cleanupLock) {
                    if (!released) runCatching { recorder.stop() }
                }
            }
        }

        fun awaitStop(timeoutMs: Long = 1000): Boolean {
            if (Thread.currentThread() === thread) return false
            thread.join(timeoutMs)
            return !thread.isAlive
        }
    }

    private data class State(val active: Capture? = null, val retired: Capture? = null)
    private val state = AtomicReference(State())
    @Volatile private var latestBuffer = AudioRingBuffer(sampleRate * ringSeconds)
    val ringBuffer: AudioRingBuffer get() = latestBuffer
    fun isActive(): Boolean = state.get().active?.stopped?.get() == false

    suspend fun startWithOffset(
        onChunkWithOffset: (ShortArray, Int, Int, Long, Float) -> Unit,
        onFailure: (String) -> Unit = {},
    ): Boolean {
        var created: Capture? = null
        return try {
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                startCapture(onChunkWithOffset, onFailure, { context.ensureActive() }, { created = it })
            }
        } catch (cancelled: CancellationException) {
            created?.let { owned ->
                state.updateAndGet { if (it.active === owned) State(retired = owned) else it }
                owned.requestStop()
            }
            throw cancelled
        }
    }

    @Synchronized private fun startCapture(
        onChunkWithOffset: (ShortArray, Int, Int, Long, Float) -> Unit,
        onFailure: (String) -> Unit,
        ensureActive: () -> Unit,
        onCreated: (Capture) -> Unit,
    ): Boolean {
        val expected = state.get()
        if (expected.active?.stopped?.get() == false) return true
        // No predecessor callback may still be mutating VAD/PCM when a new capture starts.
        if (expected.retired?.awaitStop(2000) == false) return false
        ensureActive()
        val recorder = try { openRecorder(sampleRate) } catch (_: Exception) { return false }
        val capture = Capture(recorder, AudioRingBuffer(sampleRate * ringSeconds))
        capture.thread = Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            val samples = ShortArray(1024)
            var failure: String? = null
            var emptyReads = 0
            try {
                while (!capture.stopped.get()) {
                    val n = recorder.read(samples)
                    if (capture.stopped.get()) break
                    // Every negative result is terminal, including ERROR_DEAD_OBJECT and ERROR.
                    if (n < 0 || n > samples.size) {
                        failure = "Microphone read failed"
                        break
                    }
                    if (n == 0) {
                        if (++emptyReads >= 100) { failure = "Microphone stopped responding"; break }
                        Thread.sleep(5)
                        continue
                    }
                    emptyReads = 0
                    if (state.get().active !== capture) break
                    var sum = 0.0
                    for (i in 0 until n) { val s = samples[i] / 32768.0; sum += s * s }
                    val rms = kotlin.math.sqrt(sum / n).toFloat()
                    capture.buffer.write(samples, 0, n)
                    onChunkWithOffset(samples, 0, n, SystemClock.elapsedRealtimeNanos(), rms)
                }
            } catch (_: Exception) {
                failure = "Microphone stopped"
            } finally {
                val previous = state.getAndUpdate { if (it.active === capture) State(retired = capture) else it }
                val reportFailure = !capture.stopped.get() && previous.active === capture
                capture.stopped.set(true)
                synchronized(capture.cleanupLock) {
                    runCatching { recorder.stop() }
                    runCatching { recorder.release() }
                    capture.released = true
                }
                capture.buffer.clear()
                if (reportFailure && failure != null) runCatching { onFailure(failure!!) }
            }
        }, "SprichAudio").apply { isDaemon = true }
        try {
            ensureActive()
            if (!state.compareAndSet(expected, State(capture, expected.retired))) {
                recorder.stop(); recorder.release()
                return false
            }
            onCreated(capture)
            latestBuffer = capture.buffer
            capture.thread.start()
            return true
        } catch (e: Exception) {
            state.updateAndGet { if (it.active === capture) State(retired = expected.retired) else it }
            runCatching { recorder.stop() }; runCatching { recorder.release() }
            if (e is CancellationException) throw e
            return false
        }
    }

    @Deprecated("Use startWithOffset; this adapter copies each chunk")
    suspend fun start(onChunk: (ShortArray, Long) -> Unit, onFailure: (String) -> Unit = {}): Boolean =
        startWithOffset({ samples, offset, length, time, _ -> onChunk(samples.copyOfRange(offset, offset + length), time) }, onFailure)

    /** Detach synchronously; stop and release run off Main. Retain this handle when awaiting cleanup. */
    fun requestStop(): Capture? {
        val previous = state.getAndUpdate { State(retired = it.active ?: it.retired) }
        return (previous.active ?: previous.retired)?.also { it.requestStop() }
    }
    fun stop() { requestStop()?.awaitStop() }
    fun release() = stop()
    fun snapshotPrebufferMs(ms: Int): ShortArray = latestBuffer.snapshotLast(ms.coerceAtLeast(0) / 1000f, sampleRate)

    companion object {
        private val stopExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "SprichAudioStop").apply { isDaemon = true } }

        @SuppressLint("MissingPermission")
        private fun androidRecorder(sampleRate: Int): Recorder {
            val channel = AudioFormat.CHANNEL_IN_MONO
            val format = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channel, format)
            check(minBuffer > 0)
            for (source in intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION)) {
                val record = runCatching { AudioRecord(source, sampleRate, channel, format, minBuffer * 2) }.getOrNull() ?: continue
                try {
                    check(record.state == AudioRecord.STATE_INITIALIZED)
                    record.startRecording()
                    check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    return object : Recorder {
                        override fun read(buffer: ShortArray) = record.read(buffer, 0, buffer.size)
                        override fun stop() = record.stop()
                        override fun release() = record.release()
                    }
                } catch (_: Exception) { runCatching { record.release() } }
            }
            error("Microphone unavailable")
        }
    }
}
