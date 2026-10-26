package com.sprich.app.diagnostics

/**
 * Once-per-(utterance, event-type) emission guard for utterance telemetry
 * (plan §6.4, P07).
 *
 * Pure JVM: progress callbacks (remote preview/stage) fire repeatedly for one
 * utterance, but boundary events (first preview, provider final, connect)
 * must be recorded once. The IME routes all repeatable emissions through
 * [shouldEmit]; single-fire sites (onset, recognition end) need no guard but
 * may use it for uniformity. Bounded to [MAX_TRACKED] pairs (insertion-order
 * eviction); callers must scope follow-up logic by session/field generation,
 * not by this guard.
 */
class TelemetryStageGuard(private val maxTracked: Int = MAX_TRACKED) {
    private val lock = Any()
    private val emitted = LinkedHashSet<Pair<Long, TelemetryEventType>>()

    /** Returns true exactly once per ([utteranceId], [type]) while tracked. */
    fun shouldEmit(utteranceId: Long, type: TelemetryEventType): Boolean = synchronized(lock) {
        val key = utteranceId to type
        if (emitted.contains(key)) return false
        emitted.add(key)
        while (emitted.size > maxTracked) {
            val it = emitted.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
        true
    }

    fun size(): Int = synchronized(lock) { emitted.size }

    fun clear() = synchronized(lock) { emitted.clear() }

    companion object {
        const val MAX_TRACKED = 256
    }
}
