package com.sprich.app.core.audio

/**
 * Primitive, bounded, per-utterance PCM buffer.
 * Owns exactly one utterance: preRoll + subsequent speech, frozen at endpoint.
 * Requirements: no boxing per sample, O(1) amortized append, bounded duration,
 * immutable frozen snapshot, exact preRoll exactly once.
 *
 * Implemented as a chunked buffer of ShortArray blocks to avoid boxing and
 * avoid expensive removeAt(0) shifts. Capacity bounded to maxSamples (default 30s@16k).
 * Oldest samples dropped in O(1) chunk granularity when overflow would occur.
 */
class UtterancePcmBuffer(
    private val maxSamples: Int = 16000 * 30,
) {
    private val chunks = ArrayDeque<ShortArray>()
    private var size = 0
    private var frozen: ShortArray? = null
    private val lock = Any()

    fun clear() = synchronized(lock) {
        chunks.clear()
        size = 0
        frozen = null
    }

    /** Seed buffer with preRoll — clears previous utterance and appends exactly once. */
    fun beginWithPreRoll(preRoll: ShortArray) = synchronized(lock) {
        chunks.clear()
        size = 0
        frozen = null
        if (preRoll.isNotEmpty()) {
            appendInternal(preRoll)
        }
    }

    /** Append subsequent live audio — no-op if already frozen. */
    fun append(samples: ShortArray) = append(samples, 0, samples.size)

    fun append(samples: ShortArray, offset: Int, length: Int) = synchronized(lock) {
        if (frozen != null) return
        if (length <= 0) return
        appendInternal(samples, offset, length)
    }

    private fun appendInternal(samples: ShortArray, offset: Int = 0, length: Int = samples.size) {
        // Strict bound including oversized single chunk (> maxSamples): keep final maxSamples portion of window
        val effSize = if (offset == 0 && length == samples.size) samples.size else length
        if (effSize >= maxSamples) {
            chunks.clear()
            size = 0
            val tail = ShortArray(maxSamples)
            if (offset == 0 && length == samples.size) {
                System.arraycopy(samples, samples.size - maxSamples, tail, 0, maxSamples)
            } else {
                System.arraycopy(samples, offset + effSize - maxSamples, tail, 0, maxSamples)
            }
            chunks.addLast(tail)
            size = maxSamples
            return
        }
        if (size + effSize > maxSamples) {
            val overflow = size + effSize - maxSamples
            dropOldest(overflow)
        }
        val copy = ShortArray(effSize)
        System.arraycopy(samples, offset, copy, 0, effSize)
        chunks.addLast(copy)
        size += effSize
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
                // Split first chunk: keep tail
                val keep = first.copyOfRange(remaining, first.size)
                chunks.removeFirst()
                chunks.addFirst(keep)
                size -= remaining
                remaining = 0
            }
        }
    }

    /** Freeze at endpoint — returns immutable snapshot and prevents further appends. */
    fun freeze(): ShortArray = synchronized(lock) {
        if (frozen != null) return frozen!!
        val out = toShortArrayLocked()
        frozen = out
        out
    }

    /** Snapshot of current contents — frozen if present, else current mutable copy. */
    fun snapshot(): ShortArray = synchronized(lock) {
        frozen ?: toShortArrayLocked()
    }

    /** Current size, or frozen size if frozen. */
    fun size(): Int = synchronized(lock) { frozen?.size ?: size }

    /** Whether already frozen. */
    fun isFrozen(): Boolean = synchronized(lock) { frozen != null }

    private fun toShortArrayLocked(): ShortArray {
        if (size == 0) return ShortArray(0)
        val out = ShortArray(size)
        var off = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, off, c.size)
            off += c.size
        }
        return out
    }
}
