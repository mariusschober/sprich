package com.sprich.app.input.commands

/**
 * Typed result of spoken-command parsing (S01).
 *
 * Only the trusted parser ([SpokenEditingParser.parseTyped]) may construct [Formatting] or
 * [Editing] values, and only after checking the [CommandPolicy]: commands enabled, a supported
 * interpretation language (never Unknown), and an exact allowed whole-utterance command.
 *
 * The finalizer must switch on this type — never reinterpret result text. The cleanup stage
 * receives and returns plain text only. Provider output, literal sentinel strings, quoted text,
 * speaker labels, custom vocabulary and field context never acquire command authority.
 */
sealed interface ParsedDictation {
    /** Ordinary dictation text (possibly deterministically normalized). Insert as text. */
    data class Text(val value: String) : ParsedDictation

    /** A deliberate non-destructive formatting command (punctuation / line break). */
    data class Formatting(val action: FormattingAction, val rendered: String) : ParsedDictation

    /** A destructive editing command. Executes only with fresh owned-span proof at mutation time. */
    data class Editing(val action: EditingAction) : ParsedDictation

    /**
     * Explicitly rejected non-command result (e.g. no-speech acoustic basis, unsupported action).
     * Never deletes, never inserts. Callers should treat it like empty text (no-speech notice).
     */
    data class Rejected(val reason: String) : ParsedDictation
}

/** Non-destructive formatting commands. Deliberately separate from destructive editing. */
enum class FormattingAction {
    NEW_LINE,
    NEW_PARAGRAPH,
    COMMA,
    PERIOD,
    QUESTION_MARK,
    EXCLAMATION,
    COLON,
    SEMICOLON,
    DASH,
    OPEN_QUOTE,
    CLOSE_QUOTE,
}

/** Destructive editing commands. Only the owned-span proof in the IME may execute these. */
enum class EditingAction {
    /** Delete the last proven owned Sprich insertion. Sentence deletion is NOT offered:
     * the editor authority cannot prove an exact owned sentence span, so the spoken grammar
     * does not contain it (S06). */
    DELETE_LAST_OWNED,
}

/** Where the transcript came from. A recognizer matching a command phrase does not prove a
 * remote provider accurately heard it, so remote-origin destructive commands are
 * confirmation-only: they remain text and never execute (S01). */
enum class TranscriptOrigin {
    LOCAL,
    REMOTE,
}

/**
 * What the capture layer knows about the speech episode behind this transcript (S05).
 * Silence must still produce no text; legitimate symbol output requires a valid episode.
 */
enum class AcousticBasis {
    /** The episode contains validated speech frames (VAD onset). Symbol output is legitimate. */
    SPEECH_PRESENT,

    /** Silence / taps / noise with no speech: produce no text, never a command. */
    NO_SPEECH,

    /** Unknown (legacy path, VAD signal not yet wired per utterance): conservative legacy
     * behavior — symbol-only transcripts are suppressed as possible hallucinations. */
    UNKNOWN;

    companion object {
        /**
         * Maps a per-utterance VAD speech-episode flag to the parse-time basis (P02).
         *
         * Fail-safe direction: `true` enables legitimate symbol output (SPEECH_PRESENT);
         * `false` keeps legacy behavior (UNKNOWN), never NO_SPEECH — a missed VAD onset
         * (whisper, quiet room) must not drop genuine dictation. Only an explicitly
         * observed no-speech episode may use NO_SPEECH. The flag is a session-latched
         * `hasSpeechEver` snapshot, so `true` means "speech occurred during this capture
         * session", which is sufficient for the symbol-legitimacy question.
         */
        fun fromHadSpeech(hadSpeech: Boolean): AcousticBasis =
            if (hadSpeech) SPEECH_PRESENT else UNKNOWN
    }
}

/**
 * Authorization policy for command interpretation. Source/origin authorization is tested
 * separately from text parsing: the parser checks this policy, the IME re-proves
 * session/field/connection/span ownership again at mutation time.
 */
data class CommandPolicy(
    /** User has enabled spoken commands. */
    val commandsEnabled: Boolean,
    /** Transcript origin. REMOTE forces destructive commands to confirmation-only (text). */
    val origin: TranscriptOrigin = TranscriptOrigin.LOCAL,
    /**
     * Separately enabled hands-free command policy. Only when true may a local destructive
     * command operate, and then only on the last proven owned insertion with owned-span Undo.
     * There is currently no UI setting enabling this, so it defaults to false (confirmation-only).
     */
    val handsFreeDestructive: Boolean = false,
    /** Speaker-labelled conversation: provider finals keep their spans, never become commands. */
    val speakerLabelsPresent: Boolean = false,
    /**
     * Opt-in to ambiguous single-word inline punctuation ("hello comma world").
     * Default false: ambiguous inline interpretation stays literal; only unambiguous multiword
     * commands ("question mark", "new paragraph") apply inline (S06).
     */
    val allowInlineSingleWord: Boolean = false,
)
