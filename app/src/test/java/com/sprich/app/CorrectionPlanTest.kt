package com.sprich.app

import com.sprich.app.input.correction.CorrectionPlan
import com.sprich.app.input.correction.CorrectionResult
import com.sprich.app.input.correction.CorrectionTarget
import com.sprich.app.input.correction.EditorText
import org.junit.Assert.*
import org.junit.Test

/**
 * U02 (§9.5) correction planning: exact-span revalidation, literal
 * replacement splicing, one-step undo proof, word selection, cursor-word
 * targeting, and the spelling-proposal offer. The Compose sheet + IME
 * mutation execute exactly this logic (CorrectionSheet + SprichIME wiring).
 */
class CorrectionPlanTest {

    private fun target(
        before: String = "Call Alice tomorrow",
        start: Int = 5,
        text: String = "Alice",
        field: String = "f1",
        gen: Long = 7L,
    ) = CorrectionTarget(field, gen, start, text, before, "")

    private fun snap(before: String = "Call Alice tomorrow") = EditorText(before, "")

    @Test
    fun genuineOwnedCorrectionAppliesWithUndo() {
        val r = CorrectionPlan.planCorrection(target(), snap(), "f1", 7L, "Alicia")
        assertTrue(r is CorrectionResult.Apply)
        val apply = r as CorrectionResult.Apply
        assertEquals("Call Alicia tomorrow", apply.newBefore)
        assertEquals("", apply.newAfter)
        assertTrue(CorrectionPlan.canUndo(apply.newBefore, apply.undo))
    }

    @Test
    fun fieldChangeRejects() {
        val r = CorrectionPlan.planCorrection(target(), snap(), "f2", 7L, "Alicia")
        assertTrue(r is CorrectionResult.Rejected)
        assertEquals(CorrectionResult.Rejected.FIELD_CHANGED, (r as CorrectionResult.Rejected).reason)
        val r2 = CorrectionPlan.planCorrection(target(), snap(), "f1", 8L, "Alicia")
        assertEquals(CorrectionResult.Rejected.FIELD_CHANGED, (r2 as CorrectionResult.Rejected).reason)
        assertEquals(CorrectionResult.Rejected.FIELD_CHANGED,
            (CorrectionPlan.planCorrection(target(), snap(), null, 7L, "Alicia") as CorrectionResult.Rejected).reason)
    }

    @Test
    fun cursorMoveOrHostEditRejects() {
        val moved = CorrectionPlan.planCorrection(target(), EditorText("Call Alice tomorrow!", ""), "f1", 7L, "Alicia")
        assertEquals(CorrectionResult.Rejected.SPAN_MOVED, (moved as CorrectionResult.Rejected).reason)
    }

    @Test
    fun spanMismatchRejectsWithoutFuzzySearch() {
        // Same surrounding text but the word itself changed underneath: no heuristic repair.
        val t = target(before = "Call Alicia tomorrow", text = "Alice")
        val r = CorrectionPlan.planCorrection(t, snap("Call Alicia tomorrow"), "f1", 7L, "Alicia")
        assertEquals(CorrectionResult.Rejected.SPAN_MISMATCH, (r as CorrectionResult.Rejected).reason)
        // Out-of-bounds offset never reads outside the snapshot.
        val oob = CorrectionPlan.planCorrection(target(start = 500), snap(), "f1", 7L, "x")
        assertEquals(CorrectionResult.Rejected.SPAN_MISMATCH, (oob as CorrectionResult.Rejected).reason)
    }

    @Test
    fun replacementIsSplicedLiterallyNeverInterpreted() {
        val r = CorrectionPlan.planCorrection(target(), snap(), "f1", 7L, "__DELETE_LAST__")
        assertTrue(r is CorrectionResult.Apply)
        assertEquals("Call __DELETE_LAST__ tomorrow", (r as CorrectionResult.Apply).newBefore)
    }

    @Test
    fun undoRequiresExactPreviousSpan() {
        val r = CorrectionPlan.planCorrection(target(), snap(), "f1", 7L, "Alicia") as CorrectionResult.Apply
        assertTrue(CorrectionPlan.canUndo(r.newBefore, r.undo))
        assertFalse(CorrectionPlan.canUndo("Call Xlicia tomorrow", r.undo))
        assertFalse(CorrectionPlan.canUndo("Call Al tomorrow", r.undo))
    }

    @Test
    fun spellingProposalOfferedForSingleTokenRespellingOnly() {
        val r = CorrectionPlan.planCorrection(target(), snap(), "f1", 7L, "Alicia") as CorrectionResult.Apply
        assertEquals("Alice", r.spellingProposal?.oldForm)
        assertEquals("Alicia", r.spellingProposal?.newForm)
        val multi = CorrectionPlan.planCorrection(
            target(text = "Alice tomorrow", start = 5), snap(), "f1", 7L, "Alicia today",
        ) as CorrectionResult.Apply
        assertNull(multi.spellingProposal)
        val same = CorrectionPlan.planCorrection(target(), snap(), "f1", 7L, "Alice") as CorrectionResult.Apply
        assertNull(same.spellingProposal)
    }

    @Test
    fun alternativesCarriedOnlyWhenProvided() {
        val none = CorrectionPlan.planCorrection(target(), snap(), "f1", 7L, "Alicia") as CorrectionResult.Apply
        assertTrue(none.alternatives.isEmpty())
        val some = CorrectionPlan.planCorrection(target(), snap(), "f1", 7L, "Alicia", listOf("Alisha", "Elicia"))
        assertEquals(listOf("Alisha", "Elicia"), (some as CorrectionResult.Apply).alternatives)
    }

    @Test
    fun selectWordFindsLetterDigitRuns() {
        assertEquals(5 to 5, CorrectionPlan.selectWord("Call Alice tomorrow", 1))
        assertEquals(0 to 4, CorrectionPlan.selectWord("Call Alice tomorrow", 0))
        assertEquals(11 to 8, CorrectionPlan.selectWord("Call Alice tomorrow", 2))
        assertNull(CorrectionPlan.selectWord("Call Alice tomorrow", 3))
        assertNull(CorrectionPlan.selectWord("Call Alice tomorrow", -1))
        assertNull(CorrectionPlan.selectWord("   ", 0))
        assertEquals(0 to 3, CorrectionPlan.selectWord("Jon's", 0))
    }

    @Test
    fun wordAtCursorTargetsCursorWord() {
        // Inside and at both edges of a run.
        assertEquals(5 to 5, CorrectionPlan.wordAtCursor("Call Alice", 7))
        assertEquals(5 to 5, CorrectionPlan.wordAtCursor("Call Alice", 5))
        assertEquals(5 to 5, CorrectionPlan.wordAtCursor("Call Alice", 10))
        assertEquals(0 to 4, CorrectionPlan.wordAtCursor("Call Alice", 0))
        // Between runs: trailing edge wins left, leading edge wins right.
        assertEquals(0 to 4, CorrectionPlan.wordAtCursor("Call Alice", 4))
        assertEquals(0 to 2, CorrectionPlan.wordAtCursor("hi you", 2))
        assertEquals(3 to 3, CorrectionPlan.wordAtCursor("hi you", 3))
        // No run under cursor.
        assertNull(CorrectionPlan.wordAtCursor("", 0))
        assertNull(CorrectionPlan.wordAtCursor("   ", 2))
        assertNull(CorrectionPlan.wordAtCursor("hi  ", 4))
        assertNull(CorrectionPlan.wordAtCursor("😀", 1))
    }
}
