package com.sprich.app.speech.refinement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cleanup output must not introduce invisible or direction-changing characters into another
 * app's editor. The deterministic transcript never contains them; a candidate that does is
 * rejected and the caller inserts the transcript instead.
 */
class RefinementValidatorInvisibleCharsTest {

    private fun rejects(original: String, candidate: String, mode: RefinementMode = RefinementMode.FAITHFUL): Boolean {
        val result = RefinementValidator.validate(original, candidate, mode)
        return result is RefinementValidator.Result.Reject && result.reason == "invisible_characters"
    }

    @Test
    fun plainPunctuationCleanupStillAccepted() {
        assertEquals(
            RefinementValidator.Result.Accept,
            RefinementValidator.validate("buy milk", "Buy milk.", RefinementMode.FAITHFUL),
        )
    }

    @Test
    fun newlineParagraphCleanupStillAccepted() {
        assertEquals(
            RefinementValidator.Result.Accept,
            RefinementValidator.validate("line one line two", "line one\nline two", RefinementMode.FAITHFUL),
        )
    }

    @Test
    fun emojiFromTheTranscriptIsNotMistakenForAnUnsafeCharacter() {
        assertEquals(
            RefinementValidator.Result.Accept,
            RefinementValidator.validate("good night 🌙", "Good night 🌙.", RefinementMode.FAITHFUL),
        )
    }

    @Test
    fun bidiOverrideIsRejected() {
        assertTrue(rejects("buy milk", "buy \u202Emilk"))
        assertTrue(rejects("buy milk", "buy \u2066milk\u2069"))
    }

    @Test
    fun zeroWidthAndSoftHyphenAreRejected() {
        assertTrue(rejects("buy milk", "buy\u200B milk"))
        assertTrue(rejects("buy milk", "buy\u00AD milk"))
        assertTrue(rejects("buy milk", "buy\uFEFF milk"))
    }

    @Test
    fun separatorAndControlCharactersAreRejected() {
        assertTrue(rejects("buy milk", "buy\u2028milk"))
        assertTrue(rejects("buy milk", "buy\u2029milk"))
        assertTrue(rejects("buy milk", "buy\u0000milk"))
    }

    @Test
    fun polishedModeAppliesTheSameGuard() {
        assertTrue(rejects("buy milk", "buy\u202Emilk", RefinementMode.POLISHED))
    }
}
