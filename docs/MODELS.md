# MODELS — reliability build (2026-09-02)

## Supported: Accurate (Canary 180M Flash INT8) — primary (explicit languages)

| Property | Value |
|---|---|
| Engine | `Canary 180M Flash INT8` via `sherpa-onnx` OfflineRecognizer |
| Delivery | Device-side, not bundled in APK — downloaded to `files/canary/` on first use |
| Files | `encoder.int8.onnx 127 MB`, `decoder.int8.onnx 71 MB`, `tokens.txt 52 KB` = **198 MB** on-device |
| Download | `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8.tar.bz2` |
| Size | `198 MB` (manifest `sizeBytes=207618048` = 198 * 1024 * 1024) |
| SHA-256 | `7a38ed8b13f014ad632b09ff8d22e0c6f1359dd046af9235d281dfae841b9ab9` |
| Runtime | `libsherpa-onnx-1.12.11.aar` → `libonnxruntime.so` + `libsherpa-onnx-*.so`, arm64-v8a only, CPU, 2 threads, `inferenceDispatcher + Mutex` serialized |
| Sample rate | `16000 Hz` mono PCM16 (engine-required, verified via `AudioCapture` + `Pcm16Wav`) |
| Quantization | `INT8` |
| Languages exposed | **Explicit only**: English, German, Spanish, French (FR now via explicit `fr`). **Automatic not natively supported** — `capabilities.languageDetection=false`, `supportedLanguages` does not contain `AUTO`; when `Auto` pref is present, engine falls back single-decode to `en` with diagnostic. Previous stopword multi-decode (EN/DE/ES 4s window + 30s cache) removed as architecturally invalid. See `docs/MODEL_BAKEOFF.md`. |
| Task | `transcribe` only (`srcLang == tgtLang` on every decode, never `translate`) |
| Decoder options | beam=5 (default), serialized create/decode/release via Mutex, per-utterance PCM frozen at endpoint, cancellable speculative decode, LCP N=2 stabilizer, `UtteranceToken` + `finalizeOnce` exactly-once |
| License | `CC-BY-4.0` (model) + `Apache-2.0` (sherpa-onnx) — attribution in Settings → Licenses |

At runtime, Sprich verifies `files/canary` integrity (size >50 MB per file, SHA-256 after download, atomic rename from `*.tmp`). Missing files are an explicit load error; there is no cloud fallback unless the user has explicitly configured Remote STT (opt-in).

Former Whisper Base Q5_1 `bundled` is deleted per user request (2026-08-24). `scripts/verify-models.sh` now skips the Whisper bundle check and verifies Canary runtime presence without bundling model data.

## Former: Fast (Whisper) — deleted

Whisper Q5_1 (`59,707,625 bytes`, `sha256 422f1ae4…`, `libsprich_whisper.so`) was the original bundled engine. It is removed from `app/src/main/assets/models` and `app/src/main/cpp`; the APK gate now expects no `whisper-base-q5_1.bin` in the APK. The `SprichApp.fastEngine` alias now points to the Canary instance for benchmark compatibility. Whisper may not return without a pinned checksum, NDK build, and the same lifecycle/memory gates as Canary.

## Disabled: Nemotron

Nemotron is not implemented. The former native library was a dummy that could not transcribe; it has been removed from the build. Settings identifies it as unavailable.

It must not return until a real pinned runtime/model combination exists and passes the same lifecycle, correctness, memory, and hardware gates as Accurate (Canary).

## APK gate

`scripts/verify-models.sh` now verifies:
- Canary runtime (`libsherpa`/`libonnxruntime`) IS packaged.
- Canary/Nemotron model data (`*.onnx`, `*.gguf`) is NOT bundled (device-side).
- Whisper model not bundled (Canary focus).

`scripts/check-apk.sh` enforces that `speech/*` except `speech/remote` stays network-free.
