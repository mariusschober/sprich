package com.sprich.app.ui.home

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * U03 recovery insert (plan §9.6): the Home review card offers insertion of
 * the kept draft into the newly selected valid field on explicit tap.
 * Never replaces existing text (non-collapsed selection appends at end),
 * never auto-sends, never touches the clipboard. Pure JVM logic; the caller
 * consumes the draft only after applying the returned value.
 */
fun recoveryInsertTargetValid(draft: String?, isPasswordField: Boolean): Boolean =
    !draft.isNullOrBlank() && !isPasswordField

fun insertRecoveryDraft(current: TextFieldValue, draft: String): TextFieldValue {
    require(draft.isNotEmpty())
    val sel = current.selection
    val at = if (sel.collapsed) sel.start.coerceIn(0, current.text.length) else current.text.length
    val next = current.text.substring(0, at) + draft + current.text.substring(at)
    return current.copy(text = next, selection = TextRange(at + draft.length))
}
