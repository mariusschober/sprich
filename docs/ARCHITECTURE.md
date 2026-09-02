# ARCHITECTURE — Sprich

## Pipeline (2026-09-03: API + refinement)
```
Microphone
  ↓
UtteranceAudioCollector (neutral, primitive, bounded 30s, strict size<=maxSamples, oversized keeps tail)
  ↓
immutable UtterancePlan + PCM (frozen at VAD onset)
  ↓
TranscriptionCoordinator
├── Local Automatic — Tiny LID (98M) → FastConformer CTC 126M
├── Local Accurate — Canary 180M Flash INT8
├── API Primary — RemoteSttProvider (openai-compatible) → optional local fallback
└── Local → API fallback — local first, remote only on blank/exception
  ↓
Base TranscriptionResult (ResolvedUtteranceLanguage Known/Unknown, source, timing)
  ↓
Deterministic SpokenEditingParser / ITN (before refinement, no sentinels to LLM)
├── editor action → execute locally
└── text → optional Refinement (Off/Correct/Clean, deadline, validator) → typography → exactly-once commit
```

## Legacy Input → Audio → VAD → Engine → Stabilizer → Composition → InputConnection

```
FieldSession (field focus → window hidden/loss/password): sessionId X, can contain N utterances
  Utterance 1: onset (preRoll owned exactly once by engine) → Capturing → VAD UTTERANCE_END → freeze PCM → endUtteranceWithSnapshot(frozen) → commitUtterance(utteranceId=1) → Inserting → Listening (same field session X)
  Utterance 2: onset (new preRoll, live buffer reused, frozen snapshot already queued) → ... → commitUtterance(utteranceId=2) → Listening
  ...
  FieldLost → Ending → Idle (only actual field loss/password/error ends field session, not successful utterance)

onStartInput / focus → FieldSessionController.onFieldFocused [sessionId++] → audio.start [40ms] → VAD onset 45ms → engine.beginUtteranceCapture(preRoll) owns preRoll exactly once → speculative decode (350ms, inferenceMutex, epoch-checked) → TranscriptStabilizer(LCP N=2) → FieldSessionController.applyPartial (replay=0, epoch guard) → composition (IME-preview fallback, no destructive delete) → VAD UTTERANCE_END → freeze snapshot before live reuse → endUtteranceWithSnapshot(frozen) [exactly once per utteranceId via finalized set] → commitUtterance (Inserting→Listening, keeps field session) → re-arm engine.beginSession for next utterance
                         ↘ late callbacks with old generation/fieldGeneration/sessionId/utteranceId discarded; stale partials dropped via sessionEpoch
                         ↘ continuous capture: next onset not blocked by previous final (decode serialized via mutex, live capture via separate PCM buffer/shnapshot)
```

  ## Modules (2026-09-02 sprint 3 + dual-color addendum ad1fdb8)

