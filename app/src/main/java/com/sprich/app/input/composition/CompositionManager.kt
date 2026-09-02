package com.sprich.app.input.composition

import android.view.inputmethod.InputConnection

/** Partial text stays in the IME. A final makes one commitText call, with no retry. */
class CompositionManager {
    var lastCommittedText: String? = null
        private set
    fun reset() { lastCommittedText = null }

    fun applyUpdate(ic: InputConnection?, stable: String, unstable: String, isFinal: Boolean): Boolean {
        if (ic == null) return false
        val text = listOf(stable, unstable).filter { it.isNotEmpty() }.joinToString(" ").trim(' ', '\t')
        if (!isFinal) return text.isEmpty()
        lastCommittedText = null
        if (text.isEmpty()) return true
        val before = try { ic.getTextBeforeCursor(1, 0)?.toString() } catch (_: Exception) { null }
        val selected = try { ic.getSelectedText(0) } catch (_: Exception) { null }
        val needsSpace = selected.isNullOrEmpty() && !before.isNullOrEmpty() && !before.last().isWhitespace() && !text.first().isWhitespace() && text.first() !in ".,;:?!)]}"
        val insertion = if (needsSpace) " $text" else text
        val ok = try { ic.commitText(insertion, 1) } catch (_: Exception) { false }
        if (ok) lastCommittedText = insertion
        return ok
    }
    fun discardPartial(ic: InputConnection?) = reset()
    fun finishIfActive(ic: InputConnection?) = reset()
    fun hasComposing() = false
    fun onCursorMoved() = reset()
    fun commitFinal(ic: InputConnection?, text: String) = applyUpdate(ic, text, "", true)
}
