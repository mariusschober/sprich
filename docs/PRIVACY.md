# PRIVACY — local by default, cloud is BYOK optional

**Thesis**: Normal use is completely offline. Cloud is optional enhancement using your own provider + API key directly from your device. Sprich provides no cloud account, proxy, or billing. Speech never leaves the phone unless you select On-device → API fallback or API primary and provide a key.

## Guarantees

- No cloud ASR by default, no remote inference by default, no telemetry, no analytics SDK, no crash SDK, no AD_ID, no account, no sync, no remote config for dictation.
- Transcription runs on-device through the Canary 180M Flash INT8 sherpa-onnx runtime by default.
- Raw audio is not retained or transmitted by default. Microphone state is always visibly discoverable (Listening… + waveform + glow) and immediately pausable (tap to stop, mic released <1s).
- **Transcription choices**: `On-device` (local only, 0 STT API calls), `API` (remote primary, local fallback on failure, remote success has 0 local decode, 0 local loads/sessions/pushes; fallback lazy, same frozen PCM, re-arm neutral), `On-device → API fallback` (local first, remote only on blank/exception). Default `ON_DEVICE`. All `RemoteSttConfig` snapshots are immutable per utterance (providerId/endpoint/model/languagePolicy/deadline/credentialRef) built without `runBlocking` on audio path.
- **Refinement choices**: `Off` (0 LLM calls), `Correct`, `Clean dictation`. Default `OFF`. When enabled, transcript text only (never audio) is sent to your refinement provider via `ai/OpenAiCompatibleRefinementProvider` (`POST /chat/completions`, tiny request, `temperature 0`).
- **BYOK**: Secrets via `ApiSecretStore` Keystore AES-GCM in `noBackupFilesDir/api_secrets/<id>.enc` **or FAIL CLOSED** (no reversible Base64 fallback); DataStore keeps refs only, `hasSecret()` means decryptable (invalidated key shows “needs to be entered again”), `LegacyApiCredentialMigrator` migrates plaintext once and deletes it, Settings shows `Saved` only after durable encrypted success and shows “Could not securely save” on failure. No secret in logs/diagnostics/backups; all request/config `toString()` redacted.
- **Dynamic disclosure**: Settings → Privacy reflects actual mode: local-only → `Models only`; API STT → `Audio is sent directly... using your API key`; refinement → `Transcript sent directly...`; both → combined; always `Sprich does not provide, proxy or receive your API key` + `billed directly by your provider`.

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
- Settings exposes: `Audio storage: Never — audio is not retained after transcription` (normal) vs `Debug capture enabled: test audio is stored locally (WAV)` when `debugWavCapture` is on (disabled by default, release never retains silently), `Network use` derived from `transcriptionMode`/`refinementMode`, `Clear local data`, plus `Transcription` and `Improve transcript` with explicit toggles.

## Verification

- Build checks `speech/remote` is the only networking in `speech` via `check-apk.sh`.
- `verify-models.sh` checks Canary runtime present but model data not bundled.
- `Benchmark` export JSON local only (`files/benchmark/export.json`).
- Manual test: airplane mode → kill app → dictate → identical behavior when remote is in `local` mode.
- Network audit: `adb shell dumpsys netstats` shows zero during dictation when `stt_mode=local`.

## Limitations

- The bundled fixture used by the developer benchmark is audio stored in the APK; microphone dictation audio remains RAM-only.
- Airplane-mode and network-stat checks still need to be repeated on the physical release-test device for the current Canary build.
