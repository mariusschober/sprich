package com.sprich.app

import com.sprich.app.input.commands.AcousticBasis
import com.sprich.app.input.commands.CommandPolicy
import com.sprich.app.input.commands.ParsedDictation
import com.sprich.app.input.commands.SpokenEditingParser
import com.sprich.app.input.commands.TranscriptOrigin
import com.sprich.app.speech.ResolvedUtteranceLanguage
import com.sprich.app.speech.api.Language
import org.junit.Assert.*
import org.junit.Test

/**
 * S05/S06 — grounded symbol handling and the conservative formatting grammar.
 * Symbol-only output requires a valid speech episode; prose words stay literal;
 * longer phrases win over shorter overlaps; quoted/code content is preserved.
 */
class SymbolFormattingGrammarTest {

    private val en = ResolvedUtteranceLanguage.Known(Language.EN)
    private val de = ResolvedUtteranceLanguage.Known(Language.DE)
    private val es = ResolvedUtteranceLanguage.Known(Language.ES)
    private val fr = ResolvedUtteranceLanguage.Known(Language.FR)
    private val localOn = CommandPolicy(commandsEnabled = true, origin = TranscriptOrigin.LOCAL)

    private fun typed(text: String, lang: ResolvedUtteranceLanguage, basis: AcousticBasis) =
        SpokenEditingParser.parseTyped(text, lang, localOn, basis)

    // S05: grounded intent handling ------------------------------------------

    @Test fun silenceProducesNoText() {
        for (basis in listOf(AcousticBasis.NO_SPEECH, AcousticBasis.UNKNOWN)) {
            assertTrue(typed("?", en, basis) is ParsedDictation.Rejected)
            assertTrue(typed("...", en, basis) is ParsedDictation.Rejected)
            assertTrue(typed("hello world", en, AcousticBasis.NO_SPEECH) is ParsedDictation.Rejected)
        }
    }

    @Test fun speechEpisodeAdmitsLegitimateSymbols() {
        val q = typed("?", en, AcousticBasis.SPEECH_PRESENT)
        assertTrue(q is ParsedDictation.Text)
        assertEquals("?", (q as ParsedDictation.Text).value)
        val dots = typed("...", en, AcousticBasis.SPEECH_PRESENT)
        assertTrue(dots is ParsedDictation.Text)
    }

    @Test fun emojiSequencesAdmittedOnlyWithSpeech() {
        val emoji = "😀👍"
        val withSpeech = typed(emoji, en, AcousticBasis.SPEECH_PRESENT)
        assertTrue(withSpeech is ParsedDictation.Text)
        assertEquals(emoji, (withSpeech as ParsedDictation.Text).value)
        // Variation selector sequence.
        val vs = "❤️"
        assertTrue(typed(vs, en, AcousticBasis.SPEECH_PRESENT) is ParsedDictation.Text)
        // Without a speech episode the same output is suppressed as hallucination.
        assertTrue(typed(emoji, en, AcousticBasis.UNKNOWN) is ParsedDictation.Rejected)
        assertTrue(typed(emoji, en, AcousticBasis.NO_SPEECH) is ParsedDictation.Rejected)
    }

    @Test fun spokenPunctuationWordsWorkWithoutAlphaAsr() {
        // "question mark"/"new paragraph" are alphabetic commands — they never depended
        // on the ASR returning symbols, and must keep working.
        val q = typed("question mark", en, AcousticBasis.SPEECH_PRESENT)
        assertTrue(q is ParsedDictation.Formatting)
        val p = typed("new paragraph", en, AcousticBasis.SPEECH_PRESENT)
        assertTrue(p is ParsedDictation.Formatting)
        assertEquals("\n\n", (p as ParsedDictation.Formatting).rendered)
    }

    // S06: conservative grammar ----------------------------------------------

    @Test fun ordinaryProseStaysLiteral() {
        assertEquals(
            "the point is clear",
            (typed("the point is clear", en, AcousticBasis.SPEECH_PRESENT) as ParsedDictation.Text).value,
        )
        assertEquals(
            "der Punkt ist klar",
            (typed("der Punkt ist klar", de, AcousticBasis.SPEECH_PRESENT) as ParsedDictation.Text).value,
        )
        assertEquals(
            "el punto es claro",
            (typed("el punto es claro", es, AcousticBasis.SPEECH_PRESENT) as ParsedDictation.Text).value,
        )
        assertEquals(
            "I am coming tomorrow",
            (typed("I am coming tomorrow", en, AcousticBasis.SPEECH_PRESENT) as ParsedDictation.Text).value,
        )
    }

