# Sprich — Private Dictation (On-device + Optional BYOK Cloud)

> Tap a text field. Speak. The words are there.

Sprich is a private dictation IME for Android. **Cloud is optional enhancement, never a requirement.** Normal use is fully on-device (Tiny LID + FastConformer / Canary). When you choose it, Sprich can use your own API keys to call your selected transcription or refinement provider directly from your device — no Sprich cloud, no proxy, no shared billing.

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

- `core/audio` — 16k mono PCM, `UtteranceAudioCollector` (neutral, primitive, bounded 30s, `size()<=maxSamples` strict including oversized chunk), ring buffer 30s, circular pre-roll 250ms, RMS telemetry.
- `core/vad` — energy VAD, onset 45ms, hesitation 400ms, endpoint 650ms, adaptive.
- `speech/api` — `SpeechEngine` contract, `SpeechLanguage.Auto|Fixed`, `TranscriptionTask.TRANSCRIBE`.
- `speech/canary` — Canary 180M Flash INT8 via sherpa-onnx INT8 (Accurate, `files/canary`)
- `speech/fastconformer` — FastConformer CTC 126M (Automatic primary, `files/fastconformer`) + Tiny LID 98M (`files/whisper-tiny`)
- `speech` — `TranscriptionMode` ON_DEVICE / API_PRIMARY / LOCAL_API_FALLBACK, `UtterancePlan` frozen at onset, `TranscriptionResult` + `TranscriptionCoordinator` (remote-first, no wasted local decode)
- `speech/remote` — `RemoteSttProvider` abstraction, `OpenAiCompatibleSttProvider` (`POST /audio/transcriptions`, Bearer, bounded 8KB), `MetaMuseSttProvider` (BLOCKED), `DeadlinePolicy`, typed `ApiFailure`, `Call.cancel()` structured cancellation
- `speech/refinement` + `ai` — `RefinementMode` OFF / CORRECT / CLEAN_DICTATION, `OpenAiCompatibleRefinementProvider` (`POST /chat/completions`, temp 0, tiny, DATA block), `RefinementValidator` (numbers/URLs/emails/IDs, drift heuristics, injection), deterministic before refinement
- `storage` — `Preferences` typed DataStore, `ApiSecretStore` (Keystore AES-GCM in `noBackupFilesDir/api_secrets`, DataStore keeps refs only)
- `input/ime` — `SprichIME` owns `ActiveUtterance(plan)` frozen at onset, `UtteranceAudioCollector` authoritative PCM, exactly-once via `FieldSessionController`, password guard, streaming partial policy (IME preview)
- `input/composition` — `CompositionManager` delta, `SpokenEditingParser` deterministic EN/DE/ES, `TypographyNormalizer` language-aware

Full: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · Cloud: [docs/API_ARCHITECTURE.md](docs/API_ARCHITECTURE.md)

## Models

| Model | Size | Delivery | Runtime | Status |
|-------|------|----------|---------|--------|
| **Accurate** Canary 180M Flash INT8 | 147 MB | device-side `files/canary` | sherpa-onnx INT8 NDK (CPU, 2 threads) | Supported (primary) |
| **Fast** Whisper Base Q5_1 multilingual | 59,707,625 bytes | formerly bundled | whisper.cpp (deleted 2026-08-24) | Removed — `fastEngine` aliases Canary |
| **Streaming** Nemotron 0.6B Q4_K | — | not shipped | not implemented | Disabled |

The reliability build now exposes Accurate (Canary) as primary; Fast (Whisper) was deleted per user request and aliases Canary for benchmark compatibility. A model cannot return to Settings until its native lifecycle, cancellation, memory use, and real-audio instrumentation gates pass. See [docs/MODELS.md](docs/MODELS.md).

## Privacy

- On-device by default. Cloud is optional enhancement — you provide your own provider + API key, Sprich calls provider directly from your device, no Sprich proxy/account.
- No cloud ASR by default, no telemetry, no AD_ID, raw audio in RAM 30s then discarded, diagnostic WAV opt-in only.
- BYOK secrets: Keystore AES-GCM in `noBackupFilesDir/api_secrets`, never in DataStore/logs/backups. Settings shows `Saved` without reloading plaintext.
- `speech:*` except `speech/remote` never imports `okhttp`/`java.net` (lint `check-apk.sh`), `ai` handles refinement network.
- Dynamic privacy disclosure reflects actual mode: local-only → `Models only`, API STT → `Audio sent directly...`, refinement → `Transcript sent directly...`.

See [docs/PRIVACY.md](docs/PRIVACY.md) · [docs/API_ARCHITECTURE.md](docs/API_ARCHITECTURE.md).

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
