# TEST REPORT

Verified locally on 2026-08-24 after the reliability refactor. Physical-device items remain unchecked until observed.

## Automated gates

Commands:

```bash
./scripts/apply-whisper-patches.sh
./gradlew --no-daemon --max-workers=2 \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest \
  :app:lintDebug

./gradlew --no-daemon --max-workers=1 :app:connectedDebugAndroidTest
./scripts/verify-models.sh
./scripts/check-apk.sh
```

Results:

- `BUILD SUCCESSFUL`: debug APK, Android-test APK, unit tests, and strict debug lint.
- 40 unit tests, 0 failures, 0 errors.
- Lint: 0 Error/Fatal findings with `abortOnError = true`.
- Android 36 arm64 software emulator: 3 instrumentation tests, 0 failures.
- Real 10-second bundled speech: 2 Whisper segments, 100 output characters. Software-emulated inference took 68,698 ms; this is correctness evidence, not a performance claim.
- Active native inference cancellation: 505 ms in the same final run (previously 63,353 ms before the scheduler callback fix).
- Bundled model: exact 59,707,625 bytes and SHA-256 `422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898`.
- APK contains no Canary/Nemotron model or runtime, no HTTP/archive dependency, and requests no Internet permission.
- `zipalign -c -P 16 -v 4`: successful. All `libsprich_whisper.so` ELF `LOAD` segments report `0x4000` alignment.

Final debug APK before handoff copy:

- Size: 111,844,386 bytes.
- SHA-256: `e722b2379a4c09c290480967951e59c579e51f509fa6e9bfcde941c1f3a131c8`.

Coverage includes:

- VAD calibration, immediate/quiet speech, endpoint, reset, and empty frames;
- InputConnection composing/final behavior, rejected composition, spacing, and duplication prevention;
- transcript stabilization, commands, session FSM, ring buffer, WAV parsing, and model manager;
- real native transcription of bundled deterministic speech;
- cancellation of an active native decode;
- rapid begin/cancel session cycling without unloading the process-wide engine.

## Physical-device gate

- [ ] Install the final artifact on a representative arm64 phone.
- [ ] Grant microphone, enable Sprich IME, and transcribe a normal sentence into at least three editor implementations.
- [ ] Verify immediate speech is not clipped and quiet speech triggers VAD.
- [ ] Repeat at least 20 utterances and switch fields/apps without stale or duplicated text.
- [ ] Verify password fields never start capture.
- [ ] Revoke/regrant microphone and recover without process restart.
- [ ] Repeat in airplane mode and confirm no network traffic.
- [ ] Run 5- and 15-minute thermal/memory tests.

## Claim boundary

Unit, lint, APK, and emulator-native success establish repository and pipeline correctness. They do not establish physical microphone, editor interoperability, latency, thermal stability, battery use, or store readiness.
