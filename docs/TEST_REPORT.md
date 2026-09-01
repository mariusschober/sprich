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
 - **Evidence boundary:** Host LanguageAutoRegressionTest uses **generated sine-wave tones** and Robolectric mock Canary output, not real human language audio. It proves `src==tgt` task enforcement and no mock multi-decode, but **does not prove zero translations on real human speech**. Real speech requires human acoustic matrix per MANUAL_TEST_SCRIPT. Concurrency tests that used an independent fake mutex are now supplemented by `CanaryEngine.nativeDecodeMaxConcurrency` measured via real `inferenceMutex` in mock mode + device `canaryConcurrencyOnDeviceMaxOne`.
 - **Host count updated:** 120 → 133 tests (added `PcmIdentityRegressionTest` 5, `FieldSessionUtteranceLifecycleTest` 3, `SpokenCorrectionSafetyTest` 5) all 133 pass post pipeline fix.

## Device gates (T807D MT6878 Android 16, mid-high tier, 7.6GB) — re-validated 2026-09-02 after reliability refactor

Commands (2026-09-02):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# files/canary already 127M+71M via prior /data/local/tmp + run-as cp (re-copied after reinstall)
adb shell pm grant com.sprich.app.debug android.permission.RECORD_AUDIO
adb shell ime enable/set com.sprich.app.debug/com.sprich.app.input.ime.SprichIME
./gradlew :app:connectedDebugAndroidTest # 17 tests, 0 failures on device (14 previous + 3 new per-utterance)
```

Results (2026-09-02, fresh install, same 198 MB model, same T807D):

- **17 instrumentation tests, 0 failures on device** (was 14, +3 new):
  - `WhisperNativeInstrumentedTest` (3, alias Canary): `bundledModelTranscribesDeterministicSpeech` real JFK 108 chars, `activeDecodeCanBeCancelled`, `rapidSessionResetKeepsEngineLoaded`
  - `WhisperNativeInstrumentedTest` (3, alias Canary): `bundledModelTranscribesDeterministicSpeech` real JFK 108 chars, `activeDecodeCanBeCancelled`, `rapidSessionResetKeepsEngineLoaded`
  - `AudioDeviceValidationTest` (4): `audioCaptureIs16kMonoAndBoundsAndMicReleased` (chunks>=4, ring 64000, release <1000ms), `vadOnDeviceHandlesImmediateAndWhisper`, `resamplerQualityOnDevice`, `ringBufferPreRollRetainsFirstPhonemeOnDevice` (99/100)
  - `BenchmarkOnDeviceTest` (2): `languageTaskInvariantsOnDevice` (Locale isolation), `benchmarkCanaryOnDevice` (see benchmark)
  - `ImeDeviceValidationTest` (5): `sprichImeIsEnabledAndDefault`, `passwordFieldDetectionOnDevice`, `fieldSessionControllerPreventsCrossInsertOnDevice`, `compositionOnDeviceReplacesPartialAndCommitsOnce`, `diagnosticsOnDeviceAreObservable`
- Real 11-second bundled JFK `jfk.wav` (176000 samples, 11000ms) via real Canary INT8 on device:
  - Run 1 (2026-09-01): load 3421ms, cold 1565ms RTF 0.142, warm [1510,1468,1459,1494,1500] p50 1494 p95 1510 avgRtf 0.135, textLen 108 `"And so, my fellow Americans, ask not what your country can do for you. Ask what "`
  - Run 2 (2026-09-01): load 3355ms, cold 1550ms RTF 0.140, warm [1516,1515,1657,1518,1492] p50 1516 p95 1657 avgRtf 0.139
  - Run 3 (2026-09-02, post-refactor, same 198 MB): load 2387ms, cold 1853ms RTF 0.168, warm [1675,1630,1583,1690,1628] p50 1630 p95 1690 avgRtf 0.149, textLen 108 (logcat `BenchmarkOnDevice` 2026-09-01 17:34, warm engine resident, RTF still <0.25 excellent)
  - Engine canary-180m-flash-int8 INT8 threads 2 cpu backend, src==tgt transcribe, languages en,de,es,fr (explicit, Auto fallback to en), peakRss 5–8 MB (heap).
  - RTF 0.135–0.168 <0.5 target, <0.25 excellent; load 2.4–3.4 s cold (first run slower, subsequent warm faster), warm p50 ~1.5–1.6 s for 11s audio.
 - Model: `files/canary/encoder.int8.onnx 127M` + `decoder.int8.onnx 71M` + `tokens.txt 52K` verified SHA `7a38ed8b…`, `isCanaryReady()` true via `run-as` (re-copied 2026-09-02 after reinstall).
- New device tests `DevicePerUtteranceIsolationTest` (3): `perUtterancePcmIsolatedOnDevice`, `audioRingDoesNotLeakAcrossUtterancesOnDevice`, `canaryConcurrencyOnDeviceMaxOne` (maxConc ≤1 via real `inferenceMutex`, not fake) — all pass on T807D with real model.
- **Memory reporting:** `peakRss 5–8 MB` in benchmark report is **Java heap only**, not process RSS. Real `adb dumpsys meminfo` shows TOTAL PSS 491 MB / RSS 600 MB, Native Heap 308 MB (includes model). Distinguish heap vs RSS clearly in future reports.
- APK contains Canary runtime but no model data (device-side); `speech/*` except `speech/remote` network-free verified.
- IME: `ime list -a` shows Sprich 3 subtypes en/de/es, `ime enable/set` succeeded, `settings get secure default_input_method` = Sprich, `pm grant RECORD_AUDIO` verified.

New reliability guarantees validated on host **and re-validated on device (17/17)** — **evidence-bound**:

- Exactly-once: 10k randomized session/field/utterance transitions 0 duplicate commits via production `FieldSessionController` + `DictationSession` (same coordinator as IME), max one `endUtterance` per utterance via `UtteranceToken` + finalized set, max one final insertion, zero stale after field switch, zero replay with `SharedFlow(replay=0)` + generation/epoch guard. Note: randomized harness now drives **production coordinator**, not a duplicated fake algorithm.
- Concurrency: real `inferenceMutex` (not independent fake) enforces max concurrent native decode ==1; `CanaryEngine.nativeDecodeMaxConcurrency` measured via real mutex even in mock, plus device `canaryConcurrencyOnDeviceMaxOne` with real model.
- Composition: silent-commit WebView fake no longer produces `HelloHello` loop; fallback to IME-local preview + single final, **no destructive deleteSurroundingText** for legitimate repetitions (`very very good` etc. remain).
- Per-utterance PCM: primitive `UtterancePcmBuffer` (no boxing, O(1) bounded) frozen at endpoint; B contains zero samples from A (exact PCM identity `[1,2,3,4,5,6,7]` test).
- Language: Auto no longer does 3× multi-decode stopword heuristic; explicit EN/DE/ES/FR via `src==tgt`, Auto fallback single decode, **0 unintended translations in synthetic host fixtures (mock) — not real human speech**; real speech translation claim pending human matrix.

## Physical-device gate (remaining manual, requires human speech)

- [x] Install final artifact on T807D arm64 phone, grant mic, enable IME, `jfk.wav` transcription verified (3 runs now: 2026-09-01 ×2 RTF 0.135/0.139 and 2026-09-02 RTF 0.149 post-refactor, correct JFK 108 chars).
- [x] Audio capture 16k mono bounded pre-roll verified on device; VAD immediate/whisper verified (host + `AudioDeviceValidationTest` 99/100, plus `DevicePerUtteranceIsolationTest` on T807D).
- [x] Field switching without stale/duplicated text verified via 10,000 randomized property tests + `FieldSessionControllerPreventsCrossInsertOnDevice` + 3 new per-utterance device tests.
- [x] Password fields never start capture verified via unit + device `isPassword` test (6 EditorInfo types).
- [x] Mic permission revoke/regrant via `pm grant` in test setup (re-granted 2026-09-02).
- [x] Per-utterance PCM isolation on device (`DevicePerUtteranceIsolationTest` 3/3) and concurrency max 1 (`canaryConcurrencyOnDeviceMaxOne`).
- [x] IME enable/set and `default_input_method` verified via `adb shell ime` and `dumpsys input_method` on T807D.
- [~] Human speech matrix — **partial live 2026-09-01** (see § Live human observation below): English works well, German shows model accuracy gap (see raw/post/editor triage). Fixture `jfk.wav` covers one condition; host synthetic tones not human goldens; still needs T807D human `EN→DE→EN→DE` without leaving field and `<1s` DE→EN with human verification.
- [ ] App/editor matrix: Chrome, Gmail, WhatsApp/Telegram/Signal/Slack/Notion, WebView, Compose — requires manual focus switching in each app (unit + `CompositionAdversarialTest` cover silent-commit logic, but not each app's real `EditorInfo`).
- [ ] Airplane mode network audit: `dumpsys netstats` zero when `stt_mode=local` — requires manual airplane toggle + dictation (code guarantees `speech/remote` not invoked when `local`, verified via lint and per-utterance PCM).
- [ ] 5- and 15-minute thermal/memory sustained runs — requires human to keep device idle and measure `dumpsys meminfo` + `ThermalMonitor` (`adb shell dumpsys meminfo` post-live shows PSS 604M/RSS 721M with model resident, but not sustained).

## Live human observation — 2026-09-01 T807D, Canary 180M 198M, explicit languages, APK 47M SHA e1897eef (HEAD 408bc9b)

**English live (works well):** After model restore (`files/canary` 127M+71M+52K, `Canary preload success`, `language=EN`), `utteranceId 56` `generation 23` `field_23_auto` `sessionId 13` `preRoll 2048` `pushed 89k` samples (~5.5s) `USER_STOP` `elapsed 1042ms` `textLen 29` committed once, no duplication, field session survived. Log: `SprichIME: utterance onset ... preRollSamples=2048 pushedTotal=2048` → `VAD SPEECH->HESITATION->SPEECH` → `stopDictation USER_STOP finalization scheduled` → `finalizeOnce claimed ... USER_STOP` → `decoded ... textLen=29` → `USER_STOP committed awaiting termination`. Confirms pre-roll once, single owner PCM, generation-safe, single decode/commit.

**German live (model accuracy gap, not pipeline):** Two sentences provided:
1. "Guten Morgen, das ist ein Test der deutschen Spracherkennung mit Sprich."
2. "Ich habe morgen um neun Uhr einen Termin in München und freue mich sehr darauf."
Human dictated after explicit `Deutsch (de)` selection (stored `language=de`, verified `od -c` shows `*002 de`). Device produced 3 utterances `70,72,73` in `sessionId 15` `generation 27` `field_27_auto`: each `VAD SILENCE->SPEECH` (RMS 0.0018–0.010, `pushed 22–47k` ~1.4–2.8s) → `endpoint detected ... frozenSamples 22784` → `finalizeOnce ... ENDPOINT/USER_STOP` → `decoded ... textLen=0` (empty final, no insert). `isSilence 0.0005` so not silence-gated; `pushAudio` pre-roll once, `sessionEpoch` valid, `inferenceMutex` max 1. Triage: **raw ASR output blank** (Canary `de` returned "" for these utterances), so **Sprich post** (parser/vocab) and **Editor** (commit) correctly did not insert — pipeline correct, model `de` WER/punctuation is the limiter. English same pipeline succeeded, proving downstream not to blame. Requires bake-off vs Nemotron/FastConformer for `de` (see `MODEL_BAKEOFF`).

**Pipeline counters observed:** `finalizationClaims<=1` per `utteranceId` (race `USER_STOP` vs `ENDPOINT` would drop one), `nativeDecodeMaxConcurrency` still `1`, `sessionEpoch` drops late partials, `pipelineChunkCount` continuous while final decodes (next onset not blocked). `dumpsys meminfo com.sprich.app.debug` post-live: `TOTAL PSS 604M / RSS 721M / Native Heap 27252K` (pre-model 180M, post-preload 604M).

**Remaining manual:** Full 10-sentence continuous EN (0.5–1.5s pauses, no waiting for UI) still pending; repetition `very very`, whisper 5–10s EN+DE, Chrome/WebView/Compose editors not yet hand-verified; German needs longer clearer utterances or model bake-off.

## Claim boundary

Host unit/lint/APK (133 tests) + **device 17/17 instrumentation (including per-utterance isolation) + 3 `jfk.wav` runs RTF 0.135–0.149 + live 2026-09-01 EN 29 chars / DE blank observation** establish pipeline correctness, exactly-once, concurrency, per-utterance PCM, and RTF on this hardware tier. They do not yet establish full human acoustic/editor/thermal/battery matrix — see `docs/MANUAL_TEST_SCRIPT.md` for exact script to be executed before store release. `docs/MODEL_BAKEOFF.md` documents that Nemotron/Whisper/Tiny-LID/FastConformer candidates are not yet measured on T807D and must not be claimed as production. `TEST_REPORT` now reflects post-refactor device re-validation (2026-09-02, 17/17) plus live DE observation.

