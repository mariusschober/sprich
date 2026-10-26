package com.sprich.app.storage

/**
 * Short-lived recovery for already-obtained text (plan §6.5). Pure JVM:
 * in-memory only, injectable clock for host tests.
 *
 * - Maximum one draft, [MAX_CHARS] (16,384) characters, [TTL_MS] (two-minute)
 *   lifetime. Longer input is truncated to the cap; expiry and every terminal
 *   transition clear the draft.
 * - Holds text only — never an editor token, InputConnection, field id, or
 *   audio. Insertion requires a new explicit user action plus fresh editor
 *   proof by the caller ([consume] returns the text once and clears it; it
 *   never auto-inserts).
 * - No automatic clipboard copy. The caller clears on lock ([onLock]),
 *   explicit cancel/discard ([discard]), expiry (lazy on access), process
 *   death (memory-only: nothing survives), and successful insertion
 *   ([consume]). The caller must never save password-field content here.
 */
class RecoveryDraftStore(
    private val maxChars: Int = MAX_CHARS,
    private val ttlMs: Long = TTL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var text: String? = null
    private var savedAtMs: Long = 0L

    /** Stores already-obtained text; blank input clears instead. Truncates to [maxChars]. */
    fun save(candidate: String, nowMs: Long = clockMs()): Boolean {
        synchronized(lock) {
            if (candidate.isBlank()) {
                text = null
                return false
            }
            text = if (candidate.length > maxChars) candidate.take(maxChars) else candidate
            savedAtMs = nowMs
            return true
        }
    }

    /** Live draft or null when absent/expired (expiry clears). Does not consume. */
    fun peek(nowMs: Long = clockMs()): String? = synchronized(lock) {
        val current = text ?: return null
        if (nowMs - savedAtMs > ttlMs) {
            text = null
            return null
        }
        current
    }

    fun hasDraft(nowMs: Long = clockMs()): Boolean = peek(nowMs) != null

    /**
     * Explicit user-action insertion: returns the live draft once and clears
     * it, so a second insert requires a fresh save. The caller must re-prove
     * editor authority before inserting.
     */
    fun consume(nowMs: Long = clockMs()): String? = synchronized(lock) {
        val current = text ?: return null
        if (nowMs - savedAtMs > ttlMs) {
            text = null
            return null
        }
        text = null
        current
    }

    fun discard() = synchronized(lock) { text = null }

    fun onLock() = synchronized(lock) { text = null }

    companion object {
        const val MAX_CHARS = 16_384
        const val TTL_MS = 120_000L
    }
}
