# Sprich — Release report (plan §§2,12; 2026-09-06)

**Status: NOT RELEASABLE.** S01–S06 host-tested (prior pass); P01–P05/P07 + U01/U03/U04-U05 + P04 admission implemented in code with host-fixture claims carried from implementers (not re-verified here); every device, provider, benchmark, and packaging gate is BLOCKED or NOT MEASURED. Do not call this release-ready; do not claim Wispr parity.

## Source identity

- Branch `agent/sprich-integrity-sprint`, HEAD `507a461` (`507a461 P02: thread per-utterance VAD speech flag into AcousticBasis + adversarial host tests`; parent chain `71c3e6c` docs, `78768f4` S01–S06). Audit baseline was `20be03c`; HEAD is ahead of it.
- Uncommitted diff at time of writing: 11 modified tracked files (`VoiceGestureBar.kt`, `TranscriptionCoordinator.kt`, `DeadlinePolicy.kt`, `GeminiSttProvider.kt`, `LiveRemoteUtterance.kt`, `MetaMuseSttProvider.kt`, `MetaRealtimeSession.kt`, `OpenAiCompatibleSttProvider.kt`, `RemoteProviderFactory.kt`, `HomeScreen.kt`, `OnboardingScreen.kt`) + 8 new production files (`core/audio/StartupAudioBuffer.kt`, `core/vad/VadRoutePolicy.kt`, `core/audio/SegmentReconciler.kt`, `core/perf/RecognitionBudget.kt`, `diagnostics/UtteranceTelemetry.kt`, `input/lifecycle/UtteranceQueue.kt`, `storage/RecoveryDraftStore.kt`, `ui/theme/UiTokens.kt`) + 2 new test files (`P03P07QueueDeadlineTelemetryTest.kt`, `speech/RemoteAdmissionContractTest.kt`) + this docs pass (3 files under `docs/implementation/` only).
- `versionCode` derives from git count, `versionName` defaults `1.0.0` (`app/build.gradle.kts`); no version bump in this work. Confirm with: `git -C /Users/schober/Projects/Sprich log --oneline -3` and `git -C /Users/schober/Projects/Sprich status --short`.

## Tested coverage

- Host regression Gate A (S01–S06): **100/100 pass** via kotlinc-direct harness in a prior pass (`/tmp/sprich-harness/resume-verify.log`, `docs/implementation/benchmark-results.json`; method in `SPRICH_BENCHMARK_PROTOCOL.md:5`). Carried, not re-run by this documenter pass (ENV TRUTH: no Gradle/network; documenter ran nothing).
- New-slice fixtures (carried implementer claims, not re-verified here, no new numbers invented): `speech/RemoteAdmissionContractTest.kt` (5 tests, plain JUnit4 — P04 admission/overflow `LiveRemoteUtterance.kt:109` → `ApiFailure.OutputLimit`, handshake fit `MetaRealtimeSession.kt:25`, live-contract guards `OpenAiCompatibleSttProvider.kt:44,96-99`, `GeminiSttProvider.kt:35,40-42`); `P03P07QueueDeadlineTelemetryTest.kt` (P03 queue/deadline + P07 telemetry incl. no-transcript-in-telemetry assertion).
- NOT run here: `./gradlew :app:testDebugUnitTest`, `:app:lintRelease`, `:app:assembleRelease`, `:app:bundleRelease`, `writeReleaseDependencyInventory`, `verify-locales.py`, `verify-models.sh`, `check-apk.sh` (Gradle BLOCKED); all `connectedAndroidTest`/device tests incl. updated `PipelineCorrectnessDeviceTest` (adb BLOCKED); red-team/security, Perfetto/perf, TalkBack/screenshot, provider live acceptance, §10 corpus/benchmark (all BLOCKED/NOT MEASURED).

## Unresolved issues / risks

- P06 untouched: language/engine coupling, LID optimization, Nemotron/Parakeet experiments (§7) not started; no model promoted.
- U02 correction sheet + U04/U05 17-row copy/table tokens: baseline-exists only; owned-span Apply/Cancel, alternatives-only-if-real, Remember-spelling scoping, route labels (`On-device`/`Audio online`/`Text online`), coalesced lane, accessibility walkthrough all unverified on device.
- `RecoveryDraftStore.kt` is new in the uncommitted diff; its §6.5 one-draft/16384-char/2-min/in-memory/no-auto-insert contract was not re-read by this pass — verify content before citing as done.
- `AcousticBasis.UNKNOWN` conservative default retained at the production call site; neural-VAD acoustic proof (Gate B first-word/PCM trace, endpoint sweeps) still open.
- `RefinementValidator` is a deterministic guard, not a semantic-equivalence proof; conservative-rejection cost unmeasured (`ValidationStats` instrumented, no data).
- Legacy `parse()`/`isDeleteCommand` shims retained; future pass should migrate callers to `parseTyped` and delete them.
- Overlapping-utterance `lastRemoteFailure` kept as legacy global for Settings reads (`TranscriptionCoordinator.kt` per implementer); per-call `AttemptBox` is the overlapping path — confirm no caller still branches on the global for correctness.
- Publisher decisions open: FastConformer/NGC rights review, F-Droid vs direct-download/Play channel, cloud-provider recommendation — none touched.

## Provenance (models, native, dependencies)

- No new model, native library, or dependency pin in this diff (no `native/`, model, or `gradle/libs` change listed in `git status --short`).
- Dependency inventory, license notices, APK content verification NOT regenerated (Gradle BLOCKED). Required on a capable machine: `./gradlew :app:writeReleaseDependencyInventory`, `python3 scripts/verify-locales.py`, `./scripts/verify-models.sh --inventory app/build/reports/release-runtime-dependencies.tsv`, `./scripts/check-apk.sh <apk>` — see `DEVICE_RUNBOOK.md`.
- No SHA-256, no certificate fingerprint, no model/runtime revisions to record: nothing was built from this source here. Existing APKs under `app/build/outputs/` (2026-09-03) predate S01–S06 and the new slices and MUST NOT be presented as containing this work. Do not relabel model weights Apache-2.0 because the runtime is Apache-licensed (plan §12); human publisher FastConformer/NGC rights review remains explicit and open.
- Release signing policy lives in `release/SIGNING.md` (untouched by this pass; signed-off files under `release/` were not modified).

## Install / rollback (no installable artifact from this environment)

- Nothing to install from here: no signed APK/AAB was produced; the release APK present is unsigned. Never present a QA key as the publisher key; a different signature cannot update an existing install.
- On a machine with working Gradle + Android SDK + keystore (see `DEVICE_RUNBOOK.md` for exact S23 commands): build → sign per `release/SIGNING.md` → record versionCode/versionName, commit, APK SHA-256 (`sha256sum`), certificate fingerprint (`apksigner verify --print-certs` / `keytool -list`) here → `adb install` on the S23 → verify update path keeps data; rollback = `adb uninstall <package>` then reinstall the prior signed artifact (signatures must match for update; otherwise uninstall is required — state which path was tested).
- Rollback expectations and channel gates (direct-download/Play/F-Droid) are publisher decisions and remain untested; record the tested channel here once executed.
