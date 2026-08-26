# HANDOVER — Sprich for ChatGPT Sol Max

**Date:** 2026-08-24 18:20 UTC  
**Project:** `/Users/schober/Projects/Sprich`  
**Current APK:** `Sprich-debug.apk` (116 MB, 15.7 MB `libsprich_whisper.so` real `whisper.cpp` `c122757`, 57 MB `whisper-base-q5_1.bin` `sha 422f1ae4…`) at root — built `2026-08-24 18:10` `BUILD SUCCESSFUL`, `34` unit tests pass, `lint 0`, `LOAD 0x4000` 16KB.
**Mode on handover:** `build` — you may edit, run `gradle`, `adb`.
**Git:** `main` `2cdb8d1` + `8b75588` (real whisper), `gitCount` now real `git rev-list` (was fallback `1`).

---

## 1. PLAN (Original 102-point Master)

**Thesis:** `Tap field → Speak → Words are there` as IME, not app. `WAIT. THAT'S IT?` No cloud, `focus→mic <100ms`, `speech→first visible <450ms (stretch 300)`, `endpoint→commit <500ms`, `RTF<0.5`. Privacy: `16k mono` ring `4s` RAM-only, `InputConnection` only, `INTERNET` only for explicit model download, airplane identical. 3 models via `SpeechEngine.kt:48`: Fast Whisper `Q5_1` bundled, Accurate Canary `180M INT8` (`sherpa-onnx` `files/canary`), Streaming Nemotron `0.6B Q4_K` (`files/nemotron`). Visual: quiet `FAFAF8/#121212` + pleasure radial `#FF7A67→#FF4D76→#E92B8E` (user SVG `pleasure-dot-dictation-icon.svg`).

**Where to begin now:** The 2 reported failures are **the core loop broken**: `startDictationIfNeeded → audio.start → Vad → engine.pushAudio → WhisperEngine.transcribeSnapshot → nativeTranscribe → CompositionManager.applyUpdate`. Alternative mode crash is **Canary/Nemotron path** (`EngineType.ACCURATE/STREAMING`).

---

## 2. CURRENT STATUS (Verified file:line)

**Build & Native:**
- `app/build.gradle.kts:8` `compileSdk 36 target 36 min 26 ndk 27.0.12077973 cmake 3.22.1 arm64-v8a` only.
- `native/whisper` `c122757` cloned, `app/src/main/cpp/CMakeLists.txt:13` `add_subdirectory(../../../../native/whisper)` + `whisper_jni.cpp:23` `whisper_init_from_file_with_params` `mmap` `files/whisper-base-q5_1.bin`, `lib/arm64-v8a/libsprich_whisper.so` `15.7M` `LOAD 0x4000` verified.
- `app/build/outputs/apk/debug/app-debug.apk` `91→116M` after `sherpa` AAR `app/libs/sherpa-onnx-1.12.11.aar` `37M` `libonnxruntime.so 16M` + `libsherpa-onnx-jni.so 4.8M`.

**Models:**
- Fast: `app/src/main/assets/models/whisper-base-q5_1.bin` `57M` `ModelManifest.kt:40` `sha 422f1ae4…` `59707625` bundled, `ModelManager.isFastReady >0` `requireFastReadyForRelease >50M`.
- Canary: `ModelManifest.kt:47` `https://github.com/k2-fsa/sherpa-onnx/releases/.../sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8.tar.bz2` `147M` `sha 7a38ed8b…` `files [encoder.int8.onnx, decoder, tokens.txt]` `450M` free, `ModelManager.isCanaryReady >50M` each, `DownloadManager.downloadCanary` real `Range 206` `64KB` `verifySha256` `Tar.bz2` atomic.
- Nemotron: `ModelManifest.kt:62` `https://huggingface.co/nvidia/nemotron.../resolve/main/model_q4_k.gguf` `630M` `files [model_q4_k.gguf]` `950M` free, `DownloadManager.downloadNemotron` now real `downloadAndExtract` plain `targetName model_q4_k.gguf` (was 400ms mock), `ModelManager.isNemotronReady >50M` (was `>1M`).

