# PRIVACY — local means local

**Thesis**: Normal use is completely offline. Speech never leaves the phone unless you explicitly enable a backup remote STT endpoint in Advanced settings.

## Guarantees

- No cloud ASR by default, no remote inference by default, no telemetry, no analytics SDK, no crash SDK, no AD_ID, no account, no sync, no remote config for dictation.
- Transcription runs on-device through the Canary 180M Flash INT8 sherpa-onnx runtime by default.
- Raw audio is not retained or transmitted by default. Microphone state is always visibly discoverable (Listening… + waveform + glow) and immediately pausable (tap to stop, mic released <1s).
- The remote STT path (`speech/remote` OpenAI-compatible `/audio/transcriptions`, e.g., Grok x.ai, Groq, fal Wizper) is `stt_mode=local` by default; it is only invoked when the user has filled baseUrl/apiKey/model and selected `fallback` or `remote` in Settings → Advanced. All speech/remote calls are observable in diagnostics (resolvedLanguage, task, sessionId) and require user opt-in.
- AI polish (`ai/GrammarFixer` via `/chat/completions`) is `aiEnabled=false` by default; when enabled, only the final transcript text (not raw audio) is sent, per user configuration.

## Data flows

- **Microphone**: `AudioRecord` with `VOICE_RECOGNITION` and `MIC` fallback, 16k mono PCM16, `AudioRingBuffer` 30s in RAM, circular pre-roll 250ms, overwritten continuously, discarded on `stop()`/`clear()`/`session.end()`. Never written to storage in normal operation; diagnostic WAV capture (`AudioDiagnostics`) is developer-only and disabled by default.
- **Resampling**: If device delivers non-16k, `Resampler` linear resamples to engine-required 16k; verified via `actualDeviceConfig` logging and harness tests.
- **Transcript**: `InputConnection.setComposingText`/`commitText` via `CompositionManager`/`ImeWriter` directly to focused field (sessionId owns the field; late callbacks ignored). Partial text is composing and replaces previous partial; final commits exactly once; no stale `InputConnection` receives output. Password fields detected via `EditorInfo.inputType` and never start capture.
- **Network**: Manifest has `INTERNET`/`ACCESS_NETWORK_STATE` only for the opt-in backup paths; default dictation never touches network. `speech/*` except `speech/remote` has no networking import (lint-enforced via `check-apk.sh`). `speech/remote` is isolated.
- **Logging**: `Diagnostics` collects local-only, no transcript, no raw audio. Fields: timestamp, device, engine, resolvedLanguage (BCP-47), task, sessionId, RTF, memory. Export is opt-in `files/diagnostics/latest.log` and `files/benchmark/export.json`.

## Controls

- Password/PIN fields: checked via `EditorInfo.inputType` + `isPassword` + autofill hints. Instant Mode does nothing, mic stays off.
- Permissions: `RECORD_AUDIO` (runtime), `VIBRATE` (haptics), `FOREGROUND_SERVICE_MICROPHONE` (accessibility overlay, Android 14+), `POST_NOTIFICATIONS`, `INTERNET` (only for opt-in remote). No external-storage permission.
- Storage: Canary model in `files/canary` (app-private); cleanup removes stale dirs. Diagnostic WAVs only when `AudioDiagnostics.isEnabled`. `Diagnostics` and `ModelManager` use atomic rename + SHA verification.
- Settings exposes: `Audio storage: Never` (normal), `Network use: None by default` (local), `Clear local data`, plus `Advanced → Remote STT` and `AI polish` with explicit toggles.

## Verification

- Build checks `speech/remote` is the only networking in `speech` via `check-apk.sh`.
- `verify-models.sh` checks Canary runtime present but model data not bundled.
- `Benchmark` export JSON local only (`files/benchmark/export.json`).
- Manual test: airplane mode → kill app → dictate → identical behavior when remote is in `local` mode.
- Network audit: `adb shell dumpsys netstats` shows zero during dictation when `stt_mode=local`.

## Limitations

- The bundled fixture used by the developer benchmark is audio stored in the APK; microphone dictation audio remains RAM-only.
- Airplane-mode and network-stat checks still need to be repeated on the physical release-test device for the current Canary build.
