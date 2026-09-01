package com.sprich.app

import com.sprich.app.input.commands.SpokenEditingParser
import com.sprich.app.speech.api.Language
import org.junit.Assert.*
import org.junit.Test

class SpokenCorrectionSafetyTest {

    @Test
    fun normalSpeechContainingNoDoesNotTriggerBacktracking() {
        val cases = listOf(
            "I am not sure" to "I am not sure",
            "another example" to "another example",
            "nobody knows" to "nobody knows",
            "innovative" to "innovative",
            "economics" to "economics",
            "no one knows" to "no one knows",
            "I actually think this is fine" to "I actually think this is fine",
            "rather than doing that" to "rather than doing that",
            "I am not certain" to "I am not certain",
        )
        for ((input, expected) in cases) {
            val r = SpokenEditingParser.parse(input, Language.EN, true)
            assertEquals("input '$input' should remain unchanged after unsafe correction removal", expected, r.text)
        }
    }

    @Test
    fun explicitDeleteCommandStillWorks() {
        val r = SpokenEditingParser.parse("delete that", Language.EN, true)
        assertTrue(SpokenEditingParser.isDeleteCommand(r.text))
        val r2 = SpokenEditingParser.parse("scratch that", Language.EN, true)
        assertTrue(SpokenEditingParser.isDeleteCommand(r2.text))
    }

    @Test
    fun inlinePunctuationRequiresWordBoundaries() {
        // "not" contains "no" substring but should not trigger delete correction
        val r = SpokenEditingParser.parse("I am not certain", Language.EN, true)
        assertFalse(SpokenEditingParser.isDeleteCommand(r.text))
        assertEquals("I am not certain", r.text)
    }

    @Test
    fun languageSpecificITN() {
        // English ITN should apply for email
        val en = SpokenEditingParser.parse("marius at example dot com", Language.EN, true)
        assertEquals("marius@example.com", en.text)
        // German should NOT apply English email ITN
        val de = SpokenEditingParser.parse("marius at example dot com", Language.DE, true)
        assertEquals("marius at example dot com", de.text)
        // French similar — no transform
        val fr = SpokenEditingParser.parse("marius at example dot com", Language.FR, true)
        assertEquals("marius at example dot com", fr.text)
        // Generic number words are NOT auto-transformed (reliability over aggressive ITN)
        val enNum = SpokenEditingParser.parse("zero one", Language.EN, true)
        assertEquals("zero one", enNum.text)
        val frNum = SpokenEditingParser.parse("zero one", Language.FR, true)
        assertEquals("zero one", frNum.text)
    }

    @Test
    fun intentionalRepetitionsPreserved() {
        val cases = listOf(
            "very very good",
            "no no no",
            "that that was wrong",
            "Hello Hello world",
            "I said that, I said that intentionally."
        )
        for (input in cases) {
            val r = SpokenEditingParser.parse(input, Language.EN, true)
            // No dedup should happen — repetitions must remain
            assertEquals(input, r.text)
        }
    }
}
