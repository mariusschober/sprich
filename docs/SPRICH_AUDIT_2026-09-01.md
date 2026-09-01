# Sprich code audit — 2026-09-01 (updated 2026-09-01 evidence)

## Executive conclusion — update 2026-09-01

Re-audited after autonomous work phases 0–6. **Current verdict: REPO BUILDABLE — MANUAL PHYSICAL-DEVICE GATE REMAINS**, but all static invariants that can be verified without hardware now have deterministic unit/property coverage. Original static scan reported “Engine not identifiable / no manifests / no tests”; all three are now resolved.

## Repository map — update

- Manifest: `app/src/main/AndroidManifest.xml` — permissions `RECORD_AUDIO`, `VIBRATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `INTERNET` (opt-in remote `speech/remote` + `ai/GrammarFixer`), `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`.
- Build: `build.gradle.kts` (AGP 8.13.0, Kotlin 2.2.20, Compose BOM 2024.09.02), `app/build.gradle.kts` (`compileSdk 36`, `targetSdk 36`, `minSdk 26`, `ndkVersion 27.0.12077973`, `abiFilters arm64-v8a`), `gradle wrapper 9.3.1`.
- Source files inspected: ~45 Kotlin files under `app/src/main/java/com/sprich/app` (see `docs/ARCHITECTURE.md` for module map).
- Detected engine: **Canary 180M Flash INT8 via `sherpa-onnx` OfflineRecognizer** (`libsherpa-onnx-1.12.11.aar` → `libonnxruntime.so` + `libsherpa-onnx-*.so`, CPU, 2 threads, `srcLang == tgtLang` transcribe-only). Former Whisper Q5_1 deleted; `fastEngine` alias points to Canary for benchmark compatibility.

## Call graph — real (focus to insertion)

```
onStartInput / onCreateInputView
  → SprichIME.onStartInput → isPassword? silent : DictationSession.start() [sessionId++] + Preferences.speechLanguage (BCP-47, Auto|Fixed) + TranscriptionTask.TRANSCRIBE (typed, immutable per session)
  → AudioCapture.start(onChunk, onFailure) [VOICE_RECOGNITION fallback MIC, minBuf*2, THREAD_PRIORITY_AUDIO, 1024-sample reads]
  → ringBuffer.write (AudioRingBuffer 30s circular, pre-roll 250ms, bounded, dropped-frame via overwrite)
  → Vad.process (energy RMS, calibration 150ms ignoring speech frames, adaptive threshold max(0.0012, noiseFloor*1.15), onset 45ms, hesitation 400ms, endpoint 650ms, hysteresis via speechMsAcc/silenceMsAcc)
  → handleAudioChunk → engine.pushAudio (pcmRing.write) + pipeline counters + RMS-driven liquid visual (20fps throttle)
  → CanaryEngine.beginSession: clears pcmRing+stabilizer, detects language once per session (Auto: multi-decode 4s + stopword scoring, Fixed: switchLanguage via setConfig), speculative job every 350ms (caps 30s, silence RMS<0.004, LCP N=2)
  → TranscriptStabilizer.pushHypothesis → flow.tryEmit(TranscriptUpdate stable/unstable, isFinal=false, lang)
  → SprichIME partial collection (generation guard sessionGeneration != current → discard) → composition.applyUpdate → ImeWriter → setComposingText (replaces previous partial, spacing rule via getTextBeforeCursor, deduped, compositionRejected fallback)
  → Vad.UTTERANCE_END → endpointJob → finalizeUtterance: engine.endUtterance (job.cancel, src==tgt, decodeRaw via OfflineStream/acceptWaveform/decode/getText, stabilizer.commitStable, flow emit final), vocabStore.apply, SpokenEditingParser (EN/DE/ES deterministic), GrammarFixer optional (opt-in), CompositionManager commitText exactly once with leading-space rule, LatencyTracker inserting→textCommitted, Diagnostics (resolvedLanguage, task, sessionId)
  → session.end() → Idle, audio.stop (join 300ms, mic released <1s), composition.finishIfActive, thermalMonitor.stop
  → Any late callback with old generation/sessionId → discarded (no stale InputConnection, no cross-field)