```
app/src/main/java/com/sprich/app/
  SprichApp.kt                 owns and preloads the single process-wide Canary engine (Fast alias)
  core/audio/AudioCapture.kt   16k mono PCM, ring 30s, single ShortArray readBuf zero-copy via startWithOffset, single RMS, requestStop/awaitStop split
  core/audio/AudioRingBuffer.kt lock-ish ring 30s, O(1) drop, write(offset,len) zero-copy
  core/audio/UtterancePcmBuffer.kt primitive bounded 30s, append(offset,len) zero-copy, frozen immutable
  core/audio/UtteranceAudioCollector.kt neutral authoritative PCM (bounded 30s, size<=maxSamples strict), freeze isolates pending
  core/audio/Resampler.kt      linear resampler 48k/44.1k→16k mono PCM16, tested, bounded
  core/audio/AudioDiagnostics.kt developer-only WAV capture, verifies actual device rate/channel/encoding
  core/vad/Vad.kt             energy, calibration 150ms, onset 45ms, hesitation 400ms, endpoint 650ms, adaptive threshold
  core/perf/LatencyTracker    monotonic marks p50/p90, BenchmarkRecorder
  core/perf/ThermalMonitor    /sys/class/thermal polling
  core/privacy/PrivacyGuard   log redaction, network-isolation lint
  core/security/EndpointValidator  central isValidHttpsUrl (HTTPS only, no userinfo, valid host, debug localhost)
  speech/api/SpeechEngine.kt  contract + EngineType + TranscriptionTask.TRANSCRIBE + SpeechLanguage.Auto|Fixed(BCP-47)
  speech/canary/CanaryEngine  Canary 180M Flash INT8 via sherpa-onnx INT8 (Accurate), serialized JNI, reflection cached, src==tgt never translates
  speech/fastconformer/FastConformerEngine  FastConformer CTC 126M (Automatic primary), sherpa INT8, reflection cached, 16KB
  speech/lid/WhisperLidEngine  Whisper Tiny spoken-language-ID INT8 (98M, per-utterance, reflection cached)
  speech/nemotron/NemotronEngine  dormant prototype, runtime not packaged (hidden behind DEBUG)
  speech/remote/RemoteSttProvider  abstraction: OpenAiCompatible/MetaMuse (https://api.meta.ai) / Gemini (generativelanguage.googleapis.com), bounded 8KB, followRedirects false, pooled sharedClient
  speech/refinement + ai/OpenAiCompatibleRefinementProvider  OFF/CORRECT/CLEAN, DATA-block, bounded
  speech/stabilization/TranscriptStabilizer  LCP of last N=2, word-level
   input/ime/SprichIME         InputMethodService — dual-color bar (local red #FF4D76 vs API blue #3B6BFF, isApiPalette via frozen ActiveUtterance ApiPrimary vs idle mode+valid remote, no mid-utterance hue jump for LOCAL_API_FALLBACK), axis-locked gestures (swipe up switch → previous/next/picker, left delete/right undo/down newline, one mutation per gesture), Choreographer single visual lane (no Math.random/ValueAnimator fighting), immediate ACTION_DOWN press, password guard, utteranceId monotonic, sessionGeneration/fieldGeneration, frozen UtterancePlan+PCM at onset, FIFO pendingChannel(4), suppressEpisode backpressure, local-cold for API_PRIMARY, redirect-blocked pooled OkHttp, exactly-once via finalizedUtterances LRU 128, onDestroy field-tied cleanupScope (<50ms wall)
   input/composition/CompositionManager  IME-local partials (no external setComposingText → no HelloHello), single irreversible commitText (no retry on ambiguous false), discardPartial vs commitFinal distinct, no destructive delete
   input/lifecycle/DictationSession FSM  Idle→Preparing→Listening→Speech→Finalizing→Inserting→Listening (per utterance) →Ending→Idle (field loss only), sessionId per field focus
   input/lifecycle/FieldSessionController single authoritative field session owner, commitUtterance (Inserting→Listening keeps field alive) vs commitFinal (Ending→Idle), utteranceId exactly-once set, cross-field guard, typed CommitResult (Committed/EditorRejected/Stale etc.)
   input/lifecycle/UtteranceToken immutable (sessionId, generation, utteranceId, fieldId/fieldGeneration, capturedIc) — exactly-once claim
   input/commands/SpokenEditingParser  deterministic EN/DE/ES, word-boundary punctuation, language-aware ITN (EN email only), no substring backtracking
  models/manager/ModelManager+Manifest  BuiltinManifest (Canary/Fast/Tiny 2026-09-01 SHA), SHA atomic rename, isAutomaticReady gated (>5M threshold accommodates test fixtures)
  vocab/PersonalVocabStore+Repository  word-boundary replace, DataStore persistence (local only), gesture legend
  storage/Preferences          DataStore prefs (instant, speechLanguage BCP-47, transcriptionMode, sttProviderId/sttStreamingEnabled, haptics, commands) — suggestLanguageFromLocale suspend (no runBlocking)
  ui/*                         Compose Material3 DayNight, onboarding 4 steps (jargon-free), home (Ready to speak), settings (Digital clean IA, gesture legend, DEBUG-gated benchmark/nemotron); colors api_* blue for bar
  diagnostics/Diagnostics      local only, no transcript, no raw audio by default, opt-in export
  diagnostics/ReplayHarness    debug WAV capture in noBackupFilesDir/sprich_replay (never backed up), paired WAV+.meta deletion, clearAll
```

## Threading

- Audio thread: `THREAD_PRIORITY_AUDIO`, 1024-sample reads (64ms), ringBuffer.write, pushAudio, VAD, clipping/RMS telemetry, dropped-frame accounting via ring overwrite.
 - ASR lane: one `Dispatchers.Default.limitedParallelism(1)` + `inferenceMutex` single owner — every recognizer op (create/setConfig/createStream/accept/decode/getResult/unload/language) inside `withLock`; speculative partial loop also holds mutex and epoch-checked; cancellation does not interrupt blocking ONNX call, unload waits for mutex; true concurrency ==1 measured via `nativeDecodeMaxConcurrency`.
- Insertion: Main for InputConnection, computed off-main (stable/unstable diff).
- No UI-thread inference/model/disk work, no allocations per audio callback beyond 1KB chunk copy; all AudioRecord/model/coroutine jobs released on pause/focus-loss/service-destruction.
- Structured concurrency: CoroutineScope(SupervisorJob) + limitedParallelism; cancellation via job.cancel() and native abort callback; microphone released <1s after stop.

 ## Latency-critical path

