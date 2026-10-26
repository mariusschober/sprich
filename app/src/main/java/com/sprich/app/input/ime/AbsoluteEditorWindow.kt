package com.sprich.app.input.ime

/**
 * Absolute-offset editor window used by the correction sheet.
 *
 * [readEditorSnapshot][com.sprich.app.input.ime.SprichIME] needs absolute cursor offsets to
 * revalidate and splice an exact span. `ExtractedText` is authoritative only when it describes
 * the whole field: a positive `startOffset` or a non-negative `partialStartOffset` means the
 * host returned a window of the text, and treating that window as the field would splice at
 * wrong absolute offsets (the primary `EditorSnapshot.read` path already fails closed here).
 * Returning null lets the caller fall back to the capped before/after read, which refuses
 * unknown absolute offsets instead of guessing.
 */
internal data class AbsoluteEditorWindow(
    val before: String,
    val after: String,
    val cursorAbsolute: Int,
    val collapsed: Boolean,
)

internal fun absoluteWindowFromExtracted(
    text: String,
    startOffset: Int,
    partialStartOffset: Int,
    selectionStart: Int,
    selectionEnd: Int,
): AbsoluteEditorWindow? {
    if (partialStartOffset >= 0 || startOffset != 0) return null
    val selStart = selectionStart.coerceIn(0, text.length)
    val selEnd = selectionEnd.coerceIn(0, text.length)
    return AbsoluteEditorWindow(
        before = text.substring(0, selStart),
        after = text.substring(selStart),
        cursorAbsolute = selStart,
        collapsed = selStart == selEnd,
    )
}
