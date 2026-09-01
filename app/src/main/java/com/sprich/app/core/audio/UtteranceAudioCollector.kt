package com.sprich.app.core.audio

/**
 * Engine-independent, bounded, primitive PCM collector for one utterance.
 *
 * Invariants (production-critical):
 * - primitive ShortArray storage — no MutableList<Short> boxing, no removeAt(0) shifts
 * - bounded memory (default 30s @ 16k = 480k samples); oldest dropped in O(1) chunk granularity on overflow
 * - preRoll inserted exactly once via begin() — clears previous utterance, appends one copy
 * - audio chunks appended exactly once via append() — no duplication of preRoll
 * - immutable frozen snapshot via freeze() — copyOf() isolates pending utterance; live buffer may be reused
 * - no ASR-engine ownership — neutral core/audio location, usable by FastConformer, Canary, future API STT
 * - no previous-utterance leakage — begin() clears, freeze() prevents further appends until begin/clear
 * - clear lifecycle — begin / append* / freeze / clear ; snapshot returns frozen if present else current
 *
 * Located in core/audio (neutral) — not under canary/fastconformer — so API STT and fallback can reuse same PCM.
 */
class UtteranceAudioCollector(
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

    /** Begin new utterance — clears previous, seeds exactly one copy of preRoll. */
    fun begin(preRoll: ShortArray) = synchronized(lock) {
        chunks.clear()
        size = 0
        frozen = null
        if (preRoll.isNotEmpty()) {
            appendInternal(preRoll)
        }
    }

    /** Alias matching spec — beginWithPreRoll delegate. */
    fun beginWithPreRoll(preRoll: ShortArray) = begin(preRoll)

    /** Append live audio — no-op if already frozen (preserves immutable snapshot). */
    fun append(samples: ShortArray) = synchronized(lock) {
        if (frozen != null) return
        if (samples.isEmpty()) return
        appendInternal(samples)
    }

    private fun appendInternal(samples: ShortArray) {
        // Strict bound: collector.size() <= maxSamples after EVERY operation, including oversized chunk > maxSamples.
        // Policy: keep final maxSamples portion deterministically.
        if (samples.size >= maxSamples) {
            // Oversized chunk — keep only its final maxSamples portion, drop everything else.
            chunks.clear()
            size = 0
            val tail = samples.copyOfRange(samples.size - maxSamples, samples.size)
            chunks.addLast(tail)
            size = maxSamples
            return
        }
        if (size + samples.size > maxSamples) {
            val overflow = size + samples.size - maxSamples
            dropOldest(overflow)
        }
        chunks.addLast(samples.copyOf())
        size += samples.size
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
                val keep = first.copyOfRange(remaining, first.size)
                chunks.removeFirst()
                chunks.addFirst(keep)
                size -= remaining
                remaining = 0
            }
        }
    }

    /** Freeze at endpoint — returns immutable copy, prevents further appends until begin/clear. */
    fun freeze(): ShortArray = synchronized(lock) {
        if (frozen != null) return frozen!!
        val out = toShortArrayLocked()
        frozen = out
        // Return isolated copy so caller cannot mutate frozen via returned reference
        out.copyOf()
    }

    /** Snapshot current contents — frozen if present, else current mutable copy (also isolated). */
    fun snapshot(): ShortArray = synchronized(lock) {
        val src = frozen ?: toShortArrayLocked()
        src.copyOf()
    }

    fun size(): Int = synchronized(lock) { frozen?.size ?: size }
    fun isFrozen(): Boolean = synchronized(lock) { frozen != null }
    fun isEmpty(): Boolean = synchronized(lock) { (frozen?.size ?: size) == 0 }

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