**Engines:**
- `speech/api/SpeechEngine.kt:48` contract `load/unload/beginSession/pushAudio/partialTranscript/endUtterance`.
- `WhisperEngine.kt:20` `scope limitedParallelism(1)` `AudioRingBuffer 30s` no boxing, `isSilence RMS<0.018`, `transcribeSnapshot` `ctxMutex.withLock` serialize `whisper_full` (was `runBlocking` now `suspend`), `load` atomic `tmp→rename + fsync` `loadMutex`, `unload` `segmentJob.join` + `ctxMutex` + `cancelChildren`.
- `CanaryEngine.kt:22` `AudioRingBuffer`, `isSilence`, `isSherpaAvailable catch Throwable`, `FeatureConfig(16000,80,0.0f)` 3-arg fallback 2-arg, `OfflineCanaryModelConfig 5-arg (encoder,decoder,src,tgt,usePnc)` fallback 5-string, `OfflineRecognizer(null, config)` for `filesDir` path, `transcribe` `getText()` fallback field.
- `NemotronEngine.kt:39` `isMock()` per `load()` not `by lazy` (was lazy one-shot), `threshold >50M`.

**IME (was crash):**
- `input/ime/SprichIME.kt:41` `InputMethodService` native `View` not `ComposeView` (killed `ViewTreeLifecycleOwner not found`), `FrameLayout` `FAFAF8/#121212` hairline, `LinearLayout 16h/14v/18b` gesture lift, `48dp` mic `ic_mic`/`ic_keyboard.xml` vector (was `⌨️` emoji), full-bar `OnClickListener` on row+mic+textBlock `~320×64dp`, `isDark()` `F5F5F3`, `updateImeUi` pleasure `#FF4D76` `Overshoot 1.08 180ms` + `3-bar waveform 140ms` `800ms` pulse, `hapticsEnabled` gated, `WarmthManager` `isDark` + `preloadIfNeeded` `onStartInput/onWindowShown`, `Vad @Synchronized`, `CompositionManager` `beginBatchEdit` `lastCommitted/needsSpace` deduped, `AudioCapture @Volatile record+audioThread join(300)`.

**UI/UX:**
- `Theme.kt:8` `Pleasure #FF4D76` `secondary/tertiary`, `ic_sprich.xml` pleasure dot, `OnboardingScreen.kt:1` breathing hero `96dp` `0.98→1.03` `1600ms`, `HomeScreen.kt:23` orb `112dp` halo, `SettingsScreen` `IconButton ArrowBack` `FlowRow` `FilterChip`, `BenchmarkScreen.kt` now real `2s 440Hz` `WhisperJNI.nativeTranscribe` measure `loadMs/inferMs/RTF` else mock.

**Production hardening done:**
- `ModelManager.checkIntegrity()` `SprichApp.kt:16`, `ThermalMonitor` `Dispatchers.IO`, `WorkManager` removed, `Vocab` precompiled `Regex` sorted, `backup_rules.xml`/`data_extraction_rules.xml` `sprich_vocab.xml`, `network_security_config.xml` `cleartext false`, `.gitignore` `*.keystore`, `LICENSE` MIT, `keystore.properties.template`, `AndroidManifest POST_NOTIFICATIONS + touchscreen required false`, `lint checkReleaseBuilds true`, `signingConfigs` now real `git rev-list`.

**Tests:** `34` pass after `ModelManagerTest.kt:31` `51M` threshold fix, `lintDebug 0`, `verify-models.sh 59707625`, `check-apk.sh speech network-free`.

---

## 3. LATEST ATTEMPTED FIXES (before handover, 18:10 build)

**Crash on start transcribing** — 4 P0s fixed in `18:10` commit `2cdb8d1`:
1. `whisper_jni.cpp:18` UAF `LOGE(path)` after `ReleaseStringUTFChars` → `std::string cppPath` + `atomic<int> g_threads` `load/store`.
2. `WhisperEngine.kt:20` concurrent `whisper_full` (`scope` vs `Dispatchers.Default`) → serialize via `scope.coroutineContext` `segmentJob.join()` + `ctxMutex` + `ctxPtr` local copy.
3. `WhisperEngine.kt:62` non-atomic `files/whisper-base…bin` copy → `tmp → rename + fsync` `loadMutex` + `50M` verify.
4. `CanaryEngine.kt:127` `catch Exception` misses `UnsatisfiedLinkError` → `catch Throwable`, `FeatureConfig 3-arg` + `OfflineCanary 5-arg` truth, `null AssetManager`.

