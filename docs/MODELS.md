# MODELS — reliability build

## Supported: Fast

Sprich currently supports one engine: Whisper Base multilingual Q5_1 through the bundled whisper.cpp runtime.

| Property | Value |
|---|---|
| Asset | `app/src/main/assets/models/whisper-base-q5_1.bin` |
| Size | `59,707,625` bytes |
| SHA-256 | `422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898` |
| ABI | arm64-v8a |
| Languages exposed | Automatic, English, German, Spanish |
| Runtime | `libsprich_whisper.so` built from the vendored whisper.cpp checkout |

At build time, `scripts/verify-models.sh` requires the exact byte count and checksum. At runtime, Sprich hashes the installed copy before every process-level native initialization. A replacement is copied to a staging file, synced, verified, and atomically moved into place. A missing library or invalid model is an explicit load error; there is no mock transcription fallback.

The model and whisper.cpp licensing/attribution must remain included in any distributed build. Recheck the upstream model card before public redistribution if the asset is replaced.

## Disabled: Canary

Canary prototype source remains in the repository for future investigation, but the sherpa/ONNX runtime and model are not packaged. Settings cannot select or download it. Its previous reflective native construction and concurrent lifecycle were not safe enough for users.

Canary may return only after all of these pass:

1. Pinned model files and checksums.
2. Correct runtime API integration without reflection guesses.
3. Serialized create/decode/release and cancellation under rapid switching.
4. Real-speech instrumentation on every supported ABI.
5. Repeated switch, low-memory, and 15-minute thermal tests on target hardware.

## Disabled: Nemotron

Nemotron is not implemented. The former native library was a dummy that could not transcribe; it has been removed from the build. Settings identifies it as unavailable.

It must not return until a real pinned runtime/model combination exists and passes the same lifecycle, correctness, memory, and hardware gates as Fast.

## APK gate

`scripts/verify-models.sh` rejects an APK containing Canary/Nemotron model data or `libsherpa`, `libonnxruntime`, or `libsprich_nemotron`. This prevents an unfinished alternative from silently returning to a reliability build.
