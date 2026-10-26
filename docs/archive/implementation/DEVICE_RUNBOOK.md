# Sprich — Device runbook (S23 target, plan §12 blocked gates)

Scope: exact commands to run on a machine with working Gradle + Android SDK + adb and a Samsung S23 (ARM64 flagship) on the final source+APK.
2026-09-06: fully executed on SM-S918B (Android 16) — see `docs/DEVICE_EVIDENCE_S23_2026-09-06.md`.
Define `serial=R3CW404GVBL` (S23) and pin every command with `-s <serial>` — bare `adb -d` fails when a second device is attached.

## Prerequisites (capable machine only)

- JDK 17, Android SDK (platform-tools + compile/target SDK per `app/build.gradle.kts`), charged S23 with USB debugging + authorized host key, USB cable (no USB-powered thermal claims — see Benchmark).
- Repo at the release commit: `git -C <repo> status --short` clean, `git -C <repo> log --oneline -3` recorded in the Release report.
- Provider credentials (if running live-provider gates) exported in env, never committed: e.g. `export SPRICH_TEST_KEY=...` (disposable key, non-sensitive recordings only).

## 0. Sanity

```bash
java -version
adb version
adb devices -l
git -C <repo> status --short
git -C <repo> rev-parse --short HEAD
```

Expect: S23 listed as `device` (not `unauthorized`/`offline`).

## 1. Build (same source that ships)

```bash
cd <repo>
./gradlew :app:testDebugUnitTest :app:lintRelease --stacktrace
./gradlew :app:assembleRelease :app:bundleRelease :app:writeReleaseDependencyInventory --stacktrace
python3 scripts/verify-locales.py
./scripts/verify-models.sh --inventory app/build/reports/release-runtime-dependencies.tsv
./scripts/check-apk.sh app/build/outputs/apk/release/app-release-unsigned.apk
sha256sum app/build/outputs/apk/release/app-release-unsigned.apk
```

Sign per `release/SIGNING.md` (QA key labelled as QA; never commit the keystore), then record versionCode/versionName, commit, SHA-256, certificate fingerprint (`apksigner verify --print-certs --verbose <signed.apk>`) in `SPRICH_RELEASE_REPORT.md`.

## 2. Install (S23)

```bash
adb -s <serial> install -r <signed-release.apk>
adb -s <serial> shell pm list packages | grep -i sprich
adb -s <serial> shell dumpsys package <package> | grep -A2 version
```

Update path: reinstalling with the same signature must preserve data; a signature change requires `adb -s <serial> uninstall <package>` first (record which path was tested). Rollback: uninstall, reinstall the prior signed artifact.

