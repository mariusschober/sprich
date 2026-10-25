package com.sprich.app.input.ime

/**
 * Selections that Sprich itself produced through an editor mutation.
 *
 * The framework reports every selection change through
 * `InputMethodService.onUpdateSelection`, including the ones caused by our own `commitText` +
 * `setSelection`. Without bookkeeping those callbacks look like an external cursor move and the
 * IME cancels its own field session. The registry records the post-edit selections; a matching
 * callback is consumed (no cancellation), an unrelated one is not.
 *
 * Bounded like every other selection history so a buggy host cannot grow it without limit.
 */
internal class OwnSelectionRegistry(private val maxEntries: Int = 16) {
    private val entries = ArrayDeque<Pair<Int, Int>>()

    fun note(selectionStart: Int, selectionEnd: Int) {
        entries.addLast(selectionStart to selectionEnd)
        while (entries.size > maxEntries) entries.removeFirst()
    }

    /**
     * Removes and returns true when [selectionStart]..[selectionEnd] is a selection Sprich made.
     * Matching is exact; entries noted before the match are dropped so the next unrelated
     * callback cannot be swallowed by stale history.
     */
    fun consumeIfOwned(selectionStart: Int, selectionEnd: Int): Boolean {
        val index = entries.indexOfLast { it == (selectionStart to selectionEnd) }
        if (index < 0) return false
        repeat(index + 1) { entries.removeFirst() }
        return true
    }

    fun clear() = entries.clear()

    fun size(): Int = entries.size
}
