# ARCHITECTURE — Sprich

Input → Audio → VAD → Engine → Stabilizer → Composition → InputConnection

```
onStartInput / focus → DictationSession.start() [sessionId++] → audio.start [40ms] → VAD onset 45ms → engine.pushAudio + pre-roll snapshot → speculative decode (350ms interval) → TranscriptStabilizer(LCP N=2) → FieldSessionController.applyPartial → setComposingText (replaces previous partial) → VAD UTTERANCE_END → endUtterance() [final flush] → commitText (exactly once) → session.end() → Idle
                         ↘ late callbacks with old sessionId are discarded
```

## Modules

```
app/src/main/java/com/sprich/app/
  SprichApp.kt                 owns and preloads the single process-wide Canary engine (Fast alias)
  core/audio/AudioCapture.kt + RingBuffer(30s) + ForegroundService (mic FGS)
  core/audio/Resampler.kt      linear resampler 48k/44.1k→16k mono PCM16, tested, bounded
  core/audio/AudioDiagnostics.kt developer-only WAV capture, verifies actual device rate/channel/encoding
  core/vad/Vad.kt             energy, calibration 150ms, onset 45ms, hesitation 400ms, endpoint 650ms, adaptive threshold
  core/perf/LatencyTracker    monotonic marks p50/p90, BenchmarkRecorder
  core/perf/ThermalMonitor    /sys/class/thermal polling
  core/privacy/PrivacyGuard   log redaction, network-isolation lint
  speech/api/SpeechEngine.kt  contract + EngineType + TranscriptionTask.TRANSCRIBE + SpeechLanguage.Auto|Fixed(BCP-47)
  speech/canary/CanaryEngine  Canary 180M Flash INT8 via sherpa-onnx INT8 (primary), serialized JNI, cancellable, src==tgt never translates
  speech/nemotron/NemotronEngine  dormant prototype, runtime not packaged
  speech/remote/RemoteSttEngine  OpenAI-compatible backup (/audio/transcriptions) — opt-in only, isolated in speech/remote
  speech/stabilization/TranscriptStabilizer  LCP of last N=2, word-level
  input/ime/SprichIME         InputMethodService, Instant/Tap, password guard, haptics EFFECT_TICK, sessionGeneration ownership
  input/composition/CompositionManager  setComposingText/commitText delta, no duplication, finishOnCursorMove
  input/composition/ImeWriter  abstraction over composing/final commit, spacing rules, reject fallback
  input/lifecycle/DictationSession FSM  Idle→Preparing→Listening→Speech→Finalizing→Inserting→Ending→Idle, also Suspended/Paused/Error, sessionId per cycle
  input/lifecycle/FieldSessionController session ownership + composing span control, cross-field guard
  input/commands/SpokenEditingParser  deterministic map EN/DE/ES, ITN email, backtracking "actually"
  input/accessibility/SprichAccessibilityService  TYPE_VIEW_FOCUSED editable node, ACTION_SET_TEXT
  models/manager/ModelManager+Manifest  BuiltinManifest, SHA, atomic rename, integrity check
  vocab/PersonalVocabStore+Repository  word-boundary replace, DataStore persistence (local only)
  storage/Preferences          DataStore prefs (instant, speechLanguage BCP-47, engine, haptics, commands) — Locale.getDefault() only for first-run suggestion
  ui/*                         Compose Material3 DayNight, onboarding 4 steps, home, settings, benchmark
  diagnostics/Diagnostics      local only, no transcript, no raw audio by default, opt-in export
```

## Threading

- Audio thread: `THREAD_PRIORITY_AUDIO`, 1024-sample reads (64ms), ringBuffer.write, pushAudio, VAD, clipping/RMS telemetry, dropped-frame accounting via ring overwrite.
- ASR lane: one `Dispatchers.Default.limitedParallelism(1)` lane plus one native mutex (sherpa serialized); speculative decodes cancellable before finalization; threads = 2 (Canary) / min(4,cores) if whisper; no UI-thread inference.
- Insertion: Main for InputConnection, computed off-main (stable/unstable diff).
- No UI-thread inference/model/disk work, no allocations per audio callback beyond 1KB chunk copy; all AudioRecord/model/coroutine jobs released on pause/focus-loss/service-destruction.
- Structured concurrency: CoroutineScope(SupervisorJob) + limitedParallelism; cancellation via job.cancel() and native abort callback; microphone released <1s after stop.

