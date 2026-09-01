package com.sprich.app

import com.sprich.app.input.commands.SpokenEditingParser
import com.sprich.app.input.typography.TypographyNormalizer
import com.sprich.app.speech.ResolvedUtteranceLanguage
import com.sprich.app.speech.api.Language
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 15-17 — Unknown-LID behavior.
 * When LID is unresolved but FastConformer produces text, we must use generic-only processing:
 * no English/German/Spanish command map, no English email ITN, only safe typography.
 */
class UnknownLanguagePostProcessingTest {

    private val knownEN = ResolvedUtteranceLanguage.Known(Language.EN)
    private val knownDE = ResolvedUtteranceLanguage.Known(Language.DE)
    private val unknown = ResolvedUtteranceLanguage.Unknown

    @Test
    fun unknown_doesNotInterpretEnglishDeleteCommand() {
        val r = SpokenEditingParser.parse("delete that", unknown, true)
        assertFalse(SpokenEditingParser.isDeleteCommand(r.text))
        assertEquals("delete that", r.text) // must remain literal, not __DELETE_LAST__
    }

    @Test
    fun knownEN_interpretsDeleteCommand() {
        val r = SpokenEditingParser.parse("delete that", knownEN, true)
        assertTrue(SpokenEditingParser.isDeleteCommand(r.text))
    }

    @Test
    fun unknown_doesNotInterpretGermanPunkt() {
        // German "punkt" -> "." only when language is DE, not when unknown
        val rUnknown = SpokenEditingParser.parse("hallo punkt welt", unknown, true)
        assertFalse(rUnknown.text.contains(".")) // should stay literal "punkt"
        assertEquals("hallo punkt welt", rUnknown.text)

        val rKnownDE = SpokenEditingParser.parse("hallo punkt welt", knownDE, true)
        // DE inline punctuation should map punkt -> .
        assertTrue(rKnownDE.text.contains("."))
    }

    @Test
    fun unknown_doesNotApplyEnglishEmailITN() {
        val rUnknown = SpokenEditingParser.parse("marius at example dot com", unknown, true)
        assertEquals("marius at example dot com", rUnknown.text) // no ITN
        val rKnownEN = SpokenEditingParser.parse("marius at example dot com", knownEN, true)
        assertEquals("marius@example.com", rKnownEN.text)
    }

    @Test
    fun unknown_doesNotApplyEnglishPunctuationWords() {
        // "comma" -> "," only for EN, not for Unknown
        val rUnknown = SpokenEditingParser.parse("hello comma world", unknown, true)
        assertEquals("hello comma world", rUnknown.text)
        val rKnownEN = SpokenEditingParser.parse("hello comma world", knownEN, true)
        assertTrue(rKnownEN.text.contains(","))
    }

    @Test
    fun unknown_genericTypography_stillFixesObviousSpacing() {
        // Safe generic: "hello ." -> "hello." and "word ," -> "word," should still be fixed
        val r1 = SpokenEditingParser.parse("hello .", unknown, true)
        assertEquals("hello.", r1.text)
        val r2 = SpokenEditingParser.parse("word ,", unknown, true)
        assertEquals("word,", r2.text)
    }

    @Test
    fun unknown_doesNotForceFrenchSpacing() {
        // For Unknown we must NOT force French ? ! : ; spacing according to English rules (which would remove space)
        // Generic FR-conservative keeps space before : ; ? ! — we test that unknown preserves "hello ?"
        // Current generic only handles . , ) ] }, so "hello ?" stays with space — not collapsed.
        val raw = "hello ?"
        val normalizedUnknown = TypographyNormalizer.normalizeForUnknown(raw)
        // Should remain "hello ?" (not "hello?") because ? is language-specific
        assertEquals("hello ?", normalizedUnknown)

        val normalizedEN = TypographyNormalizer.normalize(raw, Language.EN)
        // EN would collapse space before ?
        assertEquals("hello?", normalizedEN)
    }

    @Test
    fun unknown_newLineNotInterpreted() {
        val rUnknown = SpokenEditingParser.parse("new line", unknown, true)
        assertEquals("new line", rUnknown.text)
        assertFalse(rUnknown.isCommand)

        val rKnown = SpokenEditingParser.parse("new line", knownEN, true)
        assertEquals("\n", rKnown.text)
        assertTrue(rKnown.isCommand)
    }

    @Test
    fun typography_unknownUsesConservative() {
        assertEquals("hello.", TypographyNormalizer.normalizeForUnknown("hello ."))
        assertEquals("word,", TypographyNormalizer.normalizeForUnknown("word ,"))
        assertEquals("test)", TypographyNormalizer.normalizeForUnknown("test )"))
        // but not : ; ? !
        assertEquals("hello :", TypographyNormalizer.normalizeForUnknown("hello :"))
        assertEquals("hello ;", TypographyNormalizer.normalizeForUnknown("hello ;"))
    }
}
