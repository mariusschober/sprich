# Sprich architecture

## Production pipeline

```text
AudioRecord (16 kHz mono PCM16)
  → per-IME Silero speech detector + bounded endpoint state machine
  → immutable runtime configuration and utterance plan at onset
  → immutable PCM snapshot at endpoint or explicit finish
  → Automatic: Whisper Tiny language ID → FastConformer CTC
     Accurate: Canary with explicit EN / DE / ES / FR
  → deterministic spoken editing, vocabulary and typography
  → authority check → at most one irreversible editor mutation
```

All production providers are disabled until verified through the actual service path. Debug-only settings retain optional BYOK experiments. A successful API-primary route must not load or decode local models; credentialed requests never follow redirects. Saving/testing a key does not enable transcription or refinement.

## Ownership and cancellation

Each IME owns its recognizers and speech detector. Loading, decoding and release are serialized per native engine. Old IME teardown releases only its own objects. Audio captures own their recorder, reader thread, prebuffer and cleanup. A retiring reader must stop before its successor resets shared endpoint/PCM state; blocking shutdown runs off Main.

One preference collector publishes a `RuntimeConfigSnapshot`. Each utterance captures its route, selected/detected-language policy, spoken commands and vocabulary. Settings changes affect subsequent utterances. Missing plans/tokens are discarded, never reconstructed. Cancellation invalidates authority and cannot trigger a local/cloud fallback.

The PCM collector is bounded to 30 seconds. Snapshot jobs are queued in order with bounded backpressure; a suppressed speech episode is not resumed midway. Successful insertion leaves the field session available for another utterance. Hide, field loss, restart, cursor change and service destruction cancel pending work.

## Editor authority

`FieldSessionController`, `UtteranceToken` and `EditorSnapshot` tie a result to its field, generation, selection and exact surrounding context. Only callbacks matching Sprich's own successful mutation preserve authority. Manual cursor/selection changes stop listening and clear pending text and edit history.

Partials stay inside the IME. A final result uses one `commitText`; an editor returning false or throwing after mutation never causes a retry. Delete and undo require exact unchanged spans with complete Unicode boundaries. Ambiguous/truncated context fails closed. Password/PIN fields prohibit recording and editor mutations.

## Files, credentials and removal

`ModelManager` is process-wide. Each model has one serialized install/delete/load boundary. HTTPS archives have pinned size/hash; extraction rejects traversal, links and excessive expansion. Installation uses verified staging, durable receipts and atomic promotion while preserving the prior installation through interrupted replacement. Interrupted transfers restart; there is no partial-resume protocol.

Secrets are AES-GCM encrypted using Android Keystore, atomically saved off Main, and bound to provider plus canonical endpoint. Preference files retain references only. Vocabulary writes are serialized and durable before updating memory. Android's `clearApplicationUserData` terminates active work and clears all app-owned data.

## Packaging

ARM64/API 26–36; R8/resource shrinking in release. Recognition-only sherpa-onnx JNI removes unused speech synthesis and GPL eSpeak code from the old all-purpose AAR. JNI and ONNX Runtime use 16 KB ELF alignment; actual AAB-generated APK/runtime checks are recorded separately in the release evidence. Developer activities, fixtures and mock providers live in debug or opt-in QA source sets.
