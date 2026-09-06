package com.sprich.app.core.audio

/**
 * P01 (plan §5.1): bounded startup PCM buffer + capture/recognizer readiness split.
 *
 * JVM-pure (no Android imports) so it compiles with the cached kotlinc harness.
 * Android wiring (AudioCapture feeding this before the engine is warm) needs
 * Gradle + device and is recorded BLOCKED in the child report.
 *
 * Contract:
 * - cap is 8 s of canonical 16 kHz mono PCM16 = 128,000 samples = 256,000 bytes.
 * - push-based only: no polling loop, busy wait, unbounded queue, or second recorder.
 * - each buffered sample is fed into the utterance exactly once ([drainOnce]).
 * - stale-generation invalidation: appends/drains with a mismatched generation are
 *   dropped, so a cancelled load continuation can never acquire capture authority.
 * - only the current authorized capture's samples are retained; callers must pass
 *   frames timestamped at capture time and never pre-permission audio.
 */
class StartupAudioBuffer(
    private val maxSamples: Int = STARTUP_BUFFER_MAX_SAMPLES,
) {
    companion object {
        const val SAMPLE_RATE_HZ = 16000
        const val STARTUP_BUFFER_SECONDS = 8
        const val STARTUP_BUFFER_MAX_SAMPLES = SAMPLE_RATE_HZ * STARTUP_BUFFER_SECONDS // 128,000
        const val BYTES_PER_SAMPLE = 2
        const val STARTUP_BUFFER_MAX_BYTES = STARTUP_BUFFER_MAX_SAMPLES * BYTES_PER_SAMPLE // 256,000
    }

    private val chunks = ArrayDeque<ShortArray>()
    private var size = 0
    private var armedGeneration: Long? = null
    private var drained = false
    private val lock = Any()

    /** Arm the buffer for one capture generation; clears any previous utterance. */
    fun begin(generation: Long) = synchronized(lock) {
        chunks.clear()
        size = 0
        armedGeneration = generation
        drained = false
    }

    /** Push capture frames; no-op unless [generation] matches the armed one. */
    fun append(samples: ShortArray, offset: Int, length: Int, generation: Long) = synchronized(lock) {
        if (generation != armedGeneration || drained) return
        if (length <= 0) return
        appendBounded(samples, offset, length)
    }

    fun append(samples: ShortArray, generation: Long) = append(samples, 0, samples.size, generation)

    private fun appendBounded(samples: ShortArray, offset: Int, length: Int) {
        if (length >= maxSamples) {
            chunks.clear()
            size = 0
            val tail = ShortArray(maxSamples)
            System.arraycopy(samples, offset + length - maxSamples, tail, 0, maxSamples)
            chunks.addLast(tail)
            size = maxSamples
            return
        }
        if (size + length > maxSamples) dropOldest(size + length - maxSamples)
        val copy = ShortArray(length)
        System.arraycopy(samples, offset, copy, 0, length)
        chunks.addLast(copy)
        size += length
    }

    private fun dropOldest(toDrop: Int) {
        var remaining = toDrop
        while (remaining > 0 && chunks.isNotEmpty()) {
            val first = chunks.first()
            if (first.size <= remaining) {
                chunks.removeFirst()
                size -= first.size
                remaining -= first.size
            } else {
                chunks.removeFirst()
                chunks.addFirst(first.copyOfRange(remaining, first.size))
                size -= remaining
                remaining = 0
            }
        }
    }

    /**
     * Drain all buffered samples exactly once for [generation]. The second call
     * (or a call with a stale generation) returns an empty array.
     */
    fun drainOnce(generation: Long): ShortArray = synchronized(lock) {
        if (generation != armedGeneration || drained) return ShortArray(0)
        drained = true
        val out = ShortArray(size)
        var off = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, off, c.size)
            off += c.size
        }
        chunks.clear()
        size = 0
        out
    }

    /** Invalidate so no resumed continuation may feed or drain old capture. */
    fun invalidate() = synchronized(lock) {
        chunks.clear()
        size = 0
        armedGeneration = null
        drained = true
    }

    fun sizeSamples(): Int = synchronized(lock) { size }
    fun sizeBytes(): Int = synchronized(lock) { size * BYTES_PER_SAMPLE }
    fun isArmed(): Boolean = synchronized(lock) { armedGeneration != null && !drained }
}

/**
 * P01 readiness split: `captureReady` (first real PCM arrived) is independent of
 * `recognizerReady` (local route loaded). A ready cue needs [captureReady]; the
 * "warming" cue needs only field focus + permission. Generation bump on
 * invalidate() retires stale native-load continuations.
 */
class CaptureReadiness {
    @Volatile var captureReady = false
        private set
    @Volatile var recognizerReady = false
        private set
    @Volatile var generation = 0L
        private set
    private val lock = Any()

    fun onFirstPcm(generation: Long) = synchronized(lock) {
        if (generation == this.generation) captureReady = true
    }

    fun onRecognizerLoaded(generation: Long) = synchronized(lock) {
        if (generation == this.generation) recognizerReady = true
    }

    fun invalidate() = synchronized(lock) {
        generation += 1
        captureReady = false
        recognizerReady = false
    }
}