## 2b. Unsigned internal loop (debug IME on S23 — validated 2026-09-06)

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Fresh IME per run (kills wedged focus/session state):
adb -s <serial> shell am force-stop com.sprich.app.debug
adb -s <serial> shell ime set com.sprich.app.debug/com.sprich.app.input.ime.SprichIME
adb -s <serial> shell am start -n com.sprich.app.debug/com.sprich.app.ui.MainActivity
adb -s <serial> shell logcat -c
# Focus field via dump coordinates (never eyeball): uiautomator dump → EditText center → input tap → confirm:
adb -s <serial> logcat -d | grep -c "dictation started"   # expect 1
```

Rules learned the hard way: same-field re-taps are Android no-ops (no new
onStartInput) — dumps give truth, screenshots deceive. Bar toggle zone is
≈y2050–2550 display px (aim y2300); every toggle must log
`toggle running=… sessionState=…` — confirm in logcat, never by screenshot
alone. Force-stop also resets the demo screen (setup screen shows when the
IME isn't selected — `ime set` again, no reinstall).

Acoustic dictation (no on-device loopback — shell cannot open
`/dev/snd/pcmC0D0p`; `tinyplay` never emits): play from the Mac with
`afplay -v 1.0 <wav>` at system volume ≈60. Dense-speech fixture recipe for
cap tests: loop `app/src/debug/assets/jfk.wav` with 100 ms crossfades, peak
normalize, squeeze sub-threshold regions to 120 ms pads (see session notes).
Key log greps: `toggle`, `dictation started`, `utterance onset`,
`endpoint detected` (frozenSamples), `split continuation`, `abandoned`,
`finalized source`, `COMMIT_RESULT`, `non-UI thread` (must stay zero).

Abandon E2E: play ~15 s dense speech, press HOME (`input keyevent 3`) right
at playback end so decode completes post-stop → `abandoned … stale
generation` + recovery draft. Settings matrix (night/font/rotation/rtl):
snapshot first (`uimode night`, `font_scale`, `accelerometer_rotation`,
`debug.force_rtl`), restore all after — `force_rtl` may not mirror; use
static start/end-attribute analysis plus a real RTL locale for verdicts.

## 3. Device tests (Gates C/E/F)

WARNING: `:app:connectedDebugAndroidTest` UNINSTALLS the app (marker-proven:
`/data/data` wiped, provisioned models + receipts gone). Use it only to
compile the test APK. Run tests via direct `am instrument` — no Gradle
lifecycle between provisioning and test:

```bash
./gradlew :app:assembleDebugAndroidTest
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# provision (production DownloadManager flow; FreshInstall covers canary+lid+fast)
adb -s <serial> shell am instrument -w -e class com.sprich.app.FreshInstallDeviceTest com.sprich.app.debug.test/androidx.test.runner.AndroidJUnitRunner
# battery (non-deleting classes; comma-separated preserves one process)
adb -s <serial> shell am instrument -w -e class 'com.sprich.app.PipelineCorrectnessDeviceTest,...' com.sprich.app.debug.test/androidx.test.runner.AndroidJUnitRunner
# canary-deleting gates LAST (AutomaticWithoutCanary restores canary in teardown)
```

Require: zero unauthorized/cross-field/duplicate mutations in the adversarial suite; ordered at-most-once commits; S06 updated expectations pass on device.

## 4. Screenshots + accessibility walkthrough (§9.8)

```bash
adb -s <serial> exec-out screencap -p > screenshot-home-light.png
adb -s <serial> exec-out screencap -p > screenshot-bar-listening-dark.png
adb -s <serial> exec-out uiautomator dump /sdcard/ui.xml
adb -s <serial> pull /sdcard/ui.xml ./ui-home.xml
```

Capture matrix: light/dark × 320/360/411 dp widths, landscape, 200% font, one RTL layout; states: missing-setup, preparing, listening, preview, finishing, added, failed, paused, password-field, recovery "Not inserted". TalkBack order/roles/state/focus + reduced-motion (animator scale 0) verified by hand on the S23; an unrendered diff is not a pass.

## 5. Benchmark (§10/E01, same final build)

```bash
adb -s <serial> shell am instrument -w -e class com.sprich.app.BenchmarkInstrumentedTest <package>.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> pull /sdcard/Android/data/<package>/files/benchmark-results.json ./s23-benchmark-results.json
```

Corpus: ≥1,000 held-out utterances EN/DE/ES/FR, ≥20 speakers, code-switching; same phones/acoustic setup/network, randomized order for Wispr comparison; report p50/p95 post-speech wait + quality tolerance + critical-error gates with confidence intervals per language/device. Failures stay in the denominator. Record device, OS, app version, build hash, clocks, temperature, charging state in `SPRICH_BENCHMARK_PROTOCOL.md`.

## 6. Log pull (failure triage)

```bash
adb -s <serial> logcat -d -v threadtime '*:W' > logcat-warn.txt
adb -s <serial> logcat -d -v threadtime --pid=$(adb -s <serial> shell pidof <package>) > logcat-sprich.txt
adb -s <serial> bugreport ./s23-bugreport.zip
```

Scrub transcripts/secrets/endpoints before attaching; check no sensitive text/audio in logs, crash reports, tmp WAVs, clipboard, or screenshots (§11 red-team).
