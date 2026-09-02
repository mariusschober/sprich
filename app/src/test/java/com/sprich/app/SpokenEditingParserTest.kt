package com.sprich.app

import com.sprich.app.input.commands.SpokenEditingParser
import com.sprich.app.speech.api.Language
import org.junit.Assert.*
import org.junit.Test

class SpokenEditingParserTest {

    @Test
    fun acousticPunctuationAloneDoesNotAuthorizeAnInsertion() {
        for (raw in listOf(".", "...", " , ! ? ", "\n")) {
            for (language in listOf(Language.EN, Language.DE, Language.ES, Language.FR, Language.AUTO)) {
                assertEquals("", SpokenEditingParser.parse(raw, language, true).text)
                assertEquals("", SpokenEditingParser.parse(raw, language, false).text)
            }
        }
        for ((language, word) in listOf(Language.EN to "period", Language.DE to "Punkt", Language.ES to "punto", Language.FR to "point")) {
            val result = SpokenEditingParser.parse(word, language, true)
            assertTrue(result.isCommand)
            assertEquals(".", result.text)
        }
    }

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
        // "comma" inside word should not trigger?
        // Our parser is word-boundary, so "comma" as standalone word should trigger, but "comma" in sentence may be punctuation intent
        // For now check that "coming" not replaced
        val r = SpokenEditingParser.parse("I am coming tomorrow", Language.EN, true)
        assertEquals("I am coming tomorrow", r.text)
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
