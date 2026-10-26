package com.sprich.app.input.commands

import com.sprich.app.input.typography.TypographyNormalizer
import com.sprich.app.speech.ResolvedUtteranceLanguage
import com.sprich.app.speech.api.Language
import java.text.Normalizer
import java.util.Locale

/**
 * Deterministic spoken editing & ITN. No LLM.
 * Conservative: false positive worse than miss.
 *
 * Authority model (S01): only [parseTyped] may produce [ParsedDictation.Formatting] or
 * [ParsedDictation.Editing], and only for an exact whole-utterance command in a supported
 * (Known) language with [CommandPolicy.commandsEnabled]. Everything else — provider output,
 * literal sentinel strings, quoted text, speaker labels, vocabulary, field context, unknown
 * language, disabled commands, remote-origin destructive phrases — is [ParsedDictation.Text]
 * (or [ParsedDictation.Rejected] for no-speech episodes). The finalizer switches on the type.
 */
object SpokenEditingParser {

    /** Legacy result kept for existing callers/tests. Prefer [parseTyped] for new code. */
    data class EditResult(val text: String, val isCommand: Boolean)

    private data class FormatTarget(val action: FormattingAction, val rendered: String)

    // Whole-utterance + (multiword) inline formatting commands per language.
    private val enFormats = mapOf(
        "new line" to FormatTarget(FormattingAction.NEW_LINE, "\n"),
        "new paragraph" to FormatTarget(FormattingAction.NEW_PARAGRAPH, "\n\n"),
        "question mark" to FormatTarget(FormattingAction.QUESTION_MARK, "?"),
        "exclamation mark" to FormatTarget(FormattingAction.EXCLAMATION, "!"),
        "open quote" to FormatTarget(FormattingAction.OPEN_QUOTE, "\""),
        "close quote" to FormatTarget(FormattingAction.CLOSE_QUOTE, "\""),
        "full stop" to FormatTarget(FormattingAction.PERIOD, "."),
        // Single-word entries: whole-utterance, or inline only with explicit opt-in (S06).
        "comma" to FormatTarget(FormattingAction.COMMA, ","),
        "period" to FormatTarget(FormattingAction.PERIOD, "."),
        "colon" to FormatTarget(FormattingAction.COLON, ":"),
        "semicolon" to FormatTarget(FormattingAction.SEMICOLON, ";"),
        "dash" to FormatTarget(FormattingAction.DASH, " - "),
    )
    private val deFormats = mapOf(
        "neue zeile" to FormatTarget(FormattingAction.NEW_LINE, "\n"),
        "neuer absatz" to FormatTarget(FormattingAction.NEW_PARAGRAPH, "\n\n"),
        "komma" to FormatTarget(FormattingAction.COMMA, ","),
        "punkt" to FormatTarget(FormattingAction.PERIOD, "."),
        "fragezeichen" to FormatTarget(FormattingAction.QUESTION_MARK, "?"),
        "ausrufezeichen" to FormatTarget(FormattingAction.EXCLAMATION, "!"),
    )
    private val esFormats = mapOf(
        "nueva línea" to FormatTarget(FormattingAction.NEW_LINE, "\n"),
        "nuevo párrafo" to FormatTarget(FormattingAction.NEW_PARAGRAPH, "\n\n"),
        "signo de interrogación" to FormatTarget(FormattingAction.QUESTION_MARK, "?"),
        "signo de exclamación" to FormatTarget(FormattingAction.EXCLAMATION, "!"),
        "coma" to FormatTarget(FormattingAction.COMMA, ","),
        "punto" to FormatTarget(FormattingAction.PERIOD, "."),
    )
    private val frFormats = mapOf(
        "nouvelle ligne" to FormatTarget(FormattingAction.NEW_LINE, "\n"),
        "nouveau paragraphe" to FormatTarget(FormattingAction.NEW_PARAGRAPH, "\n\n"),
        "point d'interrogation" to FormatTarget(FormattingAction.QUESTION_MARK, "?"),
        "point d’exclamation" to FormatTarget(FormattingAction.EXCLAMATION, "!"),
        "point d'exclamation" to FormatTarget(FormattingAction.EXCLAMATION, "!"),
        "deux points" to FormatTarget(FormattingAction.COLON, ":"),
        "point virgule" to FormatTarget(FormattingAction.SEMICOLON, ";"),
        "virgule" to FormatTarget(FormattingAction.COMMA, ","),
        "point" to FormatTarget(FormattingAction.PERIOD, "."),
    )

