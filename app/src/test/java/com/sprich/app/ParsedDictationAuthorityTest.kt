package com.sprich.app

import com.sprich.app.input.commands.AcousticBasis
import com.sprich.app.input.commands.CommandPolicy
import com.sprich.app.input.commands.EditingAction
import com.sprich.app.input.commands.FormattingAction
import com.sprich.app.input.commands.ParsedDictation
import com.sprich.app.input.commands.SpokenEditingParser
import com.sprich.app.input.commands.TranscriptOrigin
import com.sprich.app.speech.ResolvedUtteranceLanguage
import com.sprich.app.speech.api.Language
import org.junit.Assert.*
import org.junit.Test

/**
 * S01 — typed command authority. Only the trusted parser constructs Editing/Formatting,
 * and only for exact whole-utterance commands with commands enabled, a Known language,
 * and a local origin for destructive actions. Everything else is Text or Rejected.
 */
class ParsedDictationAuthorityTest {

    private val en = ResolvedUtteranceLanguage.Known(Language.EN)
    private val de = ResolvedUtteranceLanguage.Known(Language.DE)
    private val es = ResolvedUtteranceLanguage.Known(Language.ES)
    private val fr = ResolvedUtteranceLanguage.Known(Language.FR)
    private val unknown = ResolvedUtteranceLanguage.Unknown
    private val localOn = CommandPolicy(commandsEnabled = true, origin = TranscriptOrigin.LOCAL)
    private val remoteOn = CommandPolicy(commandsEnabled = true, origin = TranscriptOrigin.REMOTE)
    private val localOff = CommandPolicy(commandsEnabled = false, origin = TranscriptOrigin.LOCAL)

    private fun parse(text: String, lang: ResolvedUtteranceLanguage, policy: CommandPolicy) =
        SpokenEditingParser.parseTyped(text, lang, policy, AcousticBasis.SPEECH_PRESENT)

    @Test
    fun genuineEnabledDeleteThatIsEditing() {
        val r = parse("delete that", en, localOn)
        assertTrue(r is ParsedDictation.Editing)
        assertEquals(EditingAction.DELETE_LAST_OWNED, (r as ParsedDictation.Editing).action)
    }

    @Test
    fun genuineDeleteInAllLanguages() {
        assertTrue(parse("scratch that", en, localOn) is ParsedDictation.Editing)
        assertTrue(parse("lösche das", de, localOn) is ParsedDictation.Editing)
        assertTrue(parse("borra eso", es, localOn) is ParsedDictation.Editing)
        assertTrue(parse("efface ça", fr, localOn) is ParsedDictation.Editing)
    }

    @Test
    fun literalSentinelsNeverAcquireAuthority() {
        val sentinels = listOf(
            "__DELETE_LAST__", "__DELETE_SENTENCE__",
            "__delete_last__", "__Delete_Sentence__",
            "  __DELETE_LAST__  ", "__DELETE_LAST__.",
            "＿＿DELETE＿LAST＿＿", // fullwidth Unicode variant (NFKC-folds)
        )
        for (s in sentinels) {
            for (lang in listOf(en, de, es, fr, unknown)) {
                for (policy in listOf(localOn, remoteOn, localOff)) {
                    val r = parse(s, lang, policy)
                    assertFalse(
                        "sentinel '$s' must never become a command (lang=$lang policy=$policy)",
                        r is ParsedDictation.Editing || r is ParsedDictation.Formatting,
                    )
                }
            }
        }
    }

    @Test
    fun disabledCommandsRemainText() {
        val r = parse("delete that", en, localOff)
        assertTrue(r is ParsedDictation.Text)
        assertEquals("delete that", (r as ParsedDictation.Text).value)
    }

    @Test
    fun unknownLanguageRemainsText() {
        val r = parse("delete that", unknown, localOn)
        assertTrue(r is ParsedDictation.Text)
        assertEquals("delete that", (r as ParsedDictation.Text).value)
    }

