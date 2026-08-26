# PRIVACY — local means local

**Thesis**: Normal use is completely offline. Speech never leaves the phone.

## Guarantees

- No cloud ASR, no remote inference, no telemetry, no analytics SDK, no crash SDK, no AD_ID, no account, no sync, no remote config for dictation.
- Transcription runs on-device through the bundled whisper.cpp Fast runtime.
- The reliability build requests no Internet permission.

## Data flows

- ** Microphone **: `AudioRecord` with `VOICE_RECOGNITION` and `MIC` fallback, 16k mono PCM16, ring buffer 4s in RAM, overwritten continuously, discarded on `stop()`/`clear()`. Never written to storage in normal operation.
- ** Transcript **: `InputConnection.setComposingText`/`commitText` directly to focused field. No transcript history unless explicitly enabled (opt-in `Learn my corrections` stores minimal local mappings in `files/vocab`, view/edit/delete/clear).
- ** Network **: the manifest has no `INTERNET` or `ACCESS_NETWORK_STATE` permission, and the reliability build has no HTTP client dependency. `speech/*` has no networking import.
- ** Logging **: `NoTranscriptLogger` + `PrivacyGuard.sanitizeForLog` redacts `transcript`. Release logs minimal: timing, engine state, error codes, device metrics. No transcript, vocab, field content.

## Controls

- Password/PIN fields: checked via `EditorInfo.inputType` + `isPassword` + autofill hints. Instant Mode does nothing, mic stays off, no warning bubble.
- Permissions: `RECORD_AUDIO` (runtime), `VIBRATE` (haptics), `FOREGROUND_SERVICE_MICROPHONE` (accessibility overlay, Android 14+), and notifications for that foreground service. No Internet, external-storage, or broad-filesystem permission.
- Storage: Fast is installed in app-private files; cleanup also removes stale Canary/Nemotron directories left by older builds. Existing backup rules exclude the stale optional-model directories and selected preferences, but Fast-model/diagnostic backup exclusions still need a release-policy review. `RECORD_AUDIO` is not used outside dictation; the IME path does not require a foreground service.
- Settings exposes: `Audio storage: Never`, `Network use: None in this reliability build`, `Clear local data`.

## Verification

- Build checks no analytics dependency in `app/build.gradle` (no `firebase`, `mixpanel`, `sentry`).
- `Benchmark` export JSON local only (`files/benchmark/export.json`).
- Manual test: airplane mode → kill app → dictate → identical behavior.
- Network audit: `adb shell dumpsys netstats` shows zero during dictation.

## Limitations

- The bundled fixture used by the developer benchmark is audio stored in the APK; microphone dictation audio remains RAM-only.
- Airplane-mode and network-stat checks still need to be repeated on the physical release-test device.
