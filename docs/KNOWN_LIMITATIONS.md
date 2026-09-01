# KNOWN LIMITATIONS — reliability build (2026-09-01)

## Release scope

- Primary engine: Canary 180M Flash INT8 (`sherpa-onnx` INT8) via `files/canary` device-side download, not bundled.
- The APK is arm64-v8a only, includes `libonnxruntime.so` + `libsherpa-onnx-*.so` (16KB aligned).
- Whisper Q5_1 deleted per user request; `fastEngine` alias points to Canary for benchmark compatibility.
- Nemotron not implemented; former dummy native library removed.
- Remote STT (`speech/remote`) and AI polish (`ai/GrammarFixer`) exist as opt-in OpenAI-compatible endpoints (Grok x.ai, Groq, fal Wizper, etc.) but default to `local` (no network).
- The current artifact is a debug build, not yet a signed Play release.

## Validation still required on a physical phone

- End-to-end IME dictation using the real microphone and a normal text field.
- Immediate speech first-phoneme retention (99/100), whisper vs normal voice, far-field/car/café/music/TV/fan/Bluetooth/wired headset.
- Pauses, corrections (“actually”), 30-second utterance, punctuation/names/numbers, one-word, immediate-speech-after-focus.
- Password/numeric/non-editable/secure-app field guard, WebView/contenteditable, Compose TextField, Chrome/Gmail/WhatsApp-like editors.
- Permission denied/revoked, incoming call, audio focus conflict, screen off/on, rotation, app/service killed, model load failure, low memory/storage, offline, rapid field switching (1,000 randomized sequences already unit-tested), repeated start/stop, keyboard change.
- Airplane mode plus a network-stat audit (`local` mode must show zero).
- Five- and fifteen-minute thermal, memory, and latency runs on representative low/mid/high Android hardware with P50/P95, RTF, peak RAM, battery.

## Product limitations

- Energy VAD is simple; thresholds (onset 45ms, hesitation 400ms, endpoint 650ms) tuned for normal/whisper but may need device-specific tuning for very noisy rooms or unusually quiet mics.
- Canary is non-streaming: partials are windowed (350ms speculative decode) and stabilized via LCP N=2; true streaming only if Nemotron returns with real RNNT.
- Cursor movement/manual typing while composing is handled via `finishIfActive` but still needs cross-editor physical testing.
- Personal vocabulary management and “Learn my corrections” remain incomplete UI features.
- Model diagnostics backup exclusions need an explicit release-policy review before store distribution.
- Spoken deletion uses bounded character deletion rather than semantic sentence boundaries.

## What is intentionally no longer claimed

- No mock or placeholder inference (Canary mock only when sherpa not available in emulation).
- No working Nemotron path.
- No measured budget-phone latency until a physical run is recorded (budgets defined per tier in ARCHITECTURE.md, P95 reported honestly).
- No release-readiness claim based only on unit tests or emulator transcription.

## Hardware-tier latency budgets (defined, to be measured physically)

- **Mid (6GB RAM, Snapdragon 730 tier)**: focus→capturing p95 <150ms, endpoint→final p95 <800ms, RTF <0.5, first phoneme loss <1/100.
- **High (8GB+, flagship SoC)**: focus→capturing p95 <100ms, endpoint→final p95 <500ms, RTF <0.3.
- **Low (3GB RAM)**: focus→capturing p95 <250ms, endpoint→final p95 <1200ms (streaming not recommended).

Benchmark screen (7× tap version) reports load time, inference time, RTF, peak RSS, backend, and exports `files/benchmark/export.json` locally.
