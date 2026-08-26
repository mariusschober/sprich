# PLAN — Sprich · Local-Only Android Dictation

> Tap a text field. Speak. The words are there.

**Date**: 2026-08-24 · **Target**: v1 shippable Android app, offline-only ASR, IME-first

---

## 1. Repository Assessment

- Workspace `/Users/schober/Projects/Sprich` was empty (fresh project).
- Host is macOS 26.5.2, Xcode 26.6, Java 18.0.1.1, Gradle 9.3.1 via wrapper, Android SDK at `/Users/schober/Documents/Circadiano/.android-sdk` (cmdline-tools 20.0, build-tools 36.0.0, platforms 36 & 34, platform-tools 37, emulator 36.6.11).
- NDK not installed yet (required for whisper.cpp / sherpa / NeMo). Will install ndk;27.0.12077973 (or latest 28.x). CMake not installed — install via sdkmanager `cmake;3.22.1` + Homebrew fallback.
- Reference project `NostrShotAndroid` provides proven patterns: DataStore, Compose BOM 2026.06.00, sherpa-onnx AAR local, OkHttp, coroutines, secp256k1. Reuse Gradle structure, not UI.
- No whisper model bundled yet; need to source Whisper base multilingual Q5_1 (~57 MB GGML).
- No physical mid-range device available in CI — instrumentation + benchmark mode required; mark hardware-dependent tests as manual gate.

---

## 2. Chosen Architecture

### Module layout
```
Sprich/
  app/                          # single :app module for v1 (fast iteration)
    src/main/
      java/com/sprich/app/
        SprichApp.kt
        core/
          audio/                # RingBuffer, AudioCapture, AudioRoute
          vad/                  # EnergyVAD + WebRTC-style thresholds
          perf/                 # LatencyTracker, BenchmarkRecorder
          privacy/              # NetworkIsolation, NoLog
        speech/
          api/                  # SpeechEngine, Session, TranscriptUpdate, Capabilities
          whisper/              # WhisperEngine (JNI + mock fallback)
          canary/               # CanaryEngine (sherpa-onnx INT8)
          nemotron/             # NemotronEngine (NeMo-Speech.cpp streaming)
          stabilization/        # TranscriptStabilizer (stable-prefix)
          warmup/               # ModelWarmth FSM
        input/
          ime/                  # SprichIME service, Keyboard affordance
          composition/          # CompositionManager, Delta logic
          commands/             # SpokenEditingParser (EN/DE/ES)
          lifecycle/            # DictationSession FSM, field detection
        models/
          manager/              # ModelManager, ModelManifest, atomic install
          download/             # DownloadWorker, checksum, resume
        vocab/                  # Personal vocab trie, ITN deterministic
        ui/
          onboarding/           # 4-screen onboarding + Trial field
          home/                 # Minimal Ready screen
          settings/             # Dictation, Personalization, Behavior, Privacy, Advanced
          benchmark/            # Hidden dev screen
          theme/                # Material3, light/dark, typography
        diagnostics/            # Local logs (no transcript), export JSON
  native/
    whisper/                    # CMake + whisper.cpp submodule (or vendored)
    nemotron/                   # NeMo-Speech.cpp placeholder
```

### Why single module
- v1 speed: avoids KMP/extra modules overhead. Separation is package-level, clear to split later.
- Enables future `:speech:api` extraction when engine swap is stable.

### Key contracts
```kotlin
interface SpeechEngine {
  suspend fun load(): Result<Unit>
  suspend fun unload()
  fun beginSession(config: SpeechSessionConfig)
  fun pushAudio(samples: ShortArray, tsNanos: Long)
  fun partialTranscript(): Flow<TranscriptUpdate>
  suspend fun endUtterance(): FinalTranscript
  fun cancelSession(); fun reset()
  fun capabilities(): SpeechEngineCapabilities
  fun supportedLanguages(): Set<Language>
}
data class TranscriptUpdate(val stable: String, val unstable: String, val isFinal: Boolean, val tokens: List<Token>?)
sealed class DictationState { IDLE, PREPARING, LISTENING, SPEECH, FINALIZING, ENDING, ERROR, SUSPENDED }
```

### Thread lanes
- `AudioThread` : AudioRecord callback, ring buffer, zero alloc, `SCHED_FIFO` not required but high priority HandlerThread
- `VAD thread` : cheap energy calc, shared with audio
- `ASR thread` : Dispatchers.Default + limitedParallelism(1) per engine, never Main
- `Stabilization` : Default, batches hypothesis
- `Insertion` : Main for InputConnection, but work computed off-main

---

## 3. Current Android Constraints (verified 2026-08-24 from developer.android.com)

