package com.sprich.app.ui.home

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U03 — card insert gate and cursor-preserving insertion. Pure JVM.
 */
class RecoveryInsertTest {

    @Test fun gate() {
        assertTrue(recoveryInsertTargetValid("hello", false))
        assertFalse(recoveryInsertTargetValid(null, false))
        assertFalse(recoveryInsertTargetValid("", false))
        assertFalse(recoveryInsertTargetValid("  ", false))
        assertFalse(recoveryInsertTargetValid("hello", true))
    }

    @Test fun insertsAtCollapsedCursor() {
        val cur = TextFieldValue("ab|cd".replace("|", ""), TextRange(2))
        val out = insertRecoveryDraft(cur, "X")
        assertEquals("abXcd", out.text)
        assertEquals(TextRange(3), out.selection)
    }

    @Test fun clampsCursor() {
        val cur = TextFieldValue("ab", TextRange(99))
        val out = insertRecoveryDraft(cur, "X")
        assertEquals("abX", out.text)
        assertEquals(TextRange(3), out.selection)
    }

    @Test fun selectionAppendsNeverReplaces() {
        val cur = TextFieldValue("abcd", TextRange(1, 3))
        val out = insertRecoveryDraft(cur, "X")
        assertEquals("abcdX", out.text)
        assertEquals(TextRange(5), out.selection)
    }

    @Test fun emptyField() {
        val out = insertRecoveryDraft(TextFieldValue(""), "hi")
        assertEquals("hi", out.text)
        assertEquals(TextRange(2), out.selection)
    }
}
