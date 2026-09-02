package com.sprich.app.input.ime

import android.os.Build
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** Bounded, in-memory editor authority. Never log or persist this data. */
data class EditorSnapshot(val selectionStart: Int, val selectionEnd: Int, val before: String, val selected: String, val after: String) {
    val collapsed get() = selectionStart == selectionEnd

    companion object {
        const val BEFORE = 1024
        const val AFTER = 128
        fun read(ic: InputConnection?): EditorSnapshot? {
            if (ic == null) return null
            return try {
                if (Build.VERSION.SDK_INT >= 31) {
                    val surrounding = ic.getSurroundingText(BEFORE, AFTER, 0)
                    if (surrounding != null && surrounding.offset >= 0) {
                        return from(surrounding.text.toString(), surrounding.offset, surrounding.selectionStart, surrounding.selectionEnd)
                    }
                }
                val extracted = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = BEFORE + AFTER; hintMaxLines = 20 }, 0) ?: return null
                if (extracted.partialStartOffset >= 0) return null
                from(extracted.text?.toString() ?: return null, extracted.startOffset, extracted.selectionStart, extracted.selectionEnd)
            } catch (_: Exception) { null }
        }

        private fun from(text: String, offset: Int, start: Int, end: Int): EditorSnapshot? {
            if (offset < 0 || start !in 0..text.length || end !in 0..text.length || offset.toLong() + maxOf(start, end) > Int.MAX_VALUE) return null
            val lo = minOf(start, end)
            val hi = maxOf(start, end)
            if (hi - lo > BEFORE) return null
            return EditorSnapshot(offset + start, offset + end, text.substring(0, lo).takeLast(BEFORE), text.substring(lo, hi), text.substring(hi).take(AFTER))
        }
    }
}
