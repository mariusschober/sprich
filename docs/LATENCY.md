# LATENCY — critical path

Goal: collapse intention→speech→text.

## Targets (warm)

- field focus → capturing: <100ms (stretch <50ms)
- speech onset → first visible: <450ms (stretch <300ms)
- endpoint → final committed: <500ms (stretch <250ms)
- UI frame: 60/90/120Hz smooth during inference, no binder stalls
- RTF <0.5 excellent <0.25

## How we chase it

1. ** Already ready **: model prewarmed via `ModelWarmthManager` when IME `onCreate`/`onStartInputView`. Assets mmap, JNI methods resolved, threads ready, audio ring pre-allocated, vocab trie compiled (when wired).
2. ** No clipping **: 4s ring buffer holds pre-roll; instant mode starts audio in 40ms post-focus.
3. ** VAD cheap **: energy RMS, noise floor calibrated first 300ms, 64ms chunks, onset 90ms, hesitation 450ms, endpoint 700ms, long silence 2s. No ML VAD.
4. ** Speculative decode **: every 320ms, window 10s max, only if >0.6s new audio, adaptive if queue backs up. Whisper/Canary share same layer.
5. ** Stable prefix **: LCP of last 2, word-level, never show churn. Composing via `setComposingText`, not field replace.
6. ** Streaming **: Nemotron feeds 160ms chunks (profile 80/160/240/320), CPU default, Vulkan optional, measure per device.

## Instrumentation

`LatencyTracker` marks monotonic `SystemClock.elapsedRealtimeNanos()`: `focusDetected` (session.start), `audioStartRequested`, `audioActuallyRecording` (AudioRecord callback), `speechOnset` (VAD), `firstInferenceStart`, `firstHypothesis`, `firstVisibleText` (composition), `endpointDetected` (VAD), `finalInferenceStart`, `finalTranscript`, `textCommitted` (commitText). Snapshot `snapshotMs()` reports p50/p90. Benchmark screen shows these plus RTF, RSS, CPU, backend.

## What slows us intentionally

- No animation delays text (80-160ms transitions are for chrome only).
- R8 fullMode + shrinkResources trimmed APK 19MB→1.5MB debug→release (before real model).
- Desugaring + `isJniDebuggable` only in debug.

## Measured (synthetic)

CI emulator mock: RTF 0.2, firstPartial 320ms, final 420ms, 4 threads, CPU. Gate pending on budget phone for real.
