# Keyboard handoff and system voice input — design note (2026-10-25)

Status: **voice-subtype handoff is implemented** (T2). `RecognitionService` and
`ACTION_RECOGNIZE_SPEECH` are **designed here only, not shipped**.

## What is shipped

`method.xml` declares, next to the four typing subtypes, one one-shot voice subtype:

```xml
<subtype android:imeSubtypeMode="voice" android:imeSubtypeLocale="en"
         android:isAuxiliary="true" android:overridesImplicitlyEnabledSubtype="true"
         android:label="@string/ime_subtype_voice" />
```

The typing subtype that precedes it also sets `overridesImplicitlyEnabledSubtype` so exactly one
typing subtype and the voice subtype are implicitly enabled. Entering through the voice subtype
is a handoff, not a keyboard choice: after a successful insertion, or when the session is
cancelled or hidden, the IME calls `switchToPreviousInputMethod()` on API 28+ (API 26–27 falls
back to the deprecated `switchToLastInputMethod(token)` when a window token exists, otherwise
the globe/programmatic switch path). Selection bookkeeping is in
`OneShotVoiceSubtypePolicy`; the framework callback is `InputMethodManager.currentInputMethodSubtype`.

## Platform constraints measured on the TCL T807D (Android 16 / API 36)

1. **Only one voice IME is discoverable at a time.** `InputMethodManager
   .getShortcutInputMethodsAndSubtypes()` returns the first enabled IME (system IMEs first) that
   has an enabled voice subtype. With Google Voice Typing enabled, that is Google's service, so
   keyboards such as HeliBoard switch there and a third-party voice IME is invisible. A user who
   wants Sprich's voice keyboard must disable Google Voice Typing in system keyboard settings.
2. **The shortcut lookup indexes the original subtype array with the position of the enabled
   subtype.** It reads `enabledSubtypes[i]` but checks `imi.getSubtypeAt(i).mode`. With the
   implicit-enable map yielding one subtype per mode, the voice subtype must sit directly after
   the typing subtype in declaration order. This ordering is deliberate; do not reorder
   `method.xml` without re-testing handoff.
3. **AOSP-lineage keyboards honour the subtype.** HeliBoard v4.2 mic key → Sprich voice subtype →
   dictation "I love you." inserted → automatically returned to HeliBoard. MEASURED.
4. **Gboard does not hand off.** Its microphone key starts Gboard's own recognizer
   (`SpeechRecognizerListener` heartbeat) and leaves the active IME unchanged. No third-party
   voice IME can be reached from it. MEASURED negative result; this is a Gboard product choice,
   not an Sprich defect.

## Route B (not implemented): `ACTION_RECOGNIZE_SPEECH` activity

An exported activity with `<intent-filter><action android:name="android.speech.action
.RECOGNIZE_SPEECH"/><category android:name="android.intent.category.DEFAULT"/></intent-filter>`
and a floating/translucent theme. Callers start it with `startActivityForResult` (or a
`PendingIntent`) and receive `EXTRA_RESULTS`; `RECOGNIZE_SPEECH` also defines `EXTRA_PARTIAL_RESULTS`
and `EXTRA_PREFER_OFFLINE`. Apps must handle `ActivityNotFoundException`.

Why not in 1.0: the activity owns its own result delivery and cannot rely on Sprich's editor
authority; it would need a separate UI, its own password/PIN policy (no request context available),
and a decision about clipboard fallback when the caller has no editor. It does not help the
keyboard-mic-key gap for Gboard (Gboard ignores it). Additive work for a later release.

## Route C (not implemented): `RecognitionService`

A service declared with the `android.speech.RecognitionService` action, meta-data
`android.speech` pointing to a `<recognition-service android:settingsActivity="…"/>` resource,
and an exported binding permission. Users select it in Settings → Voice input
(`Settings.ACTION_VOICE_INPUT_SETTINGS`); `SpeechRecognizer` callers then bind it. Callers that
want it on Android 11+ need a `<queries>` entry for the action, and the service must implement
`RecognitionService.Callback` semantics (partial/final results, errors, cancellation).

Why not in 1.0: it is a second integration surface with its own permission and lifecycle story
(microphone ownership, process lifecycle while bound, provider/key availability) and it does not
change keyboard mic-key behaviour either. The manifest deliberately declares no
`RecognitionService`; adding one must not weaken the existing invariants (immutable utterance
authority, no retry of ambiguous mutations, password/PIN no-recording). Additive work for 1.x.

## Consequences for documentation

- README: voice-keyboard handoff works from AOSP-lineage keyboards with a voice-input key; Gboard
  and vendor keyboards with built-in speech do not hand off, and a competing system voice IME
  takes precedence in the shortcut setting.
- No new permissions, services or activities were added by T2.
