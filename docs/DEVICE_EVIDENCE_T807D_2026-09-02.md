# Device Evidence — T807D 2026-09-02

## Device
- Model: T807D
- Android: 16 (SDK 36)
- Fingerprint: TCL/T807D_EEA/Frida:16/BP2A.250605.031.A3/AMD9:user/release-keys
- Build: BP2A.250605.031.A3
- RAM: 7.6 GB (7684188 kB MemTotal)
- Thermal at start: CPU 37.0°C, SKIN 33.8°C, BATTERY 33.0°C (Status 0)
- Page size: 4096 (4K, not 16K — 16KB emulator still required for 16KB gate)

## App
- Package: com.sprich.app.debug (debug), com.sprich.app (release)
- Version: 1.0.0 (67 debug, 71 release) — explicit version via SPRICH_VERSION_CODE not yet set for Play (local fallback)
- Signing: Debug CN=Android Debug (SHA256 3af50cab...), Release now debug-signed for device smoke (real upload key BLOCKED:HUMAN)
- Sherpa AAR: 47M, SHA256 0012d9a28f15bd6fb966b62b70a75da3990512fdccce28b83098248ce4be1698 (1.13.6)
- Models SHA256:
  - tiny-encoder.int8.onnx 12M d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434
  - tiny-decoder.int8.onnx 86M d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925
  - model.int8.onnx 126M 85451f03e004d607f86f4d6291ca55a8ca984d7e32dedc828cebe7fe61447511
  - canary-encoder 127M (in /data/local/tmp), canary-decoder 71M (when installed)
- WAV fixtures:
  - en-english.wav 31K bdf1efef...
  - de-german.wav 199K acc1c8c6...
  - es-spanish.wav 77K
  - fr-french.wav 112K
  - jfk.wav 352K (assets)

## Clean Native Diagnostic Matrix

### A. Tiny LID only — LidDeviceTest (7 tests) PASS
- Command: `adb shell am instrument -w -e class com.sprich.app.LidDeviceTest com.sprich.app.debug.test/androidx.test.runner.AndroidJUnitRunner`
- Result: OK 7 tests (20.5s)
- Logs:
  - load Success, ready true
  - per-utterance no-hard-cache: EN1 EN (267ms) → DE (299ms) → EN2 EN (256ms) — no cache, correct
  - alternating 8: EN 267ms, DE 488ms, EN 254ms, DE 473ms, ES 306ms, FR 353ms, DE 476ms, EN 255ms — confusion {en:{en=3}, de:{de=3}, es:{es=1}, fr:{fr=1}} correct 8/8
  - whisper 0.05 scale: EN 256ms, DE 296ms — still correct language, not flipped
  - noise: Unsupported nn 263ms — not fabricated
  - 20 repeated EN: all EN, no leak
  - jfk 3s slice: EN 345ms
  - rapid DE→EN 293→248ms, EN→DE 257→285ms, short 0.8s EN 245ms DE 246ms, mid 1.5s EN 250ms DE 287ms, long 6s EN 456ms — all correct
  - early durations: 0.5s EN 245ms correct, DE unsupported id (239ms) — 1.0s both correct, 1.5s DE mis as EN (271ms) — 2.0s both correct, 3.0s both correct
- Exit-info: reason USER REQUESTED (finished inst), pss 0, rss 0 — no crash, no tombstone
- Thermal after: CPU 44.3°C, SKIN 36.4°C
- App SHA: debug SHA256 3af50cab...

### B. FastConformer only — FastConformerDeviceTest (3 tests) PASS
- Result: OK 3 tests (7.9s)
- Logs:
  - load Success
  - jfk ms=422 rtf=0.038 textLen=107 "And so, my fellow Americans, ask not what your country can do for you, ask what "
  - warm 0 393ms, 1 383ms, 2 378ms
  - multilingual: en "I love you" (15882), de 90 chars, es 37 chars, fr 54 chars — all non-blank
  - meminfo not logged in this run, but FastConformerMemory test below gives PSS

### C. Automatic sequential, no Canary — AutomaticWithoutCanaryDeviceTest#automaticWorksEndToEndWithCanaryAbsent PASS
- Pre: delete canary, verify !isCanaryReady, ensure lid+fast ready (13M/90M+marker, 126M)
- After: lidReady true, fastReady true, autoReady true, canary absent
- Coordinator transcribe EN (15882) → textLen 10, engine fast, LID Known EN; DE (101982) → 90 chars, fast, LID Known DE; ES, FR similar — all via Fast, no Canary load
- Exactly-once isolation: two same PCM → same text, independent
- Exit: OK 1 test (4.77s)

### D. Repeat C after process restart — same test re-run after force-stop PASS
- Ran same test again after `adb shell am force-stop` — still OK 1 test (re-proved no hard cache, no leakage)

### E. Canary load/decode/unload → Automatic — AutomaticWithoutCanary#accurateExplicitStillWorksWhenFastAbsentAllowed PASS (after ensure canary install)
- Ensure canary from /data/local/tmp/canary-encoder etc., setReady, load Success, transcribe DE via Canary → non-blank, engine canary, then unload — PASS (3.5s)

### F. Production IME Automatic, Canary absent — ImeDeviceValidationTest (5 tests) PASS
- diagnosticsOnDeviceAreObservable PASS (no transcript, no raw audio)
- fieldSessionControllerPreventsCrossInsertOnDevice PASS
- sprichImeIsEnabledAndDefault: enabled [gms, tts, com.sprich.app.debug], default com.sprich.app.debug/com.sprich.app.input.ime.SprichIME PASS
- passwordFieldDetectionOnDevice PASS
- compositionOnDeviceReplacesPartialAndCommitsOnce PASS

## Continuous Winner Dictation — ContinuousWinnerDictationTest (1 test) PASS (after fix to use real fixtures, no JFK fallback)
- 10 EN via en-english.wav 1-sec repeated, 10 DE via de-german.wav 2-sec windows
- EN 10: each LID Detected EN, Fast non-blank, no failure
- DE 10: each LID Detected DE, Fast non-blank
- Total 20, lidFailures 0, blankFailures 0, order preserved — PASS (8.9s)

## Editor Matrix Real — EditorMatrixRealTest (7 tests) PASS (after fix createComposeRule)
- editTextHostileEditorTwoConsecutive PASS
- editTextPunctuationAttach PASS
- editTextSelectionStaleField PASS
- composeTextFieldTwoConsecutive (via EditText IC) PASS
- compositionImeLocalNoHelloHelloOnEditText PASS
- fieldSwitchDuringFinalStaleDrops PASS
- deleteWordAndUndoViaInputConnection PASS

## Pipeline Correctness — PipelineCorrectnessDeviceTest (12 tests) PASS

## FastConformer Memory/Product — FastConformerMemoryProductTest (1 test) PASS
- Log: Phase 8 PSS/RSS/thermal/WER — Primitive buffer verified, 126M vs Canary 198M, RTF 0.039
- Thermal: SKIN 36.38°C
- Memory: need to capture pssTotal from log — fastConformerPssRssThermalAndAccuracy finished

## Benchmark — BenchmarkOnDeviceTest (2 tests) PASS
- languageTaskInvariantsOnDevice PASS (auto bcp47, transcribe)
- benchmarkCanaryOnDevice: loadMs 1825, success true, engine canary-180m-flash-int8, jfk 176000 samples 11s, cold 1619ms rtf 0.147, warm p50 1533 p95 1551 avgRtf 0.139 peakRss 5MB, backend cpu threads 2, wrote diagnostics/benchmark_ondevice.log

