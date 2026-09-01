package com.sprich.app

import com.sprich.app.input.commands.SpokenEditingParser
import com.sprich.app.input.typography.TypographyNormalizer
import com.sprich.app.speech.api.Language
import org.junit.Assert.*
import org.junit.Test

class TypographyNormalizerTest {

    @Test fun germanHalloPunkt() {
        val r = SpokenEditingParser.parse("Hallo Punkt", Language.DE, true)
        assertEquals("Hallo.", r.text)
    }

    @Test fun germanHalloKommaWelt() {
        val r = SpokenEditingParser.parse("Hallo Komma Welt", Language.DE, true)
        assertEquals("Hallo, Welt", r.text)
    }

    @Test fun germanWieGehtEsFragezeichen() {
        val r = SpokenEditingParser.parse("Wie geht es Fragezeichen", Language.DE, true)
        assertEquals("Wie geht es?", r.text)
    }

    @Test fun germanAusrufezeichen() {
        val r = SpokenEditingParser.parse("Hallo Ausrufezeichen", Language.DE, true)
        assertEquals("Hallo!", r.text)
    }

    @Test fun rawDasIstGutSpaceDot() {
        // Raw ASR with space before dot
        val normalized = TypographyNormalizer.normalize("Das ist gut .", Language.DE)
        assertEquals("Das ist gut.", normalized)
    }

    @Test fun threeFourteenRemains() {
        assertEquals("3.14", TypographyNormalizer.normalize("3.14", Language.DE))
        assertEquals("3.14", TypographyNormalizer.normalize("3.14", Language.EN))
        // Even with normalization, existing correct decimal unchanged
    }

    @Test fun exampleDotComRemains() {
        assertEquals("example.com", TypographyNormalizer.normalize("example.com", Language.DE))
        assertEquals("example.com", TypographyNormalizer.normalize("example.com", Language.EN))
        assertEquals("marius@example.com", TypographyNormalizer.normalize("marius@example.com", Language.EN))
    }

    @Test fun intentionalRepetitionPreserved() {
        assertEquals("very very good", TypographyNormalizer.normalize("very very good", Language.EN))
        assertEquals("no no no", TypographyNormalizer.normalize("no no no", Language.DE))
    }

    @Test fun whitespacePreserved() {
        // Double spaces between words should remain (we only remove space before punctuation)
        assertEquals("Hello  world", TypographyNormalizer.normalize("Hello  world", Language.EN))
        assertEquals("Hallo  Welt", TypographyNormalizer.normalize("Hallo  Welt", Language.DE))
        // Newlines preserved — space before dot removed but newline kept
        assertEquals("Hello\nworld", TypographyNormalizer.normalize("Hello\nworld", Language.EN))
        assertEquals("Hallo.\nWelt", TypographyNormalizer.normalize("Hallo .\nWelt", Language.DE))
        assertEquals("Hello\nworld", TypographyNormalizer.normalize("Hello\nworld", Language.DE))
    }

    @Test fun englishPunctuation() {
        val r1 = SpokenEditingParser.parse("Hello period", Language.EN, true)
        // "period" is whole-utterance command -> "." ; when inline? test inline
        val r2 = SpokenEditingParser.parse("Hello comma world", Language.EN, true)
        assertEquals("Hello, world", r2.text)
        val r3 = SpokenEditingParser.parse("How are you question mark", Language.EN, true)
        assertEquals("How are you?", r3.text)
        val r4 = SpokenEditingParser.parse("Hello exclamation mark", Language.EN, true)
        assertEquals("Hello!", r4.text)
    }

    @Test fun englishRawSpacePunctuation() {
        assertEquals("Hello.", TypographyNormalizer.normalize("Hello .", Language.EN))
        assertEquals("Hello, world", TypographyNormalizer.normalize("Hello , world", Language.EN))
        assertEquals("How are you?", TypographyNormalizer.normalize("How are you ?", Language.EN))
    }

    @Test fun spanishPunctuation() {
        val r1 = SpokenEditingParser.parse("hola coma mundo", Language.ES, true)
        assertEquals("hola, mundo", r1.text)
        val r2 = SpokenEditingParser.parse("hola punto", Language.ES, true)
        assertEquals("hola.", r2.text)
        assertEquals("hola.", TypographyNormalizer.normalize("hola .", Language.ES))
        // ES: like EN, " ?" attaches -> "?"
        assertEquals("¿ que?", TypographyNormalizer.normalize("¿ que ?", Language.ES))
    }

    @Test fun frenchPreservesButFixesObviousWordDot() {
        // FR: only fix . , ) ] } not : ; ? !
        assertEquals("Bonjour.", TypographyNormalizer.normalize("Bonjour .", Language.FR))
        assertEquals("Bonjour, monde", TypographyNormalizer.normalize("Bonjour , monde", Language.FR))
        // French expects space before ! ? : ; — we preserve it
        assertEquals("Bonjour !", TypographyNormalizer.normalize("Bonjour !", Language.FR))
        assertEquals("Bonjour ?", TypographyNormalizer.normalize("Bonjour ?", Language.FR))
        assertEquals("Bonjour :", TypographyNormalizer.normalize("Bonjour :", Language.FR))
    }

    @Test fun compositionPunctuationOnlyAttaches() {
        // Direct typography check for punctuation-only token remains punctuation
        assertEquals(".", TypographyNormalizer.normalize(".", Language.DE))
        assertTrue(TypographyNormalizer.startsWithClosingPunctuation("."))
        assertTrue(TypographyNormalizer.startsWithClosingPunctuation(","))
        assertTrue(TypographyNormalizer.startsWithClosingPunctuation("?"))
        assertFalse(TypographyNormalizer.startsWithClosingPunctuation("Hello"))
        assertFalse(TypographyNormalizer.startsWithClosingPunctuation("\"Hello\""))
    }

    @Test fun parserOutputAndInputConnectionBothNormalized() {
        // Parser output
        val parsed = SpokenEditingParser.parse("Hallo Punkt", Language.DE, true).text
        assertEquals("Hallo.", parsed)
        // Simulate InputConnection final insertion with existing "Hallo" + final "." -> "Hallo." not "Hallo ."
        // This is tested via CompositionManagerTest but also validate normalizer doesn't add space
        val viaNormalizer = TypographyNormalizer.normalize(" .", Language.DE)
        assertEquals(".", viaNormalizer)
    }
}