**HelloHello spam** — fixed `RMS<0.018` gate `Whisper/Canary/Nemotron/dummy.cpp` + `CompositionManager lastCommitted/needsSpace` + `AudioRingBuffer O(1)`.

**Full-bar tappable magical** — `SprichIME` `48dp` + `waveform` + `pleasure` + `isDark` + `haptics` + `onWindowShown/Hidden` `warmth/thermal`.

**Whisper real:** `native/whisper c122757` + `whisper_jni.cpp` `whisper_full` `n_threads` + `CMakeLists 16KB` → `15.7M` lib `91→116M APK`.

**Downloadable models:** `ModelManifest` Whisper SHA pinned `422f1ae4…`, Canary `147M` SHA `7a38ed8b…` + `sherpa AAR 37M` `app/libs/`, Nemotron `630M` real `downloadAndExtract` `targetName model_q4_k.gguf`, `ModelManager >50M` gate, `Settings totalMb 147/630`.

**Build broke 15:33** `java.util.Properties` inside `android {}` → `Unresolved reference 'util'` — fixed to `gitCount=1` fallback then reverted to real `git rev-list` after `git init` `c22e72c` `8b75588`.

**Current failure after fixes:** User reports `default model doesn't transcribe anything` + `selecting alternative mode still crashes` — suggests `Whisper isSilence` gate too aggressive or `ctxPtr 0` (useMock true) or `AudioCapture` silence, and `Canary/Nemotron` `UnsatisfiedLinkError` still not caught or `ModelManager` `isReady` false after download.

---

## 4. ISSUES TO START WITH (Where to begin)

