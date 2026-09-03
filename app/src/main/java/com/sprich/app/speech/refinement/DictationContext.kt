package com.sprich.app.speech.refinement

/** Only a bounded tail of the already-authorized editor snapshot may accompany cleanup. */
object DictationContext {
    fun beforeCursor(text: String?): String? {
        val safe = text?.trimStart { Character.isLowSurrogate(it) }?.trimEnd { Character.isHighSurrogate(it) } ?: return null
        val points = safe.codePointCount(0, safe.length)
        return safe.substring(safe.offsetByCodePoints(0, (points - 512).coerceAtLeast(0))).takeIf { it.isNotBlank() }
    }
}
