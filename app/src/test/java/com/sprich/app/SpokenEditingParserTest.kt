package com.sprich.app

import com.sprich.app.input.commands.SpokenEditingParser
import com.sprich.app.speech.api.Language
import org.junit.Assert.*
import org.junit.Test

class SpokenEditingParserTest {

    @Test
    fun englishPunctuationInline() {
        val r = SpokenEditingParser.parse("hello comma world", Language.EN, true)
        assertTrue(r.text.contains(","))
    }

    @Test
    fun germanCommandExact() {
        val r = SpokenEditingParser.parse("neue zeile", Language.DE, true)
        assertEquals("\n", r.text)
        assertTrue(r.isCommand)
    }

    @Test
    fun englishDeleteCommand() {
        val r = SpokenEditingParser.parse("delete that", Language.EN, true)
        assertTrue(SpokenEditingParser.isDeleteCommand(r.text))
    }

    @Test
    fun noFalsePositiveForNormalSpeechContainingComma() {
        val r = SpokenEditingParser.parse("I am coming tomorrow", Language.EN, true)
        assertEquals("I am coming tomorrow", r.text)
    }

    @Test
    fun ordinaryEnglishNoIsNeverACorrectionCommand() {
        val r = SpokenEditingParser.parse("I have no time today", Language.EN, true)
        assertEquals("I have no time today", r.text)
        assertFalse(r.isCommand)
    }

    @Test
    fun ordinarySpanishNoIsNeverACorrectionCommand() {
        val r = SpokenEditingParser.parse("No quiero traducir esto", Language.ES, true)
        assertEquals("No quiero traducir esto", r.text)
        assertFalse(r.isCommand)
    }

    @Test
    fun ordinaryActuallyDoesNotDeletePreviousWords() {
        val r = SpokenEditingParser.parse("This is actually important", Language.EN, true)
        assertEquals("This is actually important", r.text)
    }

    @Test
    fun numberWordsRemainVerbatim() {
        val r = SpokenEditingParser.parse("There is one reason", Language.EN, true)
        assertEquals("There is one reason", r.text)
    }

    @Test
    fun emailITN() {
        val r = SpokenEditingParser.parse("marius at example dot com", Language.EN, true)
        assertEquals("marius@example.com", r.text)
    }

    @Test
    fun commandsDisabledDoesNotTransform() {
        val r = SpokenEditingParser.parse("new line", Language.EN, false)
        assertEquals("new line", r.text)
        assertFalse(r.isCommand)
    }

    @Test
    fun spanishPunctuation() {
        val r = SpokenEditingParser.parse("hola coma mundo", Language.ES, true)
        assertTrue(r.text.contains(","))
    }
}
