package com.sprich.app.input.lifecycle

/**
 * Bounded exactly-once claim set for utterance finalization (plan §6.5, Gate C).
 *
 * Pure JVM: the IME delegates its `finalizedUtterances` bookkeeping here so
 * the at-most-once mutation property is host-testable. Claiming is atomic:
 * the first claimant for an id wins, every later claimant for the same id
 * drops. History is bounded to [MAX_CLAIMS] recent ids (insertion-ordered
 * eviction); ids evicted from history may be claimed again, which is why
 * callers must also scope claims by session/field generation.
 */
class FinalizationClaims(private val maxClaims: Int = MAX_CLAIMS) {
    private val lock = Any()
    private val claimed = LinkedHashSet<Long>()

    /** Returns true for the first claim of [id], false for duplicates. */
    fun claim(id: Long): Boolean = synchronized(lock) {
        if (claimed.contains(id)) return false
        claimed.add(id)
        while (claimed.size > maxClaims) {
            val it = claimed.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
        true
    }

    fun contains(id: Long): Boolean = synchronized(lock) { claimed.contains(id) }

    fun size(): Int = synchronized(lock) { claimed.size }

    fun clear() = synchronized(lock) { claimed.clear() }

    companion object {
        const val MAX_CLAIMS = 128
    }
}