- **InputMethodService**: canonical IME lifecycle `onCreate()`, `onCreateInputView()`, `onStartInput(EditorInfo, Boolean)`, `onFinishInput()`, `onStartInputView()`. Requires XML `input-method` + `android.permission.BIND_INPUT_METHOD`. No special permission for mic; `RECORD_AUDIO` runtime required. EditorInfo.inputType masks password: `TYPE_TEXT_VARIATION_PASSWORD | VISIBLE_PASSWORD | WEB_PASSWORD`. Must check `TYPE_NUMBER_VARIATION_PASSWORD` etc.
- **Foreground Service for Mic** (Android 14+): mic requires foreground service notification if started outside IME/while app background. IME can record while input view visible without FGS, but `AUDIO_CAPTURE` foregroundServiceType needed for accessibility overlay mode. Since target 34/36, declare `FOREGROUND_SERVICE_MICROPHONE` and use `MediaRecorder.AudioSource.VOICE_RECOGNITION`.
- **Accessibility overlay**: `AccessibilityService` + `TYPE_APPLICATION_OVERLAY` windows need `canRetrieveWindowContent` + user-enabled switch. Play policy (2024-25) strictly reviews accessibility usage; Sprich will request only after explicit toggle, label experimental, fallback to IME primary. Overlay mic must show persistent notification (FGS) on Android 14+.
- **Haptics**: `VibrationEffect.createPredefined(EFFECT_TICK)` API 29+; fallback `VIBRATION_EFFECT_SUPPORT` check.
- **AudioRecord**: 16k mono PCM16 lowest latency; `getMinBufferSize` *2, `AudioFormat.CHANNEL_IN_MONO`, `VOICE_RECOGNITION` for NS/AGC off. Ring buffer 4 sec (64k samples) = 128KB.
- **Do-not-hijack**: no clipboard primary path, no `WRITE_SECURE_SETTINGS`, no hidden mic. User must select Sprich IME explicitly via system picker (`InputMethodManager.showInputMethodPicker()`). We cannot programmatically set default IME without user action — doc this.

### Verification correction vs prompt assumptions

- **Whisper.cpp Q5_1**: upstream supports Q5_0/Q5_1/Q8_0; Q5_1 exists as `ggml-model-q5_1.bin` via `quantize`. Good to bundle.
- **Canary via sherpa-onnx**: verified tar at `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8.tar.bz2` contains `encoder.int8.onnx, decoder.int8.onnx, tokens.txt`. License Apache-2.0 + model CC-BY-4.0 — attribution required. Sherpa 1.12.11 AAR not on Maven Central; use JitPack or local AAR as NostrShotAndroid does.
- **Nemotron Q4_K GGUF**: `NeMo-Speech.cpp` repo exists but Vulkan backend coverage is partial (Adreno/Mali varies, CPU fallback common). Correct to benchmark and default CPU.
- **InputMethodManager auto-mic**: cannot auto-start mic while another IME is active — primary path must be Sprich-as-IME. Companion mode must be labeled experimental.

---

## 4. Model Runtime Plan

### Model 1 — Fast (bundled)
- Artifact: `whisper-base multilingual q5_1` = 57 MB. Source: `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin` then quantize via `quantize` binary, or direct `ggml-base-q5_1.bin` from community (verify SHA). Store in `app/src/main/assets/models/whisper-base-q5_1.bin`.
- Runtime: `whisper.cpp` via NDK, `JNI` bridge `libsprich_whisper.so`, `whisper_context` reused, `whisper_full_parallel` with `n_threads = min(4, cores)`. Memory-mapped via `mmap`.
- Threading: decode on `Dispatchers.Default`, chunk 1.2-1.5s sliding window.

### Model 2 — Accurate
- `canary-180m-flash int8` ~198 MB download. Runtime `sherpa-onnx` OfflineRecognizer (non-streaming). No re-do heavy windows; use VAD-gated 0.8-2s windows + stable prefix.
- Download: OkHttp range support, SHA256 manifest, atomic rename from `.tmp` → `files/canary/<version>/`.

### Model 3 — Instant Streaming
- Nemotron 0.6B Q4_K GGUF (~500-700 MB). Runtime `NeMo-Speech.cpp` RNNT cache-aware. Feed 160 ms chunks (profile 80/160/240/320). Backend: CPU default, optional Vulkan via `VkBackend` if `vkEnumerateInstanceVersion` succeeds and device not in deny-list. Fall back cleanly if RTF >0.9.
- Guard: only advertise if benchmark RTF <0.5 on device; else recommend Fast.

### Network isolation
- `speech:*` packages must not import `okhttp` / `java.net`. Enforce via dependency rule + lint `RestrictedApi`. Networking only in `models/download`.

---

## 5. Implementation Phases

