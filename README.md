# Sprich — Local-Only Android Dictation

> Tap a text field. Speak. The words are there.

Sprich is a private, offline speech-to-text input method for Android. No cloud, no account, no telemetry. Tap → speak → text appears at the cursor like a native OS capability.

**Primary integration**: `InputMethodService` with `InputConnection` composing semantics. Experimental accessibility companion available for users who keep Gboard.

## Quick start

```bash
# Prerequisites: JDK 17+, Android SDK 36, NDK 27.0.12077973, CMake 3.22+
export ANDROID_HOME=/Users/schober/Documents/Circadiano/.android-sdk
./scripts/apply-whisper-patches.sh
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

1. Install → open Sprich → grant microphone → enable Sprich keyboard in system settings.
2. Switch to Sprich in any field (globe icon) → enable **Instant Dictation** if you want auto-listen on focus.
3. Dictate. Toggle back to Gboard via keyboard switcher instantly.

Airplane mode must work identically after installation.

## Product principles

1. Perceived latency > actual latency > reliability > privacy > battery > visuals.
2. Network must never be touched during dictation; architecture enforces isolation.
3. Target €200-300 phones (6GB RAM, mid Snapdragon/MediaTek).

See [PLAN.md](PLAN.md) for full product thesis and latency targets.

## Architecture

- `core/audio` — 16k mono PCM, ring buffer 4s, zero-first-phoneme clipping, timestamps monotonic.
- `core/vad` — tiny energy VAD, calibrated noise floor, configurable onset/hesitation/endpoint.
- `speech/api` — `SpeechEngine` contract and language/session configuration.
- `speech/whisper` — one process-wide whisper.cpp context, verified Q5_1 model, serialized JNI, cancellable inference.
- `speech/canary` / `speech/nemotron` — dormant prototypes; not packaged, selectable, or supported in this build.
- `input/ime` — SprichIME, Instant/Tap modes, password guard, explicit preparing/error states.
- `input/composition` — `setComposingText`/`commitText` delta with single-final fallback for editors that reject composing spans.
- `input/commands` — deterministic EN/DE/ES punctuation & delete, no LLM.
- Fast model install — exact byte count + SHA-256, staged copy, atomic replacement.
- `vocab` — local trie for names, deterministic post-recognition.

Full: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## Models

| Model | Size | Delivery | Runtime | Status |
|-------|------|----------|---------|--------|
| **Fast** Whisper Base Q5_1 multilingual | 59,707,625 bytes | bundled | whisper.cpp NDK | Supported |
| **Accurate** Canary 180M Flash INT8 | — | not shipped | none packaged | Disabled |
| **Streaming** Nemotron 0.6B | — | not shipped | not implemented | Disabled |

The reliability build deliberately exposes only Fast. A model cannot return to Settings until its native lifecycle, cancellation, memory use, and real-audio instrumentation gates pass. See [docs/MODELS.md](docs/MODELS.md).

## Privacy

- No cloud ASR, no telemetry, no analytics SDK, no crash SDK, no AD_ID.
- Audio in RAM ring buffer, never written to storage, discarded on session end.
- `speech:*` never imports `okhttp`/`java.net` (lint-enforced).
- The reliability build requests no Internet permission and packages no HTTP client dependency.

See [docs/PRIVACY.md](docs/PRIVACY.md).

## Benchmark

Hidden screen: tap version 7× on Home. It transcribes the bundled public-domain `jfk.wav` through the same process-wide Fast engine and reports load time, inference time, and RTF.

Instructions and limitations: [docs/BENCHMARK.md](docs/BENCHMARK.md)

## Build reproducibility

```bash
./scripts/verify-models.sh      # exact size/SHA and optional-runtime absence
./scripts/apply-whisper-patches.sh
./gradlew :app:assembleDebug    # builds the arm64 reliability APK
./gradlew :app:testDebugUnitTest
```

CI check fails if `canary/` or `nemotron/` artifacts appear inside APK.

## Testing

- Unit: stabilizer, composition, spoken commands, VAD, session FSM, ring buffer, WAV parsing, and model manager.
- Instrumentation: real bundled model transcription, cancellation of active native inference, and rapid session reset.
- Manual matrix: Chrome, WhatsApp, Telegram, Signal, Gmail, Slack, Notion, WebView, Compose fields.

## Known limitations (v1)

- Physical mid-range IME/microphone and sustained thermal testing is still required.
- Only arm64-v8a and the Fast model are supported in this build.
- Canary and Nemotron remain disabled; their prototype source is not a working product path.
- Accessibility companion labeled experimental; Play policy restricts overlay.

## License

App code MIT. Model licenses per [docs/MODELS.md](docs/MODELS.md). Include attribution in Settings → Licenses.
