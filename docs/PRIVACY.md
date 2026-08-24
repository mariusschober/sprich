# PRIVACY — local means local

**Thesis**: Normal use is completely offline. Speech never leaves the phone.

## Guarantees

- No cloud ASR, no remote inference, no telemetry, no analytics SDK, no crash SDK, no AD_ID, no account, no sync, no remote config for dictation.
- Transcription runs on-device via whisper.cpp / sherpa-onnx / NeMo-Speech.cpp.
- Works identically in airplane mode after models installed.

## Data flows

- ** Microphone **: `AudioRecord` with `VOICE_RECOGNITION`, 16k mono PCM16, ring buffer 4s in RAM, overwritten continuously, discarded on `stop()`/`clear()`. Never written to file in normal operation (tests use `files/cache` transient wav with header, deleted).
- ** Transcript **: `InputConnection.setComposingText`/`commitText` directly to focused field. No transcript history unless explicitly enabled (opt-in `Learn my corrections` stores minimal local mappings in `files/vocab`, view/edit/delete/clear).
- ** Network **: Only `models/download` uses `okhttp3` + `java.net` — isolated to `DownloadManager`, `ModelManager`. `speech/*` has no network import; lint fails build if violated. Optional model download shows size, free-space check, progress, cancel, SHA256 verify, atomic install, rollback on interruption. Never auto-downloads.
- ** Logging **: `NoTranscriptLogger` + `PrivacyGuard.sanitizeForLog` redacts `transcript`. Release logs minimal: timing, engine state, error codes, device metrics. No transcript, vocab, field content.

## Controls

- Password/PIN fields: checked via `EditorInfo.inputType` + `isPassword` + autofill hints. Instant Mode does nothing, mic stays off, no warning bubble.
- Permissions: `RECORD_AUDIO` (runtime), `VIBRATE` (haptics), `FOREGROUND_SERVICE_MICROPHONE` (accessibility overlay, Android 14+), `INTERNET` + `ACCESS_NETWORK_STATE` (model download only). No `READ_EXTERNAL_STORAGE`, no broad FS.
- Storage: optional models in app-private `files/canary`, `files/nemotron`, excluded from cloud backup and device transfer (`backup_rules.xml`, `data_extraction_rules.xml`). `RECORD_AUDIO` not used outside dictation; ` DictationForegroundService ` stops immediately when not needed (IME active does not need FGS).
- Settings exposes: `Audio storage: Never`, `Network use: Models only`, `Clear local data`.

## Verification

- Build checks no analytics dependency in `app/build.gradle` (no `firebase`, `mixpanel`, `sentry`).
- `Benchmark` export JSON local only (`files/benchmark/export.json`).
- Manual test: airplane mode → kill app → dictate → identical behavior.
- Network audit: `adb shell dumpsys netstats` shows zero during dictation.

## Limitations

- Downloaded model files are untrusted until SHA verified; we use HTTPS + pinned SHA via manifest, bounded temp dir, no archive traversal.
- No executed code from download, only model data.
