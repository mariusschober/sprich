# LATENCY — critical path

Goal: collapse intention→speech→text.

## Targets (warm)

- field focus → capturing: <100ms (stretch <50ms) ; visible listening indication <150ms after activation
- speech onset → first visible: <450ms (stretch <300ms)
- endpoint → final committed: <500ms (stretch <250ms) mid-tier p95 <800ms, high-tier p95 <500ms
- UI frame: 60/90/120Hz smooth during inference, no binder stalls
- RTF <0.5 excellent <0.25
- First phoneme retained ≥99/100 immediate-speech trials (pre-roll 250ms circular buffer)
- Microphone released <1s after pause/focus loss/error/service destruction (AudioCapture.stop joins 300ms)

## How we chase it

1. **Single warm runtime**: `SprichApp` owns and preloads one verified Canary context (`ModelWarmthManager`). IME and benchmark share it; trim callbacks do not race a just-starting session. Cold load → warm load measured; keep one warm instance where lifecycle permits.
2. **No clipping**: 30s ring buffer (AudioRingBuffer 30s) holds pre-roll; `snapshotPrebufferMs(250)` on VAD onset ensures first phoneme retained; immediate speech not learned as noise (VAD `calibrateIfNeeded` ignores speech frames during 150ms calibration).
3. **VAD cheap & robust**: energy RMS, noise floor calibrated first 150ms, 64ms chunks (1024 samples), onset 45ms, hesitation 400ms, endpoint 650ms, long silence 1800ms, adaptive threshold `max(0.0012, noiseFloor*1.15)`, hysteresis via `speechMsAcc`/`silenceMsAcc`, whisper profile lower threshold 0.0008.
4. **Cancellable speculative decode**: Canary checks every 350ms, caps at 30s window, skips silence RMS<0.004; `job.cancel()` before finalization; native sherpa serialized via `limitedParallelism(1)` plus single mutex; temperature/fallback, beam/greedy, no-speech threshold, context reuse, punctuation via sherpa config.
5. **Stable prefix**: LCP of last 2, word-level, never show churn. Composing via `setComposingText` (replaces previous partial), not field replace; `finishOnCursorMove` via `finishIfActive`.
6. **Session ownership**: `DictationSession` reducer + `FieldSessionController` sessionId guards prevent cross-field insertion; `sessionId++` per `start()`.

## Instrumentation

`LatencyTracker` marks monotonic `SystemClock.elapsedRealtimeNanos()`: `sessionStart`/`focusDetected`/`sessionId:`, `audioStartRequested`, `audioActuallyRecording` (AudioRecord callback), `speechOnset` (VAD), `firstHypothesis`, `firstVisibleText` (`setComposingText`), `endpointDetected` (VAD), `inserting`, `textCommitted` (`commitText`), `sessionEnd`. Methods `delta`, `snapshotMs`, `report`, `pushState`. Benchmark screen shows these plus RTF, RSS, CPU, backend, thermal, and exports `files/benchmark/export.json`.

`AudioCapture` logs first audio frame samples/rms, health frames maxRms every 16 chunks (~1s), source used (MIC vs VOICE_RECOGNITION), bufferBytes, and dropped-frame accounting via ring overwrite.

## What slows us intentionally

- No animation delays text (liquid bar/glow/aura are chrome only, 50ms RMS visual throttle, no layout).
- Model is device-side, not bundled, to keep APK lean (47M vs 91M with Whisper).
- Desugaring + `isJniDebuggable` only in debug.

## Measured status (2026-09-01)

- Unit tests: 79 tests, 0 failures — covers VAD, composition, stabilizer, session FSM (1,000 randomized property tests), audio ring/resampler/pre-roll/hysteresis, language/task invariants (0/100 unintended translations), field session cross-insertion guard, first-phoneme retention 99/100.
- Lint: `lintDebug` 0 errors, `abortOnError=true`.
- Build: `assembleDebug` 47M APK, 16KB page size, `libsherpa` + `libonnxruntime` present, no bundled model data, `speech` network isolation verified.
- Emulator finite: software-emulated ARM validates correctness (real `jfk.wav` → non-empty transcript via Canary mock when sherpa not downloaded), not a performance claim.
- Physical-device gates still pending (see KNOWN_LIMITATIONS.md for matrix): warm latency, RTF, peak RAM, battery 10min, first-phoneme loss, duplicate/misdirected insertion count, mic-release latency must be measured on low/mid/high hardware and reported honestly (P50/P95).
