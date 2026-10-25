package com.sprich.app

import com.sprich.app.input.correction.CorrectionPlan
import com.sprich.app.input.correction.CorrectionResult
import com.sprich.app.input.correction.CorrectionTarget
import com.sprich.app.input.correction.EditorText
import com.sprich.app.input.correction.UndoContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E7 regression: correction undo is an editor mutation and must revalidate the field, its
 * generation and the password/PIN state immediately before mutating, exactly like every other
 * mutation path. Previously only the span was rechecked, so a stale sheet could splice its
 * restore text into another field when the text happened to match at the same offset.
 */
class CorrectionUndoRevalidationTest {

    private val target = CorrectionTarget(
        fieldId = "field_7",
        fieldGeneration = 42L,
        startOffset = 4,
        text = "H",
        beforeAtOpen = "Say H there",
        afterAtOpen = "",
    )

    private fun appliedUndo(): CorrectionResult.Apply {
        val result = CorrectionPlan.planCorrection(
            target = target,
            current = EditorText("Say H there", ""),
            currentFieldId = target.fieldId,
            currentFieldGeneration = target.fieldGeneration,
            replacement = "Hello",
        )
        return result as CorrectionResult.Apply
    }

    @Test
    fun sameFieldGenerationAndSpanApplies() {
        val apply = appliedUndo()
        assertTrue(
            CorrectionPlan.undoApplies(
                apply.undo,
                apply.newBefore,
                UndoContext(target.fieldId, target.fieldGeneration, isPasswordField = false),
            ),
        )
    }

    @Test
    fun anotherFieldNeverReceivesTheRestoreText() {
        val apply = appliedUndo()
        val otherFieldSameText = "Say Hello there" // span matches, but field id differs
        assertFalse(
            CorrectionPlan.undoApplies(
                apply.undo,
                otherFieldSameText,
                UndoContext("field_8", target.fieldGeneration, isPasswordField = false),
            ),
        )
    }

    @Test
    fun anotherGenerationOfTheSameFieldIsRefused() {
        val apply = appliedUndo()
        assertFalse(
            CorrectionPlan.undoApplies(
                apply.undo,
                apply.newBefore,
                UndoContext(target.fieldId, target.fieldGeneration + 1, isPasswordField = false),
            ),
        )
    }

    @Test
    fun passwordAndPinFieldsAreNeverMutated() {
        val apply = appliedUndo()
        assertFalse(
            CorrectionPlan.undoApplies(
                apply.undo,
                apply.newBefore,
                UndoContext(target.fieldId, target.fieldGeneration, isPasswordField = true),
            ),
        )
    }

    @Test
    fun missingFieldOrMovedTextIsRefused() {
        val apply = appliedUndo()
        assertFalse(
            CorrectionPlan.undoApplies(
                apply.undo,
                apply.newBefore,
                UndoContext(null, target.fieldGeneration, isPasswordField = false),
            ),
        )
        assertFalse(
            CorrectionPlan.undoApplies(
                apply.undo,
                "Say Goodbye there",
                UndoContext(target.fieldId, target.fieldGeneration, isPasswordField = false),
            ),
        )
    }
}