    // Destructive editing phrases per language. Whole-utterance only, never inline.
    // Sentence deletion is deliberately absent: the editor authority cannot prove an exact
    // owned sentence span, so the spoken grammar must not offer it (S06).
    private val destructivePhrases: Map<Language, Set<String>> = mapOf(
        Language.EN to setOf("delete that", "scratch that"),
        Language.DE to setOf("lösche das", "lösch das"),
        Language.ES to setOf("borra eso", "borrar eso"),
        Language.FR to setOf("efface ça", "supprime ça"),
    )

    private fun formatsFor(lang: Language): Map<String, FormatTarget> = when (lang) {
        Language.DE -> deFormats
        Language.ES -> esFormats
        Language.FR -> frFormats
        else -> enFormats
    }

    private val sentinelLike = Regex("^__.*__$")
    private val speakerLabel = Regex(
        "^(speaker|sprecher|hablante|locuteur|locutore)\\s+\\d+\\s*:",
        RegexOption.IGNORE_CASE,
    )
    // Quoted / code spans are preserved literally: no inline command applies inside them (S06).
    private val quotedSpan = Regex("\"[^\"]*\"|“[^”]*”|«[^»]*»|`[^`]*`")

    /**
     * Trusted parse. The ONLY constructor of command authority.
     *
     * @param acousticBasis what the capture layer knows about the speech episode (S05).
     */
    fun parseTyped(
        text: String,
        resolved: ResolvedUtteranceLanguage,
        policy: CommandPolicy,
        acousticBasis: AcousticBasis = AcousticBasis.UNKNOWN,
    ): ParsedDictation {
        if (acousticBasis == AcousticBasis.NO_SPEECH) return ParsedDictation.Rejected("no_speech")
        if (text.isBlank()) return ParsedDictation.Rejected("blank")

        // Symbol-only transcripts (no letters/digits) need a valid speech episode (S05):
        // without one they are suppressed as possible no-speech hallucinations.
        if (!text.codePoints().anyMatch { Character.isLetterOrDigit(it) }) {
            if (acousticBasis != AcousticBasis.SPEECH_PRESENT) {
                return ParsedDictation.Rejected("symbol_without_speech")
            }
            val symbols = text.trim()
            if (symbols.isEmpty()) return ParsedDictation.Rejected("blank")
            return if (isLegitimateSymbolOutput(symbols)) ParsedDictation.Text(symbols)
            else ParsedDictation.Rejected("unsupported_symbol")
        }

        // Speaker-labelled provider output keeps its spans; never a command.
        if (policy.speakerLabelsPresent || speakerLabel.containsMatchIn(text.trim())) {
            return ParsedDictation.Text(applyTextProcessing(text, resolved, policy, inlineCommands = false))
        }

        if (!policy.commandsEnabled) {
            return ParsedDictation.Text(applyTextProcessing(text, resolved, policy, inlineCommands = false))
        }
        if (resolved is ResolvedUtteranceLanguage.Unknown) {
            // Safe generic only — no language-specific spoken commands, no English email ITN.
            return ParsedDictation.Text(TypographyNormalizer.normalizeForUnknown(text))
        }
        val lang = (resolved as ResolvedUtteranceLanguage.Known).language
        val key = commandKey(text)

        // Literal sentinel strings never acquire command authority — any case/whitespace/
        // Unicode variant stays text (compared on the NFKC-folded key).
        if (sentinelLike.matches(key)) {
            return ParsedDictation.Text(applyTextProcessing(text, resolved, policy, inlineCommands = false))
        }

        val formats = formatsFor(lang)
        formats[key]?.let { return ParsedDictation.Formatting(it.action, it.rendered) }

        if (destructivePhrases[lang]?.contains(key) == true) {
            // Remote origin: confirmation-only — the words remain ordinary dictation text,
            // they never execute. A recognizer match does not prove the provider heard it.
            if (policy.origin == TranscriptOrigin.REMOTE) {
                return ParsedDictation.Text(applyTextProcessing(text, resolved, policy, inlineCommands = false))
            }
            return ParsedDictation.Editing(EditingAction.DELETE_LAST_OWNED)
        }

        return ParsedDictation.Text(applyTextProcessing(text, resolved, policy, inlineCommands = true))
    }

