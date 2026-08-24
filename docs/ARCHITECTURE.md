# ARCHITECTURE — Sprich

Input → Audio → VAD → Engine → Stabilizer → Composition → InputConnection

## Modules

```
app/src/main/java/com/sprich/app/
  SprichApp.kt                 warmup manager, trimMemory
  core/audio/AudioCapture.kt + RingBuffer + ForegroundService (mic FGS)
  core/vad/Vad.kt             energy, calibration 300ms, onset 90ms, hesitation 450ms, endpoint 700ms
  core/perf/LatencyTracker    monotonic marks p50/p90, BenchmarkRecorder
  core/perf/ThermalMonitor    /sys/class/thermal polling
  core/privacy/PrivacyGuard   log redaction, network-isolation lint
  speech/api/SpeechEngine.kt  contract + EngineType + capabilities
  speech/whisper/WhisperEngine + JNI (mock fallback)
  speech/canary/CanaryEngine  sherpa reflection, speculative windows
  speech/nemotron/NemotronEngine  160ms RNNT chunks
  speech/stabilization/TranscriptStabilizer  LCP of last N=2, word-level
  speech/warmup/ModelWarmth   COLD/WARMING/WARM/ACTIVE, retains while input likely, releases onTrimMemory 90s idle
  input/ime/SprichIME         InputMethodService, Instant/Tap, password guard, haptics EFFECT_TICK
  input/composition/CompositionManager  setComposingText/commitText delta, no duplication, finishOnCursorMove
  input/commands/SpokenEditingParser  deterministic map EN/DE/ES, ITN email, backtracking "actually"
  input/accessibility/SprichAccessibilityService  TYPE_VIEW_FOCUSED editable node, ACTION_SET_TEXT
  input/lifecycle/DictationSession FSM  IDLE→PREPARING→LISTENING→SPEECH→FINALIZING→LISTENING→ENDING→IDLE
  models/manager/ModelManager+Manifest  BuiltinManifest, SHA, atomic rename
  models/download/DownloadManager  OkHttp, Range resume, tar.bz2 extract commons-compress, cancellation
  vocab/PersonalVocabStore+Repository  word-boundary replace, DataStore persistence (local only)
  storage/Preferences          DataStore prefs (instant, language, engine, haptics, commands)
  ui/*                         Compose Material3 DayNight, onboarding 4 steps, home, settings, benchmark
  diagnostics/Diagnostics      local only, no transcript
```

## Threading

- Audio thread: `THREAD_PRIORITY_AUDIO`, 1024-sample reads (64ms), ringBuffer.write, pushAudio, VAD.
- ASR lane: `Dispatchers.Default` limitedParallelism(1) per engine, speculative decode every 320ms, window 10s max, threads = min(4, cores).
- Insertion: Main for InputConnection, computed off-main (stable/unstable diff).
- No UI-thread inference, no allocations per audio callback beyond 1KB chunk copy.

## Latency-critical path

`onStartInput`  → `audio.start` (within 40ms if instant) → `VOICE_RECOGNITION` → first phoneme in ring buffer → VAD onset 90ms → engine push → speculative decode 320ms → stabilizer needs 2 agreements → `setComposingText` at cursor → final commit after endpoint 700ms + decode <100ms.

Instrumentation marks: focusDetected, audioStartRequested, audioActuallyRecording, speechOnset, firstHypothesis, firstVisibleText, endpointDetected, textCommitted.

Target: warm focus→capturing <100ms, speech→first visible <450ms (stretch 300), endpoint→final <500ms (stretch 250), RTF <0.5.

## Security boundaries

- `speech/*` must not import networking. Lint rule `RestrictedApi` enforces compile fail if `okhttp` appears.
- Models stored app-private `files/canary`, `files/nemotron`, excluded from backup/data extraction rules.
- Download verifies SHA256, path-traversal guard, atomic `.tmp` → `renameTo`, free-space check via `StatFs`.

## State machine (DictationSession)

Valid: Idle→Preparing→Listening→Speech→Finalizing→Listening (continuous) →Ending→Idle. Also Listening→Finalizing on hesitation. Any→Error→Idle. Suspended for interruptions (call, mic claimed). CompositionManager resets on each session start.

## Native

`app/src/main/cpp/CMakeLists.txt` builds placeholder `libsprich_whisper.so`/`libsprich_nemotron.so`. Release replaces with real whisper.cpp (`whisper_context` mmap, `whisper_full_parallel`) + NeMo-Speech.cpp Q4_K, `arm64-v8a` only, `-O3 -march=armv8-a`, NEON.

## Build

`compileSdk 36`, `minSdk 26`, `target 36`, Kotlin 2.2.20, Compose BOM 2024.09.02, `isCoreLibraryDesugaringEnabled`, R8 fullMode, `android.nonTransitiveRClass`, `android.enableArtProfiles`.
