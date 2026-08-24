# Sprich — Local-Only Android Dictation

> Tap a text field. Speak. The words are there.

Sprich is a private, offline speech-to-text input method for Android. No cloud, no account, no telemetry. Tap → speak → text appears at the cursor like a native OS capability.

**Primary integration**: `InputMethodService` with `InputConnection` composing semantics. Experimental accessibility companion available for users who keep Gboard.

## Quick start

```bash
# Prerequisites: JDK 17+, Android SDK 36, NDK 27.0.12077973, CMake 3.22+
export ANDROID_HOME=/Users/schober/Documents/Circadiano/.android-sdk
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
- `speech/api` — `SpeechEngine` abstraction with capabilities (streaming, punc, etc.).
- `speech/whisper` — whisper.cpp via NDK, Q5_1 multilingual, speculative windowing + stable-prefix.
- `speech/canary` — sherpa-onnx INT8 offline, same windowing, reflection isolate.
- `speech/nemotron` — NeMo-Speech.cpp streaming 160ms chunks, CPU default, Vulkan optional.
- `input/ime` — SprichIME, Instant/Tap modes, password guard, haptics tick, warm model.
- `input/composition` — `setComposingText`/`commitText` delta, no duplication, cursor-move abort.
- `input/commands` — deterministic EN/DE/ES punctuation & delete, no LLM.
- `models/manager` — manifest, SHA256, atomic install, free-space check, rollback.
- `vocab` — local trie for names, deterministic post-recognition.

Full: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## Models

| Model | Size | Delivery | Runtime |
|-------|------|----------|---------|
| **Fast** Whisper Base Q5_1 multilingual | 57 MB bundled | assets/models | whisper.cpp NDK |
| **Accurate** Canary 180M Flash INT8 | 198 MB download | files/canary | sherpa-onnx |
| **Streaming** Nemotron 0.6B Q4_K | ~620 MB download | files/nemotron | NeMo-Speech.cpp |

Only Fast is bundled. Optional models need explicit user download via Settings. See [docs/MODELS.md](docs/MODELS.md) for acquisition, verification, and licensing.

## Privacy

- No cloud ASR, no telemetry, no analytics SDK, no crash SDK, no AD_ID.
- Audio in RAM ring buffer, never written to storage, discarded on session end.
- `speech:*` never imports `okhttp`/`java.net` (lint-enforced).
- Works in airplane mode. Network only for model download with user consent.

See [docs/PRIVACY.md](docs/PRIVACY.md).

## Benchmark

Hidden screen: tap version 7× on Home. Measures RTF, first-partial, end-to-final, RSS, backend, thermal, exports JSON locally.

Instructions & synthetic methodology: [docs/BENCHMARK.md](docs/BENCHMARK.md)

## Build reproducibility

```bash
./scripts/verify-models.sh      # SHA check bundled whisper + optional manifests
./scripts/build-native.sh       # builds whisper.cpp libs with NDK
./gradlew :app:assembleRelease  # R8 + shrinkResources, fails if optional models leaked into APK
./gradlew :app:testDebugUnitTest
```

CI check fails if `canary/` or `nemotron/` artifacts appear inside APK.

## Testing

- Unit: `TranscriptStabilizerTest`, `CompositionManagerTest`, `SpokenEditingParserTest`, `VadTest`, `SessionFsmTest`, `AudioRingBufferTest`, `ModelManagerTest`.
- Instrumentation: IME lifecycle, focus switch, password guard, interruption matrix (call, headset, rotation).
- Manual matrix: Chrome, WhatsApp, Telegram, Signal, Gmail, Slack, Notion, WebView, Compose fields.

## Known limitations (v1)

- Physical mid-range device testing pending instrumentation gate (emulator only in CI).
- whisper.cpp real inference requires NDK-built `libsprich_whisper.so` + 57MB model; debug uses mock for speed.
- Nemotron Vulkan benchmark needs per-device profiling; CPU fallback is default.
- Accessibility companion labeled experimental; Play policy restricts overlay.

## License

App code MIT. Model licenses per [docs/MODELS.md](docs/MODELS.md). Include attribution in Settings → Licenses.