| Phase | Goal | Deliverable |
|-------|------|-------------|
| 1 Foundation | Gradle + privacy-safe logging + SpeechEngine API + ring buffer + mock Whisper | App compiles, benchmark screen skeleton, unit tests |
| 2 Input Magic | SprichIME + InputConnection composing + Instant/Tap + haptics + password guard + FSM | Tap→speak→text in test fields (mock transcripts) |
| 3 Latency | VAD + speculative Whisper + stable-prefix + pre-buffer + profiling | Mock latency <450 ms first partial measured |
| 4 Product UI | Onboarding, Home, Settings, Privacy, dark/light, perf overlay | Polish, empty states, copy |
| 5 Canary | DownloadManager + sherpa engine + benchmark | Canary switch works, integrity verified |
| 6 Nemotron | NeMo CPP bridge + streaming path + CPU/Vulkan bench | Streaming 160ms chunks, RTF logged |
| 7 Intelligence | Commands EN/DE/ES + vocab trie + ITN | Deterministic tests |
| 8 Compat | Accessibility experimental mode + app matrix docs | Listed limitations |
| 9 Ruthless Polish | 10-pass polish loop + instrumentation percentile | p50/p90 targets |
| Gates | Size check, airplane mode, duplication tests, security audit | Release QA |

---

## 6. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| whisper.cpp build fails on NDK 27-28 / CMake missing | Blocks Fast model | Vendor prebuilt `.so` for arm64 + graceful mock fallback; CI script pins NDK 27.0.12077973 |
| sherpa-onnx AAR not on Maven, JitPack downtime | Blocks Canary | Keep local `libs/sherpa-onnx-1.12.11.aar` + reflection isolate, still builds if missing |
| NeMo-Speech.cpp Vulkan jank/ thermal | Bad RTF | Default CPU, benchmark gate, never assume Vulkan faster |
| IME cannot auto-mic with Gboard active | Breaks "work with my keyboard" | Label experimental, primary path = Sprich IME |
| Duplicated composition text | QA blocker | Delta logic tests + `setComposingText`/`commitText` strict state, cursor movement aborts composition |
| Password field false negative | Privacy violation | Dual check `EditorInfo.inputType` + `AccessibilityNodeInfo.isPassword` + autofill hints |
| Model download corruption | Brick | SHA256, atomic install, rollback, free-space check, resume |
| Play Store analytics SDK ban | Privacy | Zero analytics/inspector, local-only logs, manifest includes no `AD_ID` |
| Budget phone RTF >1.0 | Latency collapse | Adaptive decode interval, queue backpressure, recommend Fast fallback prompt |
| NDK download timeout on CI | CI flaky | Pre-cache NDK, gradle option `android.ndkVersion` pinned, doc manual `sdkmanager --install` |

---

## 7. Benchmark Methodology

- **Synthetic audio**: 5s EN/DE/ES clips (quiet, street, car) bundled in `testAssets` (not APK). Same files for all engines.
- **Metrics**: `focus→audioStart`, `speechOnset→firstPartial`, `endpoint→final`, `RTF = processingMs / audioMs`, `peakRSS`, `threadCount`, `backend`, `temp`.
- **Instrumentation**: `LatencyTracker` uses `SystemClock.elapsedRealtimeNanos()` monotonic. Marks: `focusDetected`, `audioStartRequested`, `audioActuallyRecording`, `vadSpeechOnset`, `firstInferenceStart`, `firstHypothesis`, `firstVisibleComposing`, `endpointDetected`, `finalInferenceStart`, `finalCommitted`. Report p50/p90/p95.
- **Hidden screen**: 7 taps on version → `BenchmarkActivity` displays table + `Export JSON` to `files/benchmark/`.
- **Adaptive recommendation**: Run 10s synthetic on first launch (no mic) → if Canary RTF <0.7 recommend Accurate; if Nemotron RTF <0.5 recommend Streaming; else Fast.

---

## 8. UX Acceptance Criteria (quant + qual)

- Warm field→capturing <100 ms (stretch <50 ms)
- Speech→first visible <450 ms (stretch <300 ms)
- Endpoint→committed <500 ms (stretch <250 ms)
- RTF <0.5 (ideal <0.25)
- No duplicated words (0/1000 hypothesis transitions)
- Password fields never trigger mic (100% on test matrix)
- Airplane mode identical (minus download)
- Rapid 3-field switch no stale/lost text
- Hesitation “I think… hmm… Thursday” not prematurely committed
- German/Spanish first-class punctuation
- Settings: 0 unnecessary taps; Instant Dictation consent explicit.

---

## 9. Source Verification Checklist (pre-release)

- [ ] whisper.cpp Android NDK build & quantization verified against `ggml` docs
- [ ] sherpa-onnx Canary API (OfflineRecognizer 1.12.x) checked (FeatureConfig ctor differs per version → reflection fallback)
- [ ] NeMo-Speech.cpp Q4_K support status
- [ ] Android 14/15/16 FGS mic restrictions re-checked
- [ ] InputMethodService lifecycle on API 28-36
- [ ] Play foreground-service + accessibility disclosure requirements.

---

## 10. Immediate Next Steps

1. Bootstrap Gradle project in this repo (this file's directory).
2. Implement core Kotlin layers with no network.
3. Build debug APK, run instrumentation tests in emulator.
4. Wire Whisper JNI scaffold (mock if NDK lacking, then full).
5. Polish UI to spec’s visual language (quiet, precise, no purple gradients).

