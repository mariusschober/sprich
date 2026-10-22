package com.sprich.app

import com.sprich.app.speech.LocalAsrRoute
import com.sprich.app.ui.vocab.entryMatches
import com.sprich.app.ui.vocab.learnedMatches
import com.sprich.app.vocab.LearnedWord
import com.sprich.app.vocab.RecognitionProfile
import com.sprich.app.vocab.VocabEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabFilterTest {
    private val profile = RecognitionProfile.local(LocalAsrRoute.AutomaticFastConformer, false)
    private val learned = LearnedWord(
        id = "word-1",
        written = "Marius Schöber",
        profile = profile,
        samples = listOf("maris schober"),
        forms = listOf("maris schober"),
    )

    @Test fun searchMatchesWrittenAndHeardForms() {
        assertTrue(learnedMatches(learned, "schöber"))
        assertTrue(learnedMatches(learned, "SCHOBER"))
        assertTrue(learnedMatches(learned, "marius"))
        assertFalse(learnedMatches(learned, "anna"))
        assertTrue(learnedMatches(learned, "  "))
    }

    @Test fun manualRulesSearchBothSides() {
        val entry = VocabEntry("sprich", "Sprich")
        assertTrue(entryMatches(entry, "spr"))
        assertTrue(entryMatches(entry, "SPRICH"))
        assertFalse(entryMatches(entry, "dictate"))
    }
}
