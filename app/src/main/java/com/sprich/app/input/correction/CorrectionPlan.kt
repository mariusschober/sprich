package com.sprich.app.input.correction

/**
 * Pure correction planning for the §9.5 correction sheet (U02).
 *
 * JVM-pure: no Android imports. The IME owns the UI sheet, the dictation of
 * the replacement, and the actual InputConnection mutation; this module
 * decides only whether a replacement is provable and constructs the edit.
 *
 * Contract (mirrors EditorActionController's anchor discipline):
 * - The target span is revalidated against a FRESH editor snapshot at apply
 *   time. Any cursor move, host edit, app switch, or field/generation change
 *   rejects automatic replacement — review/copy only, on explicit user action.
 * - No fuzzy search: the span must match exactly at the recorded offset.
 *   There is deliberately no delete-and-retype heuristic against arbitrary
 *   surrounding text.
 * - Replacement text is spliced literally. This layer never interprets it:
 *   callers must pass already-parsed `ParsedDictation.Text` (a replacement
 *   containing "__DELETE_LAST__" inserts those exact characters).
 * - Undo is one step and itself revalidated ([canUndo]) against the exact
 *   previous span; failure clears the undo offer.
 * - A spelling proposal (old/new single-token pair) is exposed for the
 *   "Remember this spelling" offer; learning scope/inspection/removal live
 *   in the vocabulary UI, never auto-applied here.
 */
data class CorrectionTarget(
    val fieldId: String,
    val fieldGeneration: Long,
    val startOffset: Int,
    val text: String,
    val beforeAtOpen: String,
    val afterAtOpen: String,
)

data class EditorText(
    val before: String,
    val after: String,
)

data class UndoEdit(
    val startOffset: Int,
    val expectedCurrent: String,
    val restore: String,
)

data class SpellingProposal(val oldForm: String, val newForm: String)

sealed interface CorrectionResult {
    data class Apply(
        val newBefore: String,
        val newAfter: String,
        val undo: UndoEdit,
        val spellingProposal: SpellingProposal?,
        val alternatives: List<String>,
    ) : CorrectionResult

    data class Rejected(val reason: String) : CorrectionResult {
        companion object {
            const val FIELD_CHANGED = "field_changed"
            const val SPAN_MOVED = "span_moved"
            const val SPAN_MISMATCH = "span_mismatch"
        }
    }
}

object CorrectionPlan {

    fun planCorrection(
        target: CorrectionTarget,
        current: EditorText,
        currentFieldId: String?,
        currentFieldGeneration: Long,
        replacement: String,
        alternatives: List<String> = emptyList(),
    ): CorrectionResult {
        if (currentFieldId == null || currentFieldId != target.fieldId ||
            currentFieldGeneration != target.fieldGeneration
        ) {
            return CorrectionResult.Rejected(CorrectionResult.Rejected.FIELD_CHANGED)
        }
        if (current.before != target.beforeAtOpen || current.after != target.afterAtOpen) {
            return CorrectionResult.Rejected(CorrectionResult.Rejected.SPAN_MOVED)
        }
        val start = target.startOffset
        val end = start + target.text.length
        if (start < 0 || end > current.before.length) {
            return CorrectionResult.Rejected(CorrectionResult.Rejected.SPAN_MISMATCH)
        }
        if (current.before.substring(start, end) != target.text) {
            return CorrectionResult.Rejected(CorrectionResult.Rejected.SPAN_MISMATCH)
        }
        val newBefore = current.before.substring(0, start) + replacement + current.before.substring(end)
        val oldToken = target.text.trim()
        val newToken = replacement.trim()
        // Offered only for single-token user-approved corrections (e.g. a name
        // respelling); the vocabulary UI owns frequency, scope, and removal.
        val proposal =
            if (oldToken.isNotEmpty() && newToken.isNotEmpty() &&
                oldToken.none { it.isWhitespace() } && newToken.none { it.isWhitespace() } &&
                oldToken != newToken
            ) {
                SpellingProposal(oldToken, newToken)
            } else {
                null
            }
        return CorrectionResult.Apply(
            newBefore = newBefore,
            newAfter = current.after,
            undo = UndoEdit(start, replacement, target.text),
            spellingProposal = proposal,
            alternatives = alternatives.toList(),
        )
    }

    /** True when [undo] provably applies to this snapshot (offset + expected text). */
    fun canUndo(before: String, undo: UndoEdit): Boolean {
        val end = undo.startOffset + undo.expectedCurrent.length
        if (undo.startOffset < 0 || end > before.length) return false
        return before.substring(undo.startOffset, end) == undo.expectedCurrent
    }

    /**
     * Selects the [wordIndex]-th letter/digit run in [text].
     * Returns (startOffset, length), or null when out of range.
     */
    fun selectWord(text: String, wordIndex: Int): Pair<Int, Int>? {
        if (wordIndex < 0) return null
        var idx = 0
        var i = 0
        while (i < text.length) {
            while (i < text.length && !text[i].isLetterOrDigit()) i++
            if (i >= text.length) break
            val start = i
            while (i < text.length && text[i].isLetterOrDigit()) i++
            if (idx == wordIndex) return start to (i - start)
            idx++
        }
        return null
    }

    /**
     * U02: word under the cursor for the correction entry point. [before] is
     * the text before the cursor, [cursorInBefore] the cursor offset in it
     * (usually `before.length`). Returns (startOffset, length) in [before]
     * coordinates, or null when the cursor sits on no letter/digit run
     * (whitespace, emoji, empty). Same run definition as [selectWord].
     */
    fun wordAtCursor(before: String, cursorInBefore: Int): Pair<Int, Int>? {
        if (before.isEmpty()) return null
        val cursor = cursorInBefore.coerceIn(0, before.length)
        // Cursor between runs belongs to neither; a cursor at a run edge
        // belongs to that run (so tapping after a word still selects it).
        val candidates = listOf(cursor - 1, cursor).distinct().filter { it in before.indices }
        for (pos in candidates) {
            if (!before[pos].isLetterOrDigit()) continue
            var start = pos
            while (start > 0 && before[start - 1].isLetterOrDigit()) start--
            var end = pos
            while (end < before.length && before[end].isLetterOrDigit()) end++
            return start to (end - start)
        }
        return null
    }
}