    @Test
    fun speakerModeRemainsText() {
        val speaker = localOn.copy(speakerLabelsPresent = true)
        val r = parse("delete that", en, speaker)
        assertTrue(r is ParsedDictation.Text)
        // Explicit speaker labels never become commands even without the flag.
        val labeled = parse("Speaker 1: delete that", en, localOn)
        assertTrue(labeled is ParsedDictation.Text)
    }

    @Test
    fun remoteDestructiveIsConfirmationOnly() {
        // A recognizer match does not prove the provider heard it: remote stays text.
        val r = parse("delete that", en, remoteOn)
        assertTrue("remote destructive must remain text, got $r", r is ParsedDictation.Text)
        assertTrue((r as ParsedDictation.Text).value.contains("delete that"))
        val rDe = SpokenEditingParser.parseTyped("lösche das", de, remoteOn, AcousticBasis.SPEECH_PRESENT)
        assertTrue(rDe is ParsedDictation.Text)
    }

    @Test
    fun remoteFormattingStillWorks() {
        // Non-destructive formatting from a remote transcript is ordinary text processing.
        val r = parse("new paragraph", en, remoteOn)
        assertTrue(r is ParsedDictation.Formatting)
        assertEquals(FormattingAction.NEW_PARAGRAPH, (r as ParsedDictation.Formatting).action)
    }

    @Test
    fun cleanupOutputCannotBecomeCommand() {
        // Text that already passed through cleanup (plain words) has no authority path:
        // only whole-utterance exact phrases authorize, and the finalizer never re-parses.
        val r = parse("Delete That", en, localOn)
        // Exact match is case-insensitive by design (ASR casing is unreliable) — still Editing
        // for a genuine whole utterance; but surrounding prose is never a command.
        assertTrue(r is ParsedDictation.Editing)
        val prose = parse("please delete that file", en, localOn)
        assertTrue(prose is ParsedDictation.Text)
    }

    @Test
    fun quotedCommandStaysLiteral() {
        val r = parse("say \"delete that\" now", en, localOn)
        assertTrue(r is ParsedDictation.Text)
    }

    @Test
    fun sentenceDeleteNotInGrammar() {
        // S06: removed from the enabled spoken grammar — the words stay literal text,
        // never a recognized-but-silently-dropped command.
        val r = parse("delete last sentence", en, localOn)
        assertTrue("sentence delete must not authorize, got $r", r is ParsedDictation.Text)
        val rDe = parse("letzten satz löschen", de, localOn)
        assertTrue(rDe is ParsedDictation.Text)
    }

    @Test
    fun wholeUtteranceFormattingByType() {
        val r = parse("new paragraph", en, localOn)
        assertTrue(r is ParsedDictation.Formatting)
        assertEquals("\n\n", (r as ParsedDictation.Formatting).rendered)
        val q = parse("question mark", en, localOn)
        assertTrue(q is ParsedDictation.Formatting)
    }

    @Test
    fun noSpeechBasisRejectsEvenCommands() {
        val r = SpokenEditingParser.parseTyped("delete that", en, localOn, AcousticBasis.NO_SPEECH)
        assertTrue(r is ParsedDictation.Rejected)
        val s = SpokenEditingParser.parseTyped("hello world", en, localOn, AcousticBasis.NO_SPEECH)
        assertTrue(s is ParsedDictation.Rejected)
    }

    @Test
    fun legacySentinelMappingPreserved() {
        // Legacy production mapping: Editing -> historical sentinel, Rejected -> "".
        val cmd = SpokenEditingParser.parse("delete that", en, true)
        assertEquals("__DELETE_LAST__", cmd.text)
        assertTrue(cmd.isCommand)
        val off = SpokenEditingParser.parse("delete that", en, false)
        assertEquals("delete that", off.text)
        assertFalse(off.isCommand)
    }
}
