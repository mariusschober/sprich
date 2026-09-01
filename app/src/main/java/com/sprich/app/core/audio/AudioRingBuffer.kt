package com.sprich.app.core.audio

/**
 * Bounded ring buffer for mono PCM16.
 * Capacity is measured in samples and the oldest samples are overwritten on overflow.
 * All mutations are synchronized because audio capture and decoding run on different threads.
 */
class AudioRingBuffer(capacitySamples: Int = 16000 * 5) {
    init {
        require(capacitySamples > 0) { "capacitySamples must be positive" }
    }

    private val cap = capacitySamples
    private val buf = ShortArray(cap)
    private var writePos = 0
    private var readPos = 0
    private var size = 0
    private var totalWritten: Long = 0

    @Synchronized
    fun write(samples: ShortArray, offset: Int = 0, len: Int = samples.size - offset) {
        require(offset >= 0) { "offset must be non-negative" }
        require(len >= 0) { "len must be non-negative" }
        require(offset <= samples.size - len) { "offset + len exceeds source array" }
        if (len == 0) return

        // An input block can be larger than the whole ring (for example a restored recording).
        // Keep only its newest capacity-sized tail in one bounded copy.
        if (len >= cap) {
            val tailOffset = offset + len - cap
            System.arraycopy(samples, tailOffset, buf, 0, cap)
            readPos = 0
            writePos = 0
            size = cap
            totalWritten += len
            return
        }

        val overflow = (size + len - cap).coerceAtLeast(0)
        if (overflow > 0) {
            readPos = (readPos + overflow) % cap
            size -= overflow
        }

        var remaining = len
        var sourceOffset = offset
        while (remaining > 0) {
            val toCopy = minOf(remaining, cap - writePos)
            System.arraycopy(samples, sourceOffset, buf, writePos, toCopy)
            writePos = (writePos + toCopy) % cap
            sourceOffset += toCopy
            remaining -= toCopy
            size += toCopy
        }
        totalWritten += len
    }

    @Synchronized
    fun drain(dst: ShortArray): Int {
        val n = minOf(size, dst.size)
        if (n == 0) return 0
        val first = minOf(n, cap - readPos)
        System.arraycopy(buf, readPos, dst, 0, first)
        if (n > first) System.arraycopy(buf, 0, dst, first, n - first)
        readPos = (readPos + n) % cap
        size -= n
        return n
    }

    @Synchronized
    fun snapshotLast(seconds: Float, sampleRate: Int = 16000): ShortArray {
        require(seconds >= 0f && seconds.isFinite()) { "seconds must be finite and non-negative" }
        require(sampleRate > 0) { "sampleRate must be positive" }
        val requested = (seconds * sampleRate).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return snapshotLastSamplesLocked(requested)
    }

    @Synchronized
    fun snapshotLastSamples(sampleCount: Int): ShortArray {
        require(sampleCount >= 0) { "sampleCount must be non-negative" }
        return snapshotLastSamplesLocked(sampleCount)
    }

    private fun snapshotLastSamplesLocked(sampleCount: Int): ShortArray {
        val want = sampleCount.coerceAtMost(size)
        if (want == 0) return ShortArray(0)
        val out = ShortArray(want)
        val start = ((writePos - want) % cap + cap) % cap
        val first = minOf(want, cap - start)
        System.arraycopy(buf, start, out, 0, first)
        if (want > first) System.arraycopy(buf, 0, out, first, want - first)
        return out
    }

    @Synchronized
    fun clear() {
        writePos = 0
        readPos = 0
        size = 0
        totalWritten = 0
    }

    @Synchronized fun available(): Int = size
    fun capacity(): Int = cap
}
