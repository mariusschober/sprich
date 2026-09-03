# Voice bar gestures and whisper mode

Implementation plan, 3 September 2026. Scope: the compact Android voice bar, safe backward editing, a quiet-speech capture profile, and a short physical-phone acceptance check.

## Interaction contract

All distances are density-independent. Directions stay physical left/right in RTL. One finger owns a gesture from touch-down to release; a second finger, cancellation, lost focus or a replaced input connection cancels it. A cancelled drag never becomes a tap.

| Gesture | Recognition | Result |
| --- | --- | --- |
| Tap | Release within touch slop | Existing start/finish/cancel dictation action. |
| Short, quick left swipe | At least 36 dp left; under 112 dp and under 350 ms | Delete one word or one complete symbol, using the rules below. |
| Long or slow left swipe | At least 112 dp left, or at least 36 dp left for 350 ms | Delete the last exact Sprich insertion when its editor authority still matches; otherwise delete the current sentence/phrase. Show the intended action while dragging. |
| Long left swipe, then hold | Beyond 112 dp; remain near that position for 450 ms | Delete one sentence, then another every 550 ms while held. Release ends deletion immediately. Returning toward the starting point cancels repetition. Release after repetition adds no extra deletion. |
| Up out of the bar | Clear vertical intent, at least 48 dp upward and past the bar's top | Stop capture and pending insertion; switch immediately to the previous typing keyboard, without waiting for release. Use an enabled typing keyboard or the system picker when there is no previous keyboard. |
| Right swipe | At least 48 dp with clear horizontal intent | Toggle whisper mode on release. Show which mode will be entered. |
| Down swipe | At least 48 dp with clear vertical intent | Stop capture and cancel pending insertion, then lower/fade the bar over approximately 180 ms and ask Android to hide it. A later tap in a text field shows Sprich again. Respect disabled system animations. |
| Stationary hold | Stay within touch slop for 2.5 seconds | Stop capture, discard pending work and open Sprich Settings directly. Movement cancels this timer. |

Axis recognition requires one direction to dominate the other by 1.4×. Diagonal or undersized drags do nothing. Retraction cancels an armed deletion; the finger cannot switch from deletion to another destructive action mid-gesture. Buttons inside the bar retain ordinary taps and accessibility actions.

## Backward editing rules

Edits are relative to the cursor. They never delete text after it. Selections, passwords/PINs, unknown selection positions and unreadable context refuse deletion.

For a short swipe:

- `Hello world|` becomes `Hello |`.
- `Hello world |` becomes `Hello |`: one trailing ASCII space travels with its preceding word.
- `Hello world  |` becomes `Hello world |`: two trailing spaces are removed one at a time.
- `Hello.|` becomes `Hello|`; `Hello. |` becomes `Hello.|`.
- An emoji, flag, skin tone, combining character or joined emoji is one complete symbol. No swipe splits a Unicode grapheme.

For a phrase swipe, Android's sentence boundaries and explicit line breaks determine the start. The unfinished sentence at the cursor is the current phrase. Trailing punctuation and whitespace belong to that phrase. At `First sentence. Second sentence. |`, one phrase swipe leaves `First sentence. |`. A sentence beyond the available bounded editor context is left untouched; Sprich does not guess where it starts.

Every deletion goes through `EditorActionController`. A held swipe receives a field/generation/context handle. Each repeat validates that handle against the actual editor, makes at most one mutation attempt, reads back the result and advances the handle only after exact confirmation. False returns, exceptions or unexpected text stop repetition even if the editor may have applied the edit. A cursor/selection change, input restart, hidden window, screen lock, view detach or second pointer ends the hold. Empty text stops it without polling indefinitely.

Undo remains available as an explicit bar button and a TalkBack action. It restores the exact most recent deleted span only while its editor anchor remains valid. Right swipe now belongs exclusively to whisper mode.

## Whisper mode

This is a capture preference, initially off and saved on the phone. It does not select a different provider, enable an API, grant context sharing, change the language, or add network requests. The bar shows a persistent Whisper indicator; Settings offers the same toggle for discoverability and accessibility.

The profile has real, bounded changes:

1. A capped quiet-input gain, at most 3×, applied before both the prebuffer and recognition pipeline. Peak protection prevents clipping. Normal mode keeps its original samples. All recognition routes receive the same processed, immutable PCM; there is no separate streaming-only audio path.
2. Lower neural speech-presence thresholds with hysteresis, slightly longer onset confirmation and a longer quiet pause before phrase completion. Remote-only capture still loads no local model.
3. A conservative writing instruction when cleanup was already enabled: render whispered dictation as ordinary written text, preserve uncertainty and never invent inaudible words or add whisper stage directions. The whisper flag is frozen in the utterance plan. The existing output guards and time limit stay in force.
4. Switching profiles during capture cancels uninserted work, retires the old recorder and starts a fresh capture in the same field if the user was listening. No utterance mixes profiles. Switching while idle stays idle.

There is no assumed undocumented Meta/OpenAI/Gemini “whisper” parameter. The profile improves pickup and endpoint behavior; recognition accuracy still depends on the selected model, microphone, distance and room noise. Actual whispered-speech accuracy requires the user's voice and is reported separately from code or ordinary-speech tests.

## Implementation sequence

1. Preserve the completed Meta recording fix in a separate commit.
2. Replace the competing pill/outer gesture listeners and inert repeat helper with one gesture recognizer and one view owner. Keep timing/distance decisions independently testable.
3. Add grapheme/word/sentence span selection and context-bound repeat authority to the existing editor controller. Preserve the single mutation boundary and exact undo rules.
4. Connect gesture actions to capture cancellation, keyboard switching, settings navigation and the short dismissal animation. Reset touch/timers/animations on every lifecycle exit.
5. Add the immutable whisper preference, capture profile, prompt context, persistent bar indicator and concise gesture help. Provide TalkBack equivalents without intercepting touch exploration.
6. Run only focused regression checks for gesture classification/cancellation, exact editor spans/repeat stops, quiet-input bounds and frozen whisper state. Build and lint the release.
7. Install the release update and exercise the gestures in Sprich's practice field and an available real editor. Check Unicode, phrase repetition, release, password refusal, hide/reopen, keyboard switch and direct Settings opening. Use human whispering for the final acoustic check; do not substitute synthetic speech for it.

## Acceptance and limits

Required: no duplicate action on release, no mutation after cancellation or field change, no Unicode splitting, no late dictation after an editing/hide/switch action, no hold timer surviving a hidden view, no API permission change from whisper mode, and no increased native-model loading on a successful API path.

Evidence uses PASS, FAIL, NOT MEASURED or BLOCKED with the exact cause. Host editor tests establish controller behavior, not physical-editor integration. Release build checks do not establish whispered-speech accuracy. This is a focused feature pass, not a new broad reliability or store-certification campaign.