`onStartInput`  → `audio.start` (within 40ms if instant, ring pre-roll 250ms retains first phoneme) → `VOICE_RECOGNITION` → first phoneme in ring buffer (bounded 30s, circular pre-roll) → VAD onset 45ms (adaptive, hysteresis) → engine push (mono PCM 16k, resampled if needed) → speculative decode 350ms → stabilizer needs 2 agreements → IME-local preview (no external `setComposingText` → no HelloHello) → VAD utteranceEnd 650ms → freeze immutable PCM snapshot → FIFO actor → single irreversible `commitText` (Inserting) → sessionId/fieldGeneration/utteranceId validated, stale callbacks discarded, `suppressEpisode` prevents tail capture while `catchingUp`.

Instrumentation marks: focusDetected, sessionId, audioStartRequested, audioActuallyRecording, speechOnset, firstHypothesis, firstVisibleText, endpointDetected, inserting, textCommitted.

Target: warm focus→capturing <100ms, speech→first visible <450ms (stretch 300), endpoint→final <500ms (stretch 250), RTF <0.5.
TIER BUDGETS: mid Snapdragon 6GB RAM p95 focus→capturing 150ms, p95 endpoint→final 800ms; high-tier p95 focus→capturing 100ms, p95 endpoint→final 500ms; low-tier (3GB) p95 focus→capturing 250ms. P50/P95 and RTF reported honestly via Benchmark screen.

 ## Security boundaries

- `speech/*` except `speech/remote` must not import networking. Lint `check-apk.sh` enforces that only `speech/remote` (opt-in backup STT) may import `okhttp`/`java.net`; all other speech code stays local-first. Remote is LOCAL by default in Preferences (stt_mode=local); cloud path requires explicit baseUrl/apiKey/model and is observable in diagnostics.
- Credentialed BYOK OkHttp clients `followRedirects(false)` `followSslRedirects(false)` (pool preserved via `newBuilder()`), 3xx treated as typed `Http(Redirect blocked)`, `Authorization`/audio not forwarded – verified via MockWebServer `307→B 0`. `EndpointValidator` is single source for `isValidHttpsUrl` (HTTPS only, no userinfo, valid host, debug localhost).
- The Canary model is device-side in `files/canary` (encoder.int8.onnx, decoder.int8.onnx, tokens.txt) verified by SHA-256 and atomic rename; prebuilt sherpa libs (`libonnxruntime.so`, `libsherpa-onnx-*.so`) are packaged, model data is not bundled in APK.
- Internet permission exists only for explicit opt-in remote STT/AI polish (Advanced settings); normal dictation uses no network and airplane mode works identically. `speech/remote` and `ai/GrammarFixer` are isolated and not invoked unless configured. `CancellationException` never triggers cloud fallback.

 ## State machine (DictationSession — reducer)

Idle → Preparing(Arming) → Listening → Speech → Finalizing → Inserting → Listening (continuous) → Ending → Idle. Also Listening→Finalizing on hesitation. Listening→Suspended/Paused on interruption (call, mic claimed, explicit pause). Any→Error(RecoverableError)→Idle. Suspended→Listening on resume. sessionId increments per start(); late callbacks with old sessionId are ignored. CompositionManager resets on each session start; decoder context is reset/bounded on language/field changes (clear pcmRing, reset stabilizer, clear detectedLanguage cache after 30s silence). `USER_STOP` freezes current valid utterance (`collector.size>8000`) and enqueues via same FIFO `pendingChannel` after earlier accepted utterances; actor drains queue then increments `sessionGeneration` → Idle (no loss, no reorder, no new capture after Stop). Utterance-scoped failures (`EditorRejected`, timeout, refinement, blank, stale) call `failUtteranceScoped` and keep field `Listening`; only mic/field/service/active-engine corruption call global `failSession`. Valid `TranscriptionCoordinator` `CancellationException` never falls back to remote.

## Native

`app/src/main/cpp/CMakeLists.txt` is a minimal valid CMake enforcing 16KB page size; the current primary runtime is Canary via `libsherpa-onnx-1.12.11.aar` (sherpa-onnx JNI, `libonnxruntime.so`, `libonnxruntime.so` 16KB aligned, `libsherpa-onnx-*.so`), arm64-v8a only, CPU backend, 2 threads. Initialization/transcription/release are serialized via `Dispatchers.Default.limitedParallelism(1)` and a single native mutex; active inference is cancellable via `job.cancel()` before finalization. A former whisper.cpp runtime (`libsprich_whisper.so`) was deleted per user request; CMake now keeps a stub for Gradle externalNativeBuild. No Nemotron library is built.

## Build

`compileSdk 36`, `minSdk 26`, `target 36`, Kotlin 2.2.20, Compose BOM 2024.09.02, `isCoreLibraryDesugaringEnabled`, R8 fullMode, `android.nonTransitiveRClass`, `android.enableArtProfiles`.