    @Test fun frenchOverlapPrefersLongestPhrase() {
        // Whole utterance: exact longest match wins over the "point" prefix.
        val r = typed("point d'interrogation", fr, AcousticBasis.SPEECH_PRESENT)
        assertTrue(r is ParsedDictation.Formatting)
        assertEquals("?", (r as ParsedDictation.Formatting).rendered)
        // Inline with opt-in: the long phrase must win, not "point" -> ".".
        val optIn = localOn.copy(allowInlineSingleWord = true)
        val inline = SpokenEditingParser.parseTyped(
            "dis point d'interrogation s'il te plaît", fr, optIn, AcousticBasis.SPEECH_PRESENT,
        )
        assertTrue(inline is ParsedDictation.Text)
        assertTrue((inline as ParsedDictation.Text).value.contains("?"))
        assertFalse(inline.value.contains("."))
    }

    @Test fun explicitOptInEnablesSingleWordInline() {
        val optIn = localOn.copy(allowInlineSingleWord = true)
        val r = SpokenEditingParser.parseTyped(
            "hello comma world", en, optIn, AcousticBasis.SPEECH_PRESENT,
        )
        assertTrue(r is ParsedDictation.Text)
        assertTrue((r as ParsedDictation.Text).value.contains(","))
    }

    @Test fun finalSingleWordConverts() {
        // Utterance-final single-word punctuation is an explicit formatting act.
        assertEquals(
            "Hallo.",
            (typed("Hallo Punkt", de, AcousticBasis.SPEECH_PRESENT) as ParsedDictation.Text).value,
        )
        assertEquals(
            "Hello.",
            (typed("Hello period", en, AcousticBasis.SPEECH_PRESENT) as ParsedDictation.Text).value,
        )
        assertEquals(
            "Wie geht es?",
            (typed("Wie geht es Fragezeichen", de, AcousticBasis.SPEECH_PRESENT) as ParsedDictation.Text).value,
        )
        // Medial singles stay literal even in the same languages.
        assertEquals(
            "Hallo Komma Welt",
            (typed("Hallo Komma Welt", de, AcousticBasis.SPEECH_PRESENT) as ParsedDictation.Text).value,
        )
        assertEquals(
            "hola coma mundo",
            (typed("hola coma mundo", es, AcousticBasis.SPEECH_PRESENT) as ParsedDictation.Text).value,
        )
    }

    @Test fun quotedContentPreserved() {
        val optIn = localOn.copy(allowInlineSingleWord = true)
        val r = SpokenEditingParser.parseTyped(
            "say \"hello comma world\" now", en, optIn, AcousticBasis.SPEECH_PRESENT,
        )
        assertTrue(r is ParsedDictation.Text)
        assertTrue((r as ParsedDictation.Text).value.contains("comma"))
        val code = SpokenEditingParser.parseTyped(
            "type `a.b` now", en, optIn, AcousticBasis.SPEECH_PRESENT,
        )
        assertTrue((code as ParsedDictation.Text).value.contains("a.b"))
    }

    @Test fun wholeUtterancePunctuationAllLanguages() {
        assertEquals(".", (typed("period", en, AcousticBasis.SPEECH_PRESENT) as ParsedDictation.Formatting).rendered)
        assertEquals(".", (typed("Punkt", de, AcousticBasis.SPEECH_PRESENT) as ParsedDictation.Formatting).rendered)
        assertEquals(".", (typed("punto", es, AcousticBasis.SPEECH_PRESENT) as ParsedDictation.Formatting).rendered)
        assertEquals(".", (typed("point", fr, AcousticBasis.SPEECH_PRESENT) as ParsedDictation.Formatting).rendered)
        assertEquals("?", (typed("fragezeichen", de, AcousticBasis.SPEECH_PRESENT) as ParsedDictation.Formatting).rendered)
    }

    @Test fun paragraphBehaviorExplicit() {
        val deAbsatz = typed("neuer absatz", de, AcousticBasis.SPEECH_PRESENT)
        assertTrue(deAbsatz is ParsedDictation.Formatting)
        assertEquals("\n\n", (deAbsatz as ParsedDictation.Formatting).rendered)
        val frPara = typed("nouveau paragraphe", fr, AcousticBasis.SPEECH_PRESENT)
        assertTrue(frPara is ParsedDictation.Formatting)
    }
}
