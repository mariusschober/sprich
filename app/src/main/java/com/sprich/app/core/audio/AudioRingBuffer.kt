package com.sprich.app.core.audio

/**
 * Lock-free-ish ring buffer for 16k mono PCM16.
 * Capacity in samples. Overwrites oldest on overflow.
 * Single writer (audio thread), single reader (ASR thread) safe with volatile indices.
 */
class AudioRingBuffer(capacitySamples: Int = 16000 * 5) {
    private val cap = capacitySamples
    private val buf = ShortArray(cap)
    @Volatile private var writePos = 0
    @Volatile private var readPos = 0
    private var size = 0
    @Volatile private var totalWritten: Long = 0

    @Synchronized
    fun write(samples: ShortArray, offset: Int = 0, len: Int = samples.size - offset) {
        var remaining = len
        var srcOff = offset
        // O(1) deficit: if incoming > writable, drop oldest in one step
        val writable = cap - size
        if (remaining > writable) {
            val drop = remaining - writable
            readPos = (readPos + drop) % cap
            size -= drop
        }
        while (remaining > 0) {
            val contiguousSpace = cap - writePos
            val toCopy = minOf(remaining, cap - size, contiguousSpace)
            System.arraycopy(samples, srcOff, buf, writePos, toCopy)
            writePos = (writePos + toCopy) % cap
            srcOff += toCopy
            remaining -= toCopy
            size += toCopy
            totalWritten += toCopy
        }
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
        val want = (seconds * sampleRate).toInt().coerceAtMost(size)
        if (want == 0) return ShortArray(0)
        val out = ShortArray(want)
        val start = ((writePos - size + (size - want)) % cap + cap) % cap
        val first = minOf(want, cap - start)
        System.arraycopy(buf, start, out, 0, first)
        if (want > first) System.arraycopy(buf, 0, out, first, want - first)
        return out
    }

    @Synchronized
    fun clear() {
        writePos = 0; readPos = 0; size = 0; totalWritten = 0
    }

    @Synchronized fun available(): Int = size
    fun capacity(): Int = cap
}
