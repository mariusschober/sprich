# ARCHITECTURE — Sprich

Input → Audio → VAD → Engine → Stabilizer → Composition → InputConnection

## Modules

```
app/src/main/java/com/sprich/app/
  SprichApp.kt                 owns and preloads the single process-wide Fast engine
  core/audio/AudioCapture.kt + RingBuffer + ForegroundService (mic FGS)
  core/vad/Vad.kt             energy, calibration 300ms, onset 90ms, hesitation 450ms, endpoint 700ms
  core/perf/LatencyTracker    monotonic marks p50/p90, BenchmarkRecorder
  core/perf/ThermalMonitor    /sys/class/thermal polling
  core/privacy/PrivacyGuard   log redaction, network-isolation lint
  speech/api/SpeechEngine.kt  contract + EngineType + capabilities
  speech/whisper/WhisperEngine + JNI (real runtime; no mock fallback)
  speech/canary/CanaryEngine  dormant prototype, not packaged or selectable
  speech/nemotron/NemotronEngine  dormant prototype, runtime not implemented
  speech/stabilization/TranscriptStabilizer  LCP of last N=2, word-level
  input/ime/SprichIME         InputMethodService, Instant/Tap, password guard, haptics EFFECT_TICK
  input/composition/CompositionManager  setComposingText/commitText delta, no duplication, finishOnCursorMove
  input/commands/SpokenEditingParser  deterministic map EN/DE/ES, ITN email, backtracking "actually"
  input/accessibility/SprichAccessibilityService  TYPE_VIEW_FOCUSED editable node, ACTION_SET_TEXT
  input/lifecycle/DictationSession FSM  IDLE→PREPARING→LISTENING→SPEECH→FINALIZING→LISTENING→ENDING→IDLE
  models/manager/ModelManager+Manifest  BuiltinManifest, SHA, atomic rename
  vocab/PersonalVocabStore+Repository  word-boundary replace, DataStore persistence (local only)
  storage/Preferences          DataStore prefs (instant, language, engine, haptics, commands)
  ui/*                         Compose Material3 DayNight, onboarding 4 steps, home, settings, benchmark
  diagnostics/Diagnostics      local only, no transcript
```

## Threading

- Audio thread: `THREAD_PRIORITY_AUDIO`, 1024-sample reads (64ms), ringBuffer.write, pushAudio, VAD.
- ASR lane: one `Dispatchers.Default.limitedParallelism(1)` lane plus one native mutex; speculative decodes are cancellable before finalization; threads = min(4, cores).
- Insertion: Main for InputConnection, computed off-main (stable/unstable diff).
- No UI-thread inference, no allocations per audio callback beyond 1KB chunk copy.

## Latency-critical path

`onStartInput`  → `audio.start` (within 40ms if instant) → `VOICE_RECOGNITION` → first phoneme in ring buffer → VAD onset 90ms → engine push → speculative decode 320ms → stabilizer needs 2 agreements → `setComposingText` at cursor → final commit after endpoint 700ms + decode <100ms.

Instrumentation marks: focusDetected, audioStartRequested, audioActuallyRecording, speechOnset, firstHypothesis, firstVisibleText, endpointDetected, textCommitted.

Target: warm focus→capturing <100ms, speech→first visible <450ms (stretch 300), endpoint→final <500ms (stretch 250), RTF <0.5.

## Security boundaries

- `speech/*` must not import networking. Lint rule `RestrictedApi` enforces compile fail if `okhttp` appears.
- The bundled Fast model is copied to app-private storage only after exact size and SHA-256 verification, then atomically replaced.
- The reliability manifest requests no Internet permission, and the unused network/archive downloader dependencies are absent.

## State machine (DictationSession)

Valid: Idle→Preparing→Listening→Speech→Finalizing→Listening (continuous) →Ending→Idle. Also Listening→Finalizing on hesitation. Any→Error→Idle. Suspended for interruptions (call, mic claimed). CompositionManager resets on each session start.

## Native

`app/src/main/cpp/CMakeLists.txt` builds the real whisper.cpp runtime into `libsprich_whisper.so`, arm64-v8a only. JNI initialization/transcription/release are serialized, active inference has a native abort callback, and UTF-8 results are converted without JNI modified-UTF-8 assumptions. `patches/whisper-scheduler-abort.patch` wires the callback through the pinned submodule's scheduler backends; CMake refuses an unpatched checkout. No Nemotron library is built.

## Build

`compileSdk 36`, `minSdk 26`, `target 36`, Kotlin 2.2.20, Compose BOM 2024.09.02, `isCoreLibraryDesugaringEnabled`, R8 fullMode, `android.nonTransitiveRClass`, `android.enableArtProfiles`.
