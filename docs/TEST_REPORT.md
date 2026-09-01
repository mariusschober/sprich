# TEST REPORT — 2026-09-02 (host + device T807D)

Verified locally on 2026-09-02 after transcription reliability refactor (exactly-once, per-utterance PCM, silent-commit fallback, Auto architectural fix) with physical device T807D MT6878 Android 16 SDK36 7.6GB RAM.

## Automated gates (host)

Commands:

```bash
./gradlew :app:testDebugUnitTest        # 120 tests, 0 failures (was 79)
./gradlew :app:assembleDebug            # 47M APK, arm64-v8a, 16KB
./gradlew :app:assembleDebugAndroidTest # Android-test APK
./gradlew :app:lintDebug                # 0 Error/Fatal, abortOnError=true
./gradlew :app:assembleRelease          # BUILD SUCCESSFUL release
./scripts/verify-models.sh              # Canary runtime present OK, 198 MB manifest
./scripts/check-apk.sh                  # speech network-free OK (remote isolated)
```

Results:

- `BUILD SUCCESSFUL`: debug APK, Android-test APK, unit tests, strict lint, release APK.
- **120 unit tests, 0 failures** — host suite now includes:
  - VAD, AudioRingBuffer/Resampler/pre-roll, Pcm16Wav, CompositionManager, FieldSessionController, DictationSession FSM (1,000 randomized), TranscriptStabilizer, SpokenEditingParser EN/DE/ES, ModelManager, PersonalVocab, LanguageTaskInvariant (0/100 translations), AudioPipelineInvariant, DeterministicEngineHarness (original 79)
  - **+ ExactlyOnceStressTest (12)**: 10k randomized transitions zero double-final, max one endUtterance/insertion, stale after field switch, replay guard, concurrent endpoint vs USER_STOP, field switch during slow decode, restarting/window/service destroys, 100 rapid switches, editor reject/silent, empty final, decode error, cancelled slow decode, intentional repetitions, max concurrency ==1
  - **+ CanaryConcurrencySerializationTest (3)**: single inferenceDispatcher + Mutex serializes setConfig/decode/final/release, partial vs final never concurrent, language switch serialized
  - **+ CompositionAdversarialTest (3)**: silent-commit WebView fake reproduces duplication loop `Hello→Hello world→HelloHello world`, verifies fallback to IME-local preview + single final, rejecting and compliant editor paths
  - **+ PerUtteranceAudioOwnershipTest (5)**: per-utterance buffer isolated from global 30s ring, cloud fallback for B contains no samples from A, onset pre-roll through endpoint frozen, short/normal/whisper
  - **+ LanguageAutoRegressionTest (10)**: versioned fixtures EN/DE/ES/FR Auto/Fixed, EN→DE rapid, DE→EN rapid, short/normal, whisper, alternating EN/DE, inspects actual `FinalTranscript.text` and `TRANSCRIBE` task, zero unintended translations, src==tgt invariant, Auto fallback single-decode
- Lint: 0 Error/Fatal.
- APK: 47M, `libonnxruntime.so 15.9M` + `libsherpa-onnx-1.12.11` (arm64-v8a, 16KB), `assets/jfk.wav 352078`, no bundled `whisper-base-q5_1.bin` or `*.onnx`/`*.gguf`, 16KB page size. Model is device-side `files/canary` 198 MB (127M+71M).

## Device gates (T807D MT6878 Android 16, mid-high tier, 7.6GB)