    /**
     * Legacy parse — delegates to the typed core so production and legacy share one behavior.
     * Whole-utterance formatting maps to isCommand=true; inline formatting historically also
     * reported isCommand=true for multiword matches; destructive editing maps to the historical
     * sentinel string. Prefer [parseTyped] for new code.
     *
     * Legacy contract: [Language.AUTO] resolves to Unknown (safe, generic only).
     */
    fun parse(text: String, lang: Language, enableCommands: Boolean): EditResult {
        val resolved: ResolvedUtteranceLanguage = when (lang) {
            Language.AUTO -> ResolvedUtteranceLanguage.Unknown
            else -> ResolvedUtteranceLanguage.Known(lang)
        }
        return parse(text, resolved, enableCommands)
    }

    fun parse(text: String, resolved: ResolvedUtteranceLanguage, enableCommands: Boolean): EditResult {
        val policy = CommandPolicy(commandsEnabled = enableCommands)
        val typed = parseTyped(text, resolved, policy, AcousticBasis.UNKNOWN)
        // Historical inline flag: recompute with the same tracked grammar (multiword anywhere,
        // single-word utterance-final or opt-in) so legacy callers see a truthful flag.
        var inlineFormatting = false
        if (typed is ParsedDictation.Text && resolved is ResolvedUtteranceLanguage.Known &&
            enableCommands && text.codePoints().anyMatch { Character.isLetterOrDigit(it) }
        ) {
            inlineFormatting = applyInlineCommandsTracked(
                text, resolved.language, policy.allowInlineSingleWord,
            ).second
        }
        return when (typed) {
            is ParsedDictation.Text -> EditResult(typed.value, inlineFormatting)
            is ParsedDictation.Formatting -> EditResult(typed.rendered, true)
            is ParsedDictation.Editing -> EditResult("__DELETE_LAST__", true)
            is ParsedDictation.Rejected -> EditResult("", false)
        }
    }

    private fun applyTextProcessing(
        text: String,
        resolved: ResolvedUtteranceLanguage,
        policy: CommandPolicy,
        inlineCommands: Boolean,
    ): String {
        if (resolved is ResolvedUtteranceLanguage.Unknown) {
            return TypographyNormalizer.normalizeForUnknown(text)
        }
        val lang = (resolved as ResolvedUtteranceLanguage.Known).language
        var out = text
        if (inlineCommands && policy.commandsEnabled && !policy.speakerLabelsPresent) {
            out = applyInlineCommands(out, lang, policy.allowInlineSingleWord)
        }
        out = applyITN(out, lang)
        out = TypographyNormalizer.normalize(out, lang)
        return out
    }

    /**
     * Inline commands within a conservative grammar (S06) — the literal/command convention:
     * - whole-utterance commands always apply;
     * - unambiguous multiword commands ("question mark", "point d'interrogation") apply inline;
     * - single-word punctuation ("Punkt", "point", "punto") applies inline ONLY as the last
     *   word of the utterance ("Hallo Punkt" -> "Hallo."), because medially those words are
     *   ordinary prose ("the point is clear" stays literal). Explicit opt-in
     *   ([CommandPolicy.allowInlineSingleWord]) additionally allows medial singles;
     * - destructive phrases never apply inline;
     * - longer phrases match before shorter overlaps ("point d'interrogation" before "point");
     * - matches inside quoted/code spans are preserved literally.
     */
    private fun applyInlineCommands(text: String, lang: Language, allowSingleWord: Boolean): String =
        applyInlineCommandsTracked(text, lang, allowSingleWord).first

