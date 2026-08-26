# LATENCY — critical path

Goal: collapse intention→speech→text.

## Targets (warm)

- field focus → capturing: <100ms (stretch <50ms)
- speech onset → first visible: <450ms (stretch <300ms)
- endpoint → final committed: <500ms (stretch <250ms)
- UI frame: 60/90/120Hz smooth during inference, no binder stalls
- RTF <0.5 excellent <0.25

## How we chase it

1. **Single warm runtime**: `SprichApp` owns and preloads one verified Whisper context. IME and benchmark share it; trim callbacks do not race a just-starting session.
2. ** No clipping **: 4s ring buffer holds pre-roll; instant mode starts audio in 40ms post-focus.
3. ** VAD cheap **: energy RMS, noise floor calibrated first 300ms, 64ms chunks, onset 90ms, hesitation 450ms, endpoint 700ms, long silence 2s. No ML VAD.
4. **Cancellable speculative decode**: Fast checks for new audio and caps partial windows at 10s. Endpoint/cancel requests interrupt the current native decode before final inference.
5. ** Stable prefix **: LCP of last 2, word-level, never show churn. Composing via `setComposingText`, not field replace.
6. **No unvalidated fallback**: Canary and Nemotron are disabled rather than consuming memory or crashing during model switches.

## Instrumentation

`LatencyTracker` marks monotonic `SystemClock.elapsedRealtimeNanos()`: `focusDetected` (session.start), `audioStartRequested`, `audioActuallyRecording` (AudioRecord callback), `speechOnset` (VAD), `firstInferenceStart`, `firstHypothesis`, `firstVisibleText` (composition), `endpointDetected` (VAD), `finalInferenceStart`, `finalTranscript`, `textCommitted` (commitText). Snapshot `snapshotMs()` reports p50/p90. Benchmark screen shows these plus RTF, RSS, CPU, backend.

## What slows us intentionally

- No animation delays text (80-160ms transitions are for chrome only).
- The verified 59,707,625-byte model dominates APK size by design.
- Desugaring + `isJniDebuggable` only in debug.

## Measured status

The native gate uses real speech and the real model. Software-emulated ARM validates correctness but is not a performance claim. Warm latency and sustained RTF remain physical-device gates.