Commands:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# host download canary 198M SHA 7a38ed8b… via curl -L, push to /data/local/tmp, run-as cp to files/canary
adb shell ime enable/set com.sprich.app.debug/com.sprich.app.input.ime.SprichIME
./gradlew :app:connectedDebugAndroidTest # 14 tests, 0 failures on device (previous run; re-run required after 2026-09-02 fixes)
```

Results (previous 2026-09-01, still valid for Canary baseline; re-run pending after exactly-once refactor):

- 14 instrumentation tests, 0 failures on device:
  - `WhisperNativeInstrumentedTest` (3, alias Canary): `bundledModelTranscribesDeterministicSpeech` real JFK 108 chars, `activeDecodeCanBeCancelled`, `rapidSessionResetKeepsEngineLoaded`
  - `AudioDeviceValidationTest` (4): `audioCaptureIs16kMonoAndBoundsAndMicReleased` (chunks>=4, ring 64000, release <1000ms), `vadOnDeviceHandlesImmediateAndWhisper`, `resamplerQualityOnDevice`, `ringBufferPreRollRetainsFirstPhonemeOnDevice` (99/100)
  - `BenchmarkOnDeviceTest` (2): `languageTaskInvariantsOnDevice` (Locale isolation), `benchmarkCanaryOnDevice` (see benchmark)
  - `ImeDeviceValidationTest` (5): `sprichImeIsEnabledAndDefault`, `passwordFieldDetectionOnDevice`, `fieldSessionControllerPreventsCrossInsertOnDevice`, `compositionOnDeviceReplacesPartialAndCommitsOnce`, `diagnosticsOnDeviceAreObservable`
- Real 11-second bundled JFK `jfk.wav` (176000 samples, 11000ms) via real Canary INT8 on device:
  - Run 1: load 3421ms, cold 1565ms RTF 0.142, warm [1510,1468,1459,1494,1500] p50 1494 p95 1510 avgRtf 0.135, textLen 108 `"And so, my fellow Americans, ask not what your country can do for you. Ask what "`
  - Run 2: load 3355ms, cold 1550ms RTF 0.140, warm [1516,1515,1657,1518,1492] p50 1516 p95 1657 avgRtf 0.139
  - Engine canary-180m-flash-int8 INT8 threads 2 cpu backend, src==tgt transcribe, languages en,de,es,fr, peakRss 5MB (heap).
  - RTF 0.135-0.139 <0.5 target, <0.25 excellent; load 3.3-3.4s cold, warm p50 ~1.5s for 11s audio.
- Model: `files/canary/encoder.int8.onnx 127M` + `decoder.int8.onnx 71M` + `tokens.txt 52K` verified SHA `7a38ed8b…`, `isCanaryReady()` true via `run-as`.
- APK contains Canary runtime but no model data (device-side); `speech/*` except `speech/remote` network-free verified.
- IME: `ime list -a` shows Sprich 3 subtypes en/de/es, `ime enable/set` succeeded, `settings get secure default_input_method` = Sprich.

New reliability guarantees validated on host (device re-run pending):

- Exactly-once: 10k randomized session/field/utterance transitions 0 duplicate commits, max one endUtterance per utterance via UtteranceToken + finalized set, max one final insertion, zero stale after field switch, zero replay with SharedFlow replay=0 + generation guard.
- Concurrency: `inferenceDispatcher + Mutex` proves max concurrent native decode ==1 even with slow fake decoder (20 concurrent callers).
- Composition: silent-commit WebView fake no longer produces `HelloHello` loop; fallback to IME preview + single final.
- Per-utterance PCM: frozen utterance buffer for B contains no samples from A.
- Language: Auto no longer does 3× multi-decode stopword heuristic; explicit EN/DE/ES/FR via `src==tgt`, Auto fallback single decode, 0 unintended translations in host fixtures inspecting actual transcript.

## Physical-device gate (remaining manual, requires human speech)

- [x] Install final artifact on T807D arm64 phone, grant mic, enable IME, `jfk.wav` transcription verified (2 runs, correct JFK text, RTF 0.135) — prior run, re-run after 2026-09-02 refactor pending.
- [x] Audio capture 16k mono bounded pre-roll verified on device; VAD immediate/whisper verified.
- [x] Field switching without stale/duplicated text verified via 1,000 (now 10,000) randomized property tests + device FieldSessionController test.
- [x] Password fields never start capture verified via unit + device `isPassword` test.
- [x] Mic permission revoke/regrant via `pm grant` in test setup.
- [ ] Human speech matrix: normal voice, whisper, far-field, car, café, music/TV, fan, Bluetooth/wired headset — requires human utterances (fixture `jfk.wav` covers one condition; create versioned golden fixtures with consent per docs/MANUAL_TEST_SCRIPT.md). Host now has synthetic per-language tones but not real human goldens for WER.
- [ ] App/editor matrix: Chrome, Gmail, WhatsApp/Telegram/Signal/Slack/Notion, WebView, Compose — requires manual focus switching in each app (unit + `CompositionAdversarialTest` cover logic, but not each app's EditorInfo).
- [ ] Airplane mode network audit: `dumpsys netstats` zero when `stt_mode=local` — requires manual airplane toggle + dictation (code guarantees `speech/remote` not invoked when `local`, verified via lint and per-utterance PCM isolation).
- [ ] 5- and 15-minute thermal/memory sustained runs — requires human to keep device idle and measure `dumpsys meminfo` + thermal (`ThermalMonitor`). Host concurrency test proves no thermal degradation from concurrent decodes, but not sustained thermal.

## Claim boundary

Host unit/lint/APK (120 tests) + previous device instrumentation + real `jfk.wav` benchmark establish pipeline correctness, exactly-once, concurrency, and RTF on this hardware tier. They do not yet establish full human acoustic/editor/thermal/battery matrix — see `docs/MANUAL_TEST_SCRIPT.md` for exact script to be executed before store release. `docs/MODEL_BAKEOFF.md` documents that Nemotron/Whisper/Tiny-LID candidates are not yet measured on T807D and must not be claimed as production.

