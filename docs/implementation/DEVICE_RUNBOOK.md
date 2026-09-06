# Sprich — Device runbook (S23 target, plan §12 blocked gates)

Scope: exact commands to run on a machine with working Gradle + Android SDK + adb and a Samsung S23 (ARM64 flagship) on the final source+APK. This file was written in an environment where Gradle/adb/network are unavailable, so every command below is UNRUN here — it is the unblock path, not evidence.

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
adb -d install -r <signed-release.apk>
adb -d shell pm list packages | grep -i sprich
adb -d shell dumpsys package <package> | grep -A2 version
```

Update path: reinstalling with the same signature must preserve data; a signature change requires `adb -d uninstall <package>` first (record which path was tested). Rollback: uninstall, reinstall the prior signed artifact.

## 3. Device tests (Gates C/E/F)

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.sprich.app.PipelineCorrectnessDeviceTest --stacktrace
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.sprich.app.FreshInstallDeviceTest --stacktrace
adb -d pull /sdcard/Android/data/<package>/files/test-results ./device-test-results/
```

Require: zero unauthorized/cross-field/duplicate mutations in the adversarial suite; ordered at-most-once commits; S06 updated expectations pass on device.

## 4. Screenshots + accessibility walkthrough (§9.8)

```bash
adb -d exec-out screencap -p > screenshot-home-light.png
adb -d exec-out screencap -p > screenshot-bar-listening-dark.png
adb -d exec-out uiautomator dump /sdcard/ui.xml
adb -d pull /sdcard/ui.xml ./ui-home.xml
```

Capture matrix: light/dark × 320/360/411 dp widths, landscape, 200% font, one RTL layout; states: missing-setup, preparing, listening, preview, finishing, added, failed, paused, password-field, recovery "Not inserted". TalkBack order/roles/state/focus + reduced-motion (animator scale 0) verified by hand on the S23; an unrendered diff is not a pass.

## 5. Benchmark (§10/E01, same final build)

```bash
adb -d shell am instrument -w -e class com.sprich.app.BenchmarkInstrumentedTest <package>.test/androidx.test.runner.AndroidJUnitRunner
adb -d pull /sdcard/Android/data/<package>/files/benchmark-results.json ./s23-benchmark-results.json
```

Corpus: ≥1,000 held-out utterances EN/DE/ES/FR, ≥20 speakers, code-switching; same phones/acoustic setup/network, randomized order for Wispr comparison; report p50/p95 post-speech wait + quality tolerance + critical-error gates with confidence intervals per language/device. Failures stay in the denominator. Record device, OS, app version, build hash, clocks, temperature, charging state in `SPRICH_BENCHMARK_PROTOCOL.md`.

## 6. Log pull (failure triage)

```bash
adb -d logcat -d -v threadtime '*:W' > logcat-warn.txt
adb -d logcat -d -v threadtime --pid=$(adb -d shell pidof <package>) > logcat-sprich.txt
adb -d bugreport ./s23-bugreport.zip
```

Scrub transcripts/secrets/endpoints before attaching; check no sensitive text/audio in logs, crash reports, tmp WAVs, clipboard, or screenshots (§11 red-team).
