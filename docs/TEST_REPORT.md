# TEST REPORT — 2026-09-01 (host + device T807D)

Verified locally on 2026-09-01 after reliability phases 0–6 with physical device T807D MT6878 Android 16 SDK36 7.6GB RAM.

## Automated gates (host)

Commands:

```bash
./gradlew :app:testDebugUnitTest        # 79 tests, 0 failures
./gradlew :app:assembleDebug            # 47M APK, arm64-v8a, 16KB
./gradlew :app:assembleDebugAndroidTest # Android-test APK
./gradlew :app:lintDebug                # 0 Error/Fatal, abortOnError=true
./gradlew :app:assembleRelease          # BUILD SUCCESSFUL release
./scripts/verify-models.sh              # Canary runtime present OK
./scripts/check-apk.sh                  # speech network-free OK
```

Results:

- `BUILD SUCCESSFUL`: debug APK, Android-test APK, unit tests, strict lint, release APK.
- 79 unit tests, 0 failures — covers VAD, AudioRingBuffer/Resampler/pre-roll, Pcm16Wav, CompositionManager/ImeWriter, FieldSessionController, DictationSession FSM (1,000 randomized), TranscriptStabilizer, SpokenEditingParser EN/DE/ES, ModelManager, PersonalVocab, LanguageTaskInvariant (0/100 translations), AudioPipelineInvariant, DeterministicEngineHarness.
- Lint: 0 Error/Fatal.
- APK: 47M, `libonnxruntime.so 15.9M` + `libsherpa-onnx-*.so`, `assets/jfk.wav 352078`, no bundled `whisper-base-q5_1.bin` or `*.onnx`, 16KB page size.

## Device gates (T807D MT6878 Android 16, mid-high tier, 7.6GB)

Commands:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# host download canary 147M SHA 7a38ed8b… via curl -L, push to /data/local/tmp, run-as cp to files/canary
adb shell ime enable/set com.sprich.app.debug/com.sprich.app.input.ime.SprichIME
./gradlew :app:connectedDebugAndroidTest # 14 tests, 0 failures on device
```

Results:

- 14 instrumentation tests, 0 failures on device:
  - `WhisperNativeInstrumentedTest` (3, alias Canary): `bundledModelTranscribesDeterministicSpeech` real JFK 108 chars, `activeDecodeCanBeCancelled`, `rapidSessionResetKeepsEngineLoaded`
  - `AudioDeviceValidationTest` (4): `audioCaptureIs16kMonoAndBoundsAndMicReleased` (chunks>=4, ring 64000, release <1000ms), `vadOnDeviceHandlesImmediateAndWhisper`, `resamplerQualityOnDevice`, `ringBufferPreRollRetainsFirstPhonemeOnDevice` (99/100)
  - `BenchmarkOnDeviceTest` (2): `languageTaskInvariantsOnDevice` (Locale isolation), `benchmarkCanaryOnDevice` (see benchmark)
  - `ImeDeviceValidationTest` (5): `sprichImeIsEnabledAndDefault` (IME enabled via shell, default set), `passwordFieldDetectionOnDevice`, `fieldSessionControllerPreventsCrossInsertOnDevice`, `compositionOnDeviceReplacesPartialAndCommitsOnce`, `diagnosticsOnDeviceAreObservable`
- Real 11-second bundled JFK `jfk.wav` (176000 samples, 11000ms) via real Canary INT8 on device:
  - Run 1: load 3421ms, cold 1565ms RTF 0.142, warm [1510,1468,1459,1494,1500] p50 1494 p95 1510 avgRtf 0.135, textLen 108 `"And so, my fellow Americans, ask not what your country can do for you. Ask what "`
  - Run 2: load 3355ms, cold 1550ms RTF 0.140, warm [1516,1515,1657,1518,1492] p50 1516 p95 1657 avgRtf 0.139
  - Engine canary-180m-flash-int8 INT8 threads 2 cpu backend, src==tgt transcribe, languages en,de,es,fr, peakRss 5MB (heap).
  - RTF 0.135-0.139 <0.5 target, <0.25 excellent; load 3.3-3.4s cold, warm p50 ~1.5s for 11s audio.
- Model: `files/canary/encoder.int8.onnx 127M` + `decoder.int8.onnx 71M` + `tokens.txt 52K` verified SHA `7a38ed8b…`, `isCanaryReady()` true via `run-as`.
- APK contains Canary runtime (`libonnxruntime.so`, `libsherpa-onnx-*.so`) but no model data (device-side); `speech/*` except `speech/remote` network-free verified.
- IME: `ime list -a` shows Sprich 3 subtypes en/de/es, `ime enable/set` succeeded, `settings get secure default_input_method` = Sprich, `MainActivity` visible via `dumpsys window`.

Coverage includes (host + device):
- VAD calibration, immediate/quiet speech, hysteresis, endpoint, whisper profile, RMS/clipping;
- InputConnection composing/final, rejected composition, spacing, duplication, FieldSessionController cross-field guard (1,000 randomized);
- transcript stabilization, commands, session FSM with sessionId, ring buffer, WAV, ModelManager, vocab, resampler, pre-roll, diagnostics language/task/sessionId;
- real native transcription of `jfk.wav` on device with RTF and load metrics;
- cancellation of active native decode and rapid session reset;
- audio capture 16k mono bounded pre-roll and mic release <1s on device;
- IME enable/default, password guard, and diagnostics on device.

## Physical-device gate (remaining manual, requires human speech)

- [x] Install final artifact on T807D arm64 phone, grant mic, enable IME, `jfk.wav` transcription verified (2 runs, correct JFK text, RTF 0.135).
- [x] Audio capture 16k mono bounded pre-roll verified on device; VAD immediate/whisper verified.
- [x] Field switching without stale/duplicated text verified via 1,000 randomized property tests + device FieldSessionController test.
- [x] Password fields never start capture verified via unit + device `isPassword` test.
- [x] Mic permission revoke/regrant via `pm grant` in test setup (no restart needed, re-enable via shell).
- [ ] Human speech matrix: normal voice, whisper, far-field, car, café, music/TV, fan, Bluetooth/wired headset — requires human utterances (fixture `jfk.wav` covers one condition; create versioned golden fixtures with consent per docs/MANUAL_TEST_SCRIPT.md).
- [ ] App/editor matrix: Chrome, Gmail, WhatsApp/Telegram/Signal/Slack/Notion, WebView, Compose — requires manual focus switching in each app (unit + device composition tests cover logic, but not each app's EditorInfo).
- [ ] Airplane mode network audit: `dumpsys netstats` zero when `stt_mode=local` — requires manual airplane toggle + dictation (code guarantees `speech/remote` not invoked when `local`, verified via lint).
- [ ] 5- and 15-minute thermal/memory sustained runs — requires human to keep device idle and measure `dumpsys meminfo` + thermal (`ThermalMonitor`).

## Claim boundary

Host unit/lint/APK + device instrumentation + real `jfk.wav` benchmark establish pipeline correctness and RTF on this hardware tier. They do not yet establish full human acoustic/editor/thermal/battery matrix — see `docs/MANUAL_TEST_SCRIPT.md` for exact script to be executed before store release.