**Default (Whisper) no transcription:**
- **Most likely:** `WhisperEngine.kt:30` `useMock by lazy { !WhisperJNI.isAvailable() || !hasBundledModel() }` — `hasBundledModel` uses `assets.open().available()>0` where `available()` for compressed `57M` asset may return `~1M` not size, but check is `>0` so true; `isAvailable()` loads `libsprich_whisper.so` `15M` — on `arm64-v8a` emulator `google_apis 36` should succeed (`arm64` filter only, `x86_64` would fail → `useMock true` → mock `Hello` still should show; but user sees *nothing* → suggests `isSilence` gate `RMS<0.018` too high, or `Vad` threshold `0.015` vs `noiseFloor*1.6`, or `pcmRing` not fed because `AudioCapture` not started (permission `RECORD_AUDIO` denied, `SprichIME:431` shows `Mic permission needed` but user may miss).
- **Check:** `adb logcat | grep -E "SprichIME|WhisperEngine|whisper_init|isSilence|Vad"` — look for `Using mock` vs `Loaded ctx`, `RMS`, `Vad.State.SPEECH`.
- **File to instrument first:** `WhisperEngine.kt:128 isSilence` + `WhisperJNI.kt:16 isAvailable` + `SprichIME.kt:431 permission` + `AudioCapture.kt:34 isCapturing`.
- **Alternative path crash:**
  - `Canary` `isSherpaAvailable` now `catch Throwable` but `createSherpaRecognizer` still may throw `NoSuchMethodException` for `FeatureConfig` 3-arg vs `float` mismatch on `1.12.11` — we added fallback, but if still mismatched, `recognizer==null` → mock `Hello world canary` not crash. Crash suggests `UnsatisfiedLinkError` from `libonnxruntime.so` not caught in `transcribe` (now `catch Throwable` should catch, but `SprichIME.switchEngine` `catch Exception` still misses `Error` at `load`).
  - `Nemotron` `isMock()` per load now, but `dummy_nemotron.so` `nativeInit returns 0` — `load` does `mock load` success, not crash. Crash on select likely `ModelManager.isNemotronReady >50M` false → `load` `failure Model not downloaded` → `switchEngine fallback to Whisper` but `prev?.unload` inside `withLock` may race.

**Where to begin (ordered):**
1. **Reproduce with logs:** `adb logcat -c; adb install -r Sprich-debug.apk; adb logcat *:E | grep -E "FATAL|whisper_init|SprichIME|CanaryEngine|isSilence|RECORD_AUDIO"` — tap field → `Tap to speak` → speak → capture `FATAL` tombstone.
2. **Whisper silent gate:** Lower `WhisperEngine.kt:162 isSilence 0.018 → 0.012` or log `RMS` for real mic `ShortArray` from `AudioCapture` (currently `ShortArray 1024` `VOICE_RECOGNITION` may be near-zero if mic route is `VOICE_COMMUNICATION` vs `VOICE_RECOGNITION` on some OEMs).
3. **Verify `useMock`:** Add `Log.i("WhisperEngine", "useMock=${useMock} available=${WhisperJNI.isAvailable()} hasModel=${hasBundledModel()} isPlaceholder=${isPlaceholderModel()}")` in `load()` — if `true` → mock `Hello` should still appear, so *nothing* suggests `transcriptFlow` not observed or `CompositionManager` suppressed (`lastComposing` dedup).
4. **Canary crash:** Change `CanaryEngine.kt:40 load catch (e: Throwable)` and `SprichIME.kt:119 switchEngine catch (e: Throwable)` to catch `Error`, and make `createSherpaRecognizer` log full stack `Log.w(..., e)` already, but ensure fallback to mock never throws.
5. **IME lifecycle:** Add `try { composition.applyUpdate } catch (e: Throwable)` already, but check `currentInputConnection == null` after `onFinishInput` — `SprichIME.kt:508` `withContext(Main) { val ic = currentInputConnection }` may be `null` → `return@withContext` drops hypothesis → no transcription. Add `beginBatchEdit` check already, but verify.

---

## 5. REMAINING GOALS (Build mode — you may edit, run)

**Emulator-verifiable, no physical device needed:**
- Fix `default` transcription: lower `isSilence` or make VAD-driven, verify `whisper_full` returns non-empty for `2s 440Hz` synthetic in `BenchmarkScreen`.
- Fix `alternative mode crash`: make `Canary/Nemotron` `catch Throwable` comprehensive, ensure `isSherpaAvailable` `catch Throwable`, ensure `switchEngine` never throws `Error`.
- Sync `Sprich-debug.apk` `91M` (`app/build/outputs`) → `./Sprich-debug.apk` `116M` after `sherpa` AAR (currently `app/build` stale `116M` vs root `91M` until `cp`).
- Enable `lint` `abortOnError true` already, but `warningsAsErrors false` — keep.
- Final `assembleDebug :testDebugUnitTest :lintDebug` + `llvm-readelf 0x4000` + `verify-models.sh` + `check-apk.sh` + `rm -rf app/build` → `du -sh .` `~600M` with `native/whisper 48M`.

**Not needed for emulator:** `sherpa 198M` `402MB` extracted `/tmp/canary.tar.bz2` download test (requires 5 min), `Nemotron 630M` download (requires 950M free), `thermal` sustained `30s`, `Vulkan` benchmark — keep `CPU` default.

---

## 6. QUICK START FOR SOL MAX

```bash
export ANDROID_HOME=/Users/schober/Documents/Circadiano/.android-sdk
# If you change native whisper or libs:
rm -rf app/.cxx
./gradlew :app:assembleDebug  # -> 116M with 15M whisper + 37M sherpa
adb install -r Sprich-debug.apk
adb logcat -c; adb logcat *:E | grep -E "FATAL|whisper_init|WhisperEngine|CanaryEngine|Vad|SprichIME"
# Trigger: tap field in Keep/Chrome → Tap to speak → speak "Hello world"
# Check diagnostics if crash: adb shell run-as com.sprich.app.debug cat files/diagnostics/crash.log
./gradlew :app:testDebugUnitTest  # 34 pass
./scripts/verify-models.sh && ./scripts/check-apk.sh
```

**Repo secrets:** No `.git` remote, `keystore.properties.template` → `keystore.properties` for release, `ModelManifest` `sha256` for Canary pinned `7a38ed8b…`, Nemotron blank pending.

**Decision log:** Direct `HF resolve/main/model_q4_k.gguf` (not page), `CPU` default `Vulkan` fallback, `Cancel keeps tmp` as pause, `WorkManager` removed, `Pleasure` accent minimalism.

**You take ownership now — decide UX vs technical balance best, no more questions.**