## Latency-critical path

`onStartInput`  → `audio.start` (within 40ms if instant, ring pre-roll 250ms retains first phoneme) → `VOICE_RECOGNITION` → first phoneme in ring buffer (bounded 30s, circular pre-roll) → VAD onset 45ms (adaptive, hysteresis) → engine push (mono PCM 16k, resampled if needed) → speculative decode 350ms → stabilizer needs 2 agreements → `setComposingText` at cursor (replaces previous partial, never duplicates) → VAD utteranceEnd 650ms → final flush → commitText exactly once (Inserting) → sessionId validated, late callbacks discarded.

Instrumentation marks: focusDetected, sessionId, audioStartRequested, audioActuallyRecording, speechOnset, firstHypothesis, firstVisibleText, endpointDetected, inserting, textCommitted.

Target: warm focus→capturing <100ms, speech→first visible <450ms (stretch 300), endpoint→final <500ms (stretch 250), RTF <0.5.
TIER BUDGETS: mid Snapdragon 6GB RAM p95 focus→capturing 150ms, p95 endpoint→final 800ms; high-tier p95 focus→capturing 100ms, p95 endpoint→final 500ms; low-tier (3GB) p95 focus→capturing 250ms. P50/P95 and RTF reported honestly via Benchmark screen.

## Security boundaries

- `speech/*` except `speech/remote` must not import networking. Lint `check-apk.sh` enforces that only `speech/remote` (opt-in backup STT) may import `okhttp`/`java.net`; all other speech code stays local-first. Remote is LOCAL by default in Preferences (stt_mode=local); cloud path requires explicit baseUrl/apiKey/model and is observable in diagnostics.
- The Canary model is device-side in `files/canary` (encoder.int8.onnx, decoder.int8.onnx, tokens.txt) verified by SHA-256 and atomic rename; prebuilt sherpa libs (`libonnxruntime.so`, `libsherpa-onnx-*.so`) are packaged, model data is not bundled in APK.
- Internet permission exists only for explicit opt-in remote STT/AI polish (Advanced settings); normal dictation uses no network and airplane mode works identically. `speech/remote` and `ai/GrammarFixer` are isolated and not invoked unless configured.

## State machine (DictationSession — reducer)

Idle → Preparing(Arming) → Listening → Speech → Finalizing → Inserting → Listening (continuous) → Ending → Idle. Also Listening→Finalizing on hesitation. Listening→Suspended/Paused on interruption (call, mic claimed, explicit pause). Any→Error(RecoverableError)→Idle. Suspended→Listening on resume. sessionId increments per start(); late callbacks with old sessionId are ignored. CompositionManager resets on each session start; decoder context is reset/bounded on language/field changes (clear pcmRing, reset stabilizer, clear detectedLanguage cache after 30s silence).

## Native

`app/src/main/cpp/CMakeLists.txt` is a minimal valid CMake enforcing 16KB page size; the current primary runtime is Canary via `libsherpa-onnx-1.12.11.aar` (sherpa-onnx JNI, `libonnxruntime.so`, `libonnxruntime.so` 16KB aligned, `libsherpa-onnx-*.so`), arm64-v8a only, CPU backend, 2 threads. Initialization/transcription/release are serialized via `Dispatchers.Default.limitedParallelism(1)` and a single native mutex; active inference is cancellable via `job.cancel()` before finalization. A former whisper.cpp runtime (`libsprich_whisper.so`) was deleted per user request; CMake now keeps a stub for Gradle externalNativeBuild. No Nemotron library is built.

## Build

`compileSdk 36`, `minSdk 26`, `target 36`, Kotlin 2.2.20, Compose BOM 2024.09.02, `isCoreLibraryDesugaringEnabled`, R8 fullMode, `android.nonTransitiveRClass`, `android.enableArtProfiles`.