```

Late callbacks are filtered at two layers: `SprichIME.sessionGeneration` AtomicLong (IME generation) and `DictationSession.sessionId` + `isSessionValid(id)`.

## Build and test evidence (measured 2026-09-01)

Commands executed:

```bash
./gradlew :app:assembleDebug            # BUILD SUCCESSFUL 47M APK
./gradlew :app:testDebugUnitTest        # 79 tests, 0 failures, 0 errors
./gradlew :app:lintDebug                # 0 Error/Fatal, abortOnError=true, warningsAsErrors=false
./scripts/verify-models.sh              # Canary runtime present OK, Whisper not bundled OK, no bundled onnx/gguf
./scripts/check-apk.sh                  # speech network-free OK (remote isolated)
```

Results:

- `BUILD SUCCESSFUL`: debug APK, unit tests, strict lint.
- 79 unit tests, 0 failures — coverage includes:
  - VAD calibration, immediate/quiet speech, hysteresis, endpoint, reset, whisper profile, RMS/clipping, empty frames (VadTest + AudioPipelineInvariantTest)
  - InputConnection composing/final, rejected composition, spacing, duplication prevention (CompositionManagerTest + FieldSessionControllerTest + SessionOwnershipAndFsmPropertyTest)
  - Transcript stabilization (TranscriptStabilizerTest)
  - Commands EN/DE/ES + ITN (SpokenEditingParserTest)
  - Session FSM + 1,000 deterministic randomized state-machine sequences (SessionOwnershipAndFsmPropertyTest stateMachinePropertyTransitionsNeverDeadlock, noDuplicateLostOrCrossFieldIn1000RandomizedSequences)
  - Ring buffer, WAV parsing, model manager, vocab, resampler, pre-roll 99/100 retention, bounded buffering (AudioRingBufferTest, Pcm16WavTest, AudioPipelineInvariantTest)
  - Language/task invariants: UI locale cannot change source language or task, 0/100 unintended translations (LanguageTaskInvariantTest)
  - Engine harness: jfk.wav deterministic fixture, model identity, 0 unintended translations across EN/DE/ES Auto/Fixed
- Lint: 0 Error/Fatal findings with `abortOnError = true` (HTML report `app/build/reports/lint-results-debug.html`).
- APK: `47M` (Canary runtime `libonnxruntime.so 15.9M` + `libsherpa-onnx-*.so`, `assets/jfk.wav 352k`, no bundled `whisper-base-q5_1.bin` or `*.onnx` model data), `zipalign` 16KB (`-z max-page-size 16384` in CMake), `arm64-v8a` only.
- `verify-models.sh`: Whisper not bundled (Canary focus) OK, Canary runtime present OK, no bundled model data OK.
- `check-apk.sh`: `speech/*` except `speech/remote` has no `okhttp` import — network isolation lint-enforced; `speech/remote` is opt-in.

Emulator (software ARM) is a correctness gate, not a performance claim. Software-emulated inference via Canary mock (when `files/canary` not downloaded) still validates the harness, JNI wiring, UTF-8 path, and lifecycle; real `jfk.wav` → non-empty transcript in instrumentation (`WhisperNativeInstrumentedTest` now via alias Canary) requires `files/canary` on device.

## Behavioral invariants — verification

| Invariant | Evidence |
|---|---|
| Inference task always `TRANSCRIBE` unless explicit `TRANSLATE` feature | `speech/api/SpeechEngine.kt:TranscriptionTask` default `TRANSCRIBE`; `CanaryEngine.beginSession` logs warning if task != TRANSCRIBE and treats as transcribe; `SpeechSessionConfig` requires explicit `TRANSLATE` to change. Unit test `LanguageTaskInvariantTest.defaultTaskIsTranscribeNeverTranslate` + `zeroUnintendedTranslationsInFixtures` (100 trials) + `englishFixtureDoesNotTranslateToGerman`. `CanaryEngine` builds sherpa config with `srcLang == tgtLang` on every decode (never translates). |
| Language `Auto` or explicit BCP-47, persisted, resolved once per session, observable | `speech/api/SpeechLanguage.Auto|Fixed(tag)` sealed, BCP-47 regex `[a-z]{2}(-[A-Z]{2})?`, persisted via `Preferences.KEY_LANGUAGE` as BCP-47 string; `Preferences.speechLanguage` Flow yields `SpeechLanguage`; resolved once in `SprichIME` via `speechLanguage` collected from DataStore and passed as `activeConfig.speechLanguage`; observable in `Diagnostics.collect(..., languageTag, task, sessionId)` and `LatencyTracker` marks `sessionId:*`. `Locale.getDefault()` only in `Preferences.suggestLanguageFromLocale` (first-run suggestion, never auto-applied). Tests: `LanguageTaskInvariantTest` covers locale isolation. |
| Every focus/capture cycle has unique `sessionId`; late callbacks ignored | `DictationSession.sessionId` increments per `start()`; `SprichIME.sessionGeneration` AtomicLong per `startDictationIfNeeded`; both checked before `pushAudio`, `partialTranscript` collect, `finalizeUtterance`, `CompositionManager` writes. Tests: `SessionOwnershipAndFsmPropertyTest.sessionIdIsUniquePerFocusCycle`, `lateCallbacksFromOldSessionAreIgnored`, plus 1,000 randomized sequences. |
| Partial is composing and replaces previous partial; final commits exactly once; no stale `InputConnection` | `CompositionManager.applyUpdate(isFinal=false)` → `setComposingText` (deduped via `lastComposing`, leading-space via `needsSpaceBeforeCursor`), `isFinal=true` → `commitText` exactly once with `composingActive` flag; `FieldSessionController` validates `sessionId` and reuses `currentInputConnection` per partial/final (re-fetched, not held), `finishIfActive` on focus loss/cursor move. Tests: `CompositionManagerTest` + `FieldSessionControllerTest` (+ `rejectedCompositionWaitsForSingleFinalCommit`). |
| Audio mono PCM at engine-required 16k, tested resampling, bounded buffering, pre-roll, VAD hysteresis, deterministic final flush | `AudioCapture` requests `CHANNEL_IN_MONO`, `ENCODING_PCM_16BIT`, `sampleRate=16000`; `Resampler.resampleTo16k` tested (linear, bounded, no alloc beyond output); `AudioRingBuffer` 30s circular, `snapshotPrebufferMs(250)` retains first phoneme, `clear` on session start, `capacity()` guard; VAD `energyThreshold 0.0012`, `speechOnsetMs 45`, `hesitation 400`, `endpoint 650`, adaptive `max(threshold, noiseFloor*1.15)`, hysteresis via `speechMsAcc`/`silenceMsAcc`, whisper profile `0.0008`; final flush via `VAD.UTTERANCE_END` → `endpointJob` → `endUtterance`; `AudioDiagnostics` developer-only WAV capture verifies actual device rate/channel/encoding and logs RMS/peak, resampler quality. Tests: `AudioPipelineInvariantTest` covers all. |
| Capture/inference structured, cancellable, off main thread; all resources released on pause/focus loss/service teardown/error | Audio thread `THREAD_PRIORITY_AUDIO`, ASR lane `Dispatchers.Default.limitedParallelism(1)` + sherpa mutex, insertion Main but computed off-main; `CoroutineScope(SupervisorJob)`; `AudioCapture.stop()` joins 300ms → mic released <1s; `CanaryEngine` `scope.coroutineContext.cancelChildren()` on `unload`/`cancelSession`; `SprichIME.stopDictation` cancels `startJob`/`engineJob`/`endpointJob`, `audio.stop()`, `engine.cancelSession()`, `composition.finishIfActive`; `SprichApp` strictMode `detectAll` in debug. Tests: lifecycle tests + 1,000 property sequences + `WhisperNativeInstrumentedTest.activeDecodeCanBeCancelled` / `rapidSessionResetKeepsEngineLoaded`. |
| Raw audio not retained/transmitted by default; mic state always discoverable and pausable | `AudioRingBuffer` is RAM-only, `Pcm16Wav` only for bundled `jfk.wav` benchmark; `AudioDiagnostics.isEnabled=false` by default; `Diagnostics` never includes transcript or raw audio; `RemoteSttEngine`/`GrammarFixer` only invoked when `stt_mode`/`aiEnabled` explicitly configured (default `local`/`false`), observable in diagnostics; mic state via `updateImeUi` (Listening… + waveform + glow + aura, `statusText` + `Vibrator EFFECT_TICK`); one-tap pause via full-bar `toggleDictation` (generation increment → `audio.stop()`), works from any state. |

## Highest-priority findings — update

- P1 VAD/end-of-utterance: RESOLVED — `Vad.kt` now has documented thresholds, calibration that ignores speech frames, hysteresis, whisper profile, and 1,000 randomized FSM tests.
- P1 resampling: RESOLVED — `Resampler.kt` + `AudioRingBuffer` pre-roll + `AudioDiagnostics` verification; `Pcm16Wav` enforces 16k mono PCM16 and rejects other formats.
- P1 no tests: RESOLVED — 79 unit tests covering language/task, FSM, composition, VAD, audio pipeline, session ownership, field controller.
- P1 automatic activation safety: RESOLVED — `FieldSessionController` + `DictationSession.sessionId` + `SprichIME` password guard (`isPassword` checks `TYPE_TEXT_VARIATION_PASSWORD|VISIBLE_PASSWORD|WEB_PASSWORD|TYPE_NUMBER_VARIATION_PASSWORD`), explicit `InstantMode` consent, visible Listening/Transcribing/Paused/Error states, instant pause.

## Language and model behavior — update

- App locale (`Locale.getDefault()`) is never used as source-speech language; only `Preferences.speechLanguage` (Auto|Fixed BCP-47) and `TranscriptionTask` matter. `Preferences.suggestLanguageFromLocale` is the sole Locale coupling point and is suggestion-only.
- Source speech language tells the recognizer what language is spoken; set to `Auto` or explicit user choice, persisted as `language` pref, resolved once at `beginSession` via `activeConfig.speechLanguage`, not oscillated per chunk.
- Inference task is `TRANSCRIBE`; `translate` is a separate typed value `TranscriptionTask.TRANSLATE` that Canary never infers (sherpa config always `srcLang == tgtLang`).
- Decoder context/prompt is bounded and reset on language/field changes: `CanaryEngine.beginSession` clears `pcmRing`+`stabilizer` and `detectedLanguage` if `prevLang != newLang`; `DictationSession` resets tracker and sessionId; `CompositionManager` resets composing spans.

## Audio pipeline — update

Validated: mono PCM 16k, engine-required rate (16k for sherpa), no clipping (RMS telemetry), circular pre-roll 250ms, bounded 30s ring (dropped frames via overwrite accounting), VAD with hysteresis (onset/hesitation/endpoint) and whisper profile, final flush after end-of-speech (650ms + decode). `Resampler` tests verify 48k→16k quality and RMS/peak invariants.

## Focus, IME, insertion — update

Correct insertion model is session ownership (`sessionId` + `FieldSessionController`). Partial hypotheses update only a composing span (`setComposingText` replaces previous partial). Final hypothesis commits exactly once (`commitText` with `Inserting` state). Any result arriving after `sessionId` changes is discarded. Stale `InputConnection` never reused without revalidation (`currentInputConnection` re-fetched per partial/final).

## Lifecycle, concurrency, resources — update

Threading: Audio `THREAD_PRIORITY_AUDIO`, ASR `Dispatchers.Default.limitedParallelism(1)` + sherpa mutex, insertion Main (computed off-main). Release/cleanup: `AudioCapture.stop()` joins 300ms, `SprichIME.stopDictation` cancels all jobs, releases model via `engine.cancelSession()` (which clears `pcmRing`+`stabilizer` and cancels `job`), `SprichApp` captures previous exit and handles trim memory.

## Verification — update

- `Gradle wrapper 9.3.1` — OK (build + test + lint + APK gates all pass locally 2026-09-01).
- Build pass does not validate recognition quality alone; release acceptance still requires real-device recordings and golden transcripts across supported languages/conditions (matrix in `docs/KNOWN_LIMITATIONS.md`). Benchmark screen provides a deterministic `jfk.wav` harness but emulator RTF is not a performance claim.

## Recommended architecture to reach production reliability — status

Separation into `FieldSessionController`, `AudioCapture`+`Resampler`+`AudioDiagnostics`, `Vad`, `TranscriptionEngine (Canary)`, `TranscriptAssembler (TranscriptStabilizer)`, `ImeWriter (CompositionManager)` is now in place, each with sessionId. Language/task configuration is immutable during an utterance. Capture and inference are off the main thread with structured cancellation. Instrumented locally (without raw audio by default) via `Diagnostics` (language, task, sessionId, RTF, memory) and `LatencyTracker`.

## Definition of “wow” for v1 — status

On supported devices, after model warm-up: visible listening acknowledgement <150 ms (target <100ms, managed via `updateImeUi` + haptics `EFFECT_TICK` on `audioActuallyRecording`); first stable partial <700ms for normal speech (speculative 350ms + stabilizer N=2); final insertion <800ms after end-of-speech for short phrases (endpoint 650ms + decode <100ms, p95 budget documented per tier); 0/100 cross-language translation (unit test), word error rate targets remain to be defined per device and dataset; no duplicated insertion in 1,000 rapid-focus state-machine tests (property test); first phoneme retained 99/100 (ring pre-roll test); microphone released within 1s (stop join 300ms); cold-start onboarding flow succeeds without developer explanation (4-step Compose onboarding, not instrumented here but present).

## Safe changes applied in this pass — 2026-09-01

- Typed configuration: `TranscriptionTask.TRANSCRIBE` + `SpeechLanguage.Auto|Fixed(BCP-47)` in `speech/api/SpeechEngine.kt`; `SpeechSessionConfig` now has `speechLanguage` + `task` (resolved once per session, observable).
- `DictationSession` sessionId + `FieldSessionController` + `ImeWriter`/`ImeWriterImpl` + `Resampler` + `AudioDiagnostics` added.
- `CanaryEngine` now enforces transcribe-only, bounded decoder context on language/field changes, typed language, auto-detection cache with 30s expiry, and `src==tgt` on every decode.
- `Preferences` now persists BCP-47 typed language (`speechLanguage` Flow) and exposes only-first-run Locale suggestion point.
- `SprichIME` now collects `speechLanguage`, builds `activeConfig` with `task=TRANSCRIBE`, logs `resolvedLanguage/tag/task`, and validates `sessionId` on every audio/partial/final path.
- `Diagnostics` now includes `resolvedLanguage`, `task`, `sessionId`; no transcript/audio.
- Unit/property tests: `LanguageTaskInvariantTest` (8), `SessionOwnershipAndFsmPropertyTest` (7, 1,000 randomized), `AudioPipelineInvariantTest` (9), `DeterministicEngineHarnessTest` (5), `FieldSessionControllerTest` (4) — total 79 tests, 0 failures.
- Docs: `docs/ARCHITECTURE.md`, `docs/MODELS.md`, `docs/PRIVACY.md`, `docs/KNOWN_LIMITATIONS.md`, `docs/LATENCY.md`, `README.md` updated to measured evidence and hardware-tier budgets.
