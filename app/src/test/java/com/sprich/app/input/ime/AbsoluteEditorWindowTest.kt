package com.sprich.app.input.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsoluteEditorWindowTest {

    @Test
    fun wholeFieldExtractionYieldsAbsoluteOffsets() {
        val w = absoluteWindowFromExtracted(
            text = "hello world",
            startOffset = 0,
            partialStartOffset = -1,
            selectionStart = 5,
            selectionEnd = 5,
        )!!
        assertEquals("hello", w.before)
        assertEquals(" world", w.after)
        assertEquals(5, w.cursorAbsolute)
        assertTrue(w.collapsed)
    }

    @Test
    fun selectionRangeIsNotCollapsed() {
        val w = absoluteWindowFromExtracted("hello world", 0, -1, 0, 5)!!
        assertFalse(w.collapsed)
    }

    @Test
    fun partialExtractionIsRejected() {
        assertNull(absoluteWindowFromExtracted("window of text", 0, 100, 5, 5))
        assertNull(absoluteWindowFromExtracted("window of text", 0, 0, 5, 5))
    }

    @Test
    fun jumpExtractionIsRejected() {
        // startOffset > 0 means the text is a slice of the field; treating it as the whole
        // field would compute wrong absolute splice offsets.
        assertNull(absoluteWindowFromExtracted("slice", 100, -1, 2, 2))
    }

    @Test
    fun selectionIsClampedToText() {
        val w = absoluteWindowFromExtracted("abc", 0, -1, 99, 99)!!
        assertEquals("abc", w.before)
        assertEquals("", w.after)
        assertEquals(3, w.cursorAbsolute)
    }
}
