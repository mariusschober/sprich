# Teach Sprich a word

## Purpose

Learn a person's actual recognition errors, then map the confirmed errors to their
chosen spelling. This is a personal correction dictionary, not acoustic model
training. It cannot guarantee that the recognizer will hear the word correctly in
every sentence. The interface must make that distinction without model jargon.

## The experience

1. Open **Personal vocabulary → Teach Sprich a word**. Explain: say one difficult
   name or term three times, then type its correct spelling. Show the current voice
   recognition choice and whether recordings stay on the phone or go directly to
   the person's already-enabled API. Writing cleanup is not used.
2. Tap **Record** and say the term once. Capture at most four seconds; **Done
   speaking** can finish sooner. Show a listening indicator, remaining recording
   progress, then a separate recognition state. There is always a Cancel action.
3. Show the recognizer's final text verbatim in an attempt card. Three successful
   attempts unlock **Next**; two additional attempts are optional. Empty, failed,
   cancelled and interrupted attempts do not count. Removing an attempt permits a
   replacement. Never invent a result from a partial transcript.
4. Type the intended spelling with the phone's typing keyboard, or explicitly
   choose one launchable app or contact through Android's local picker. Sprich
   receives only the chosen display name; it does not enumerate either list.
   Avoid a spoken alphabet parser: asking the same recognizer to decode the
   spelling introduces another ambiguous recognition step. Clear focus and stop/release recording
   before opening the keyboard. An own-app-only field marker tells Sprich to switch
   to a typing keyboard instead of starting instant dictation in this field.
5. Review the distinct forms Sprich heard and their frequencies. Repeated forms
   are suggested; single observations require an explicit selection. A form that
   already equals the intended spelling needs no replacement. Explain that a
   selected form will be replaced whenever the same recognition mode returns it.
   Do not hide the risk of selecting an ordinary, otherwise legitimate word.
6. **Save word** commits the spelling, original text observations, selected forms
   and recognition scope together. No unconfirmed draft is persisted. The list
   shows learned words, recognition scope and selected forms; details preserve all
   recorded text observations. Remove deletes the complete lesson. Keep manual
   rules under a secondary **Add a rule manually** control.

If all attempts already have the correct spelling, allow saving the term without
inventing a replacement. It can participate in vocabulary hints only when the
person has separately enabled that existing permission.

## Recognition and lifecycle

- Freeze one recognition configuration for the entire lesson. Use the currently
  enabled primary recognizer: Automatic, Accurate with its selected language, or
  a verified personal voice API. Local-with-API-fallback lessons use the local
  primary. Failed lessons never silently switch to another recognizer.
- Use the production local coordinator or the production remote provider factory
  and live streaming session. Preserve language hints, streaming choice and
  whisper audio handling. Turn off speaker labels for this single-term exercise.
- Collect raw recognition, before commands, dictionary replacements or LLM
  cleanup. Send no personal vocabulary hints while collecting; otherwise existing
  corrections would contaminate the observations. No writing API is called.
- Own the recorder, bounded PCM buffer and any local engines within the lesson.
  Native engines are created only for local lessons, serialized across attempts,
  and released when recording ends, the screen backgrounds or the lesson closes.
  An old lesson can never unload an IME's native instance.
- Cancellation invalidates the attempt before stopping work. Late results cannot
  add samples, navigate or save. Leaving the app stops the microphone and remote
  work; completed draft observations may survive a rotation in memory, but not
  process death. No background recording or retry loop.
- Zero owned PCM buffers after completion/cancellation. No WAV files, transcript
  logs, provider bodies or credential logs. API keys remain in the existing
  encrypted provider-bound store; opening/saving a lesson grants no API consent.

## Corrections and persistence

- Extend the existing vocabulary document with learned-word records while keeping
  old manual rules readable. Migrate on save to an AtomicFile in no-backup private
  storage: commit disk first, publish memory second, under the existing process-wide
  mutex on IO. Recheck conflicts inside that transaction. A failed replacement
  preserves the previous file; an empty file remains authoritative after clearing.
- Scope learned forms to recognizer/model/endpoint, language, streaming options
  and whisper mode. Do not include credentials in the scope. Manual rules keep
  their existing global scope. At runtime choose the scope of the recognizer that
  actually produced the final text, including an explicitly allowed fallback.
- Capture an immutable vocabulary snapshot at dictation start. A lesson saved
  during dictation affects the next utterance. Preserve one-pass longest-first
  replacement, complete Unicode word boundaries and no cascading replacements.
- Group observations case-insensitively with Unicode normalization and normalized
  spaces. Handle sentence punctuation added to an isolated word conservatively;
  retain each original observation for review. No fuzzy matches or LLM-generated
  aliases. Selected aliases must be derivable from that lesson's observations.
- Refuse a conflicting alias with a different spelling in the same scope or in a
  global manual rule. The person must deselect it or remove the old rule first.
  Never silently reassign a learned correction. Prevent duplicate records for the
  same spelling/scope. Bound record count, sample count and text lengths.
- Protect learned spellings from optional writing cleanup just as existing
  vocabulary terms are protected. Recognition vocabulary hints remain separately
  opt-in and bounded. Intended spellings can help any recognizer; only the error
  replacements are scoped. Do not send lessons or observed mistakes as context.

## Deliberate limits

No voiceprint, audio archive, acoustic fine-tuning, background learning, inferred
confidence score, new provider, additional API key, cloud account, alphabet parser
or fuzzy global autocorrect. No replacement rules learned without review. A
single isolated-word lesson may not cover errors that occur only in sentences;
users can teach an additional short phrase or use the manual rule control.
Spoken editing retains priority, and speaker-labeled transcripts skip rewriting.
Disable those options when teaching a form that is also an editing command or when
you want learned corrections in normal dictation.

## Focused acceptance

- Host tests: migration, observation grouping and selection, conflicts, Unicode
  and punctuation boundaries, no cascades, immutable snapshots, scope isolation,
  cancellation/late-result handling and bounds.
- Build: release compilation, targeted unit tests, release lint and artifact
  verification. Keep the established production editor mutation boundary intact.
- S23: inspect the old screen first; then verify the guide, permission/error and
  cancellation states, actual microphone/provider path, three-attempt flow,
  typing-keyboard handoff, review/save/list/remove and persistence. Inspect dark
  and light layout and large-text wrapping for the changed screens.
- Demonstrate corrections with controlled samples in the real dictionary path.
  Distinguish that from a human proving improved recognition during daily use.
  Do not call synthetic audio evidence of the user's pronunciation.

Record measured results and remaining limits after implementation. Store submission
and production signing are outside this feature; the active distribution target
remains Zapstore.

The app/contact choice and its threat model are specified in
[LOCAL_NAME_LEARNING.md](LOCAL_NAME_LEARNING.md).