    private fun applyInlineCommandsTracked(text: String, lang: Language, allowSingleWord: Boolean): Pair<String, Boolean> {
        // Masks are recomputed per phrase against the current string: earlier replacements
        // change offsets, and a stale mask could expose quoted content to conversion.
        fun quotedMask(s: String) = quotedSpan.findAll(s).map { it.range }.toList()
        fun inQuoted(masked: List<IntRange>, start: Int, end: Int) = masked.any { it.first < end && start <= it.last }

        var out = text
        var fired = false
        // Longest-first so overlaps resolve to the longest phrase.
        val entries = formatsFor(lang).entries.sortedByDescending { it.key.length }
        for ((phrase, target) in entries) {
            val isMultiword = phrase.trim().contains(' ') || phrase.contains('’') || phrase.contains('\'')
            if (!isMultiword && !allowSingleWord) {
                // Conservative default: utterance-final single word only ("Hallo Punkt").
                val finalRegex = Regex("\\b${Regex.escape(phrase)}\\b([\\s.!?…]*)$", RegexOption.IGNORE_CASE)
                out = finalRegex.replace(out) { m ->
                    if (inQuoted(quotedMask(out), m.range.first, m.range.last + 1)) m.value
                    else {
                        // A spoken trailing mark ("Punkt.") merges into one rendered mark.
                        val hadMark = m.groupValues[1].any { it in ".!?…" }
                        fired = true
                        if (hadMark && target.rendered.trim() in setOf(".", ",", "?", "!", ":", ";")) target.rendered
                        else target.rendered + m.groupValues[1]
                    }
                }
                continue
            }
            val regex = Regex("\\b${Regex.escape(phrase)}\\b", RegexOption.IGNORE_CASE)
            out = regex.replace(out) { m ->
                if (inQuoted(quotedMask(out), m.range.first, m.range.last + 1)) m.value
                else { fired = true; target.rendered }
            }
        }
        return out to fired
    }

    /** Legitimate recognized symbol output admitted only with a valid speech episode (S05). */
    private fun isLegitimateSymbolOutput(symbols: String): Boolean {
        if (symbols.length > 64) return false
        val punctuationRun = Regex("^[\\p{P}\\p{S}\\s¿¡«»“”‘’—–…]+$")
        if (punctuationRun.matches(symbols)) return true
        // Emoji sequences, incl. variation selectors (FE0F), ZWJ joins, skin tones,
        // regional indicators — admitted only because speech presence was proven.
        if (symbols.codePoints().anyMatch { isEmojiish(it) }) return true
        return false
    }

    private fun isEmojiish(cp: Int): Boolean {
        if (cp == 0xFE0F || cp == 0x200D) return true
        if (cp in 0x1F3FB..0x1F3FF) return true // skin-tone modifiers
        if (cp in 0x1F1E6..0x1F1FF) return true // regional indicators
        if (cp in 0x1F300..0x1FAFF) return true
        if (cp in 0x2600..0x27BF) return true
        if (cp in 0x2B00..0x2BFF) return true
        return false
    }

    /** Canonical whole-utterance command key: NFKC, collapsed whitespace, ROOT casefold. */
    private fun commandKey(text: String): String {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return nfkc.trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
            .trimEnd('.', '!', '?')
    }

    private fun applyITN(text: String, lang: Language): String {
        if (lang != Language.EN && lang != Language.AUTO) return text
        var t = text
        t = Regex("\\b([a-zA-Z0-9_\\-]+)\\s+at\\s+([a-zA-Z0-9_\\-]+)\\s+dot\\s+([a-z]{2,})\\b", RegexOption.IGNORE_CASE)
            .replace(t) { m -> "${m.groupValues[1]}@${m.groupValues[2]}.${m.groupValues[3]}" }
        t = Regex("\\b([a-zA-Z0-9_\\-]+)\\s+dot\\s+com\\b", RegexOption.IGNORE_CASE)
            .replace(t) { m -> "${m.groupValues[1]}.com" }
        return t
    }

    /**
     * Legacy text-sniffing authority check. Deprecated: production must switch on
     * [ParsedDictation] instead. Kept only so existing tests/callers compile; always prefer
     * [parseTyped]. Matches exactly the historical sentinel emitted by legacy [parse].
     */
    @Deprecated("Switch on ParsedDictation from parseTyped instead of sniffing text.")
    fun isDeleteCommand(text: String): Boolean = text == "__DELETE_LAST__"
}
