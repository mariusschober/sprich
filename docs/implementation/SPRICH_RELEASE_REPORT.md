# Sprich — Release report (integrity sprint, 2026-09-06)

**Status: NOT RELEASABLE.** S01–S06 implemented and host-tested; every device, provider, benchmark, and packaging gate is BLOCKED or NOT MEASURED. Do not call this release-ready.

## Source identity

- Branch `agent/sprich-integrity-sprint`, HEAD `20be03c` (== audit baseline; this work is the uncommitted diff + the commit that carries it).
- Diff: 10 modified files, 7 new (3 production, 4 test), 5 docs + 1 JSON (this directory).
- `versionCode` derives from git count, `versionName` defaults `1.0.0` (`app/build.gradle.kts`); no version bump in this work.

## Tested coverage

- Host regression (Gate A): **100/100 pass** via kotlinc-direct harness (`/tmp/sprich-harness/resume-verify.log`, `benchmark-results.json`). Covers S01–S06 production paths including the `SprichIME` finalizer policy.
- Device mutation tests: BLOCKED, never executed (S06 expectation updates in `PipelineCorrectnessDeviceTest` are unrun).
- Native/model/download/provider paths: untouched and untested by this work.

## Blocked gates (observed 2026-09-06, this environment)

- `./gradlew` (any task, `--offline`, with and without daemon): fails before configuration — `Could not create service of type FileLockContentionHandler … SocketException: Operation not permitted`. Gradle cannot run in this sandbox, so `:app:testDebugUnitTest`, `:app:lintRelease`, `:app:assembleRelease`, `:app:bundleRelease`, dependency inventory, locale/model/APK verification scripts were all unexecutable.
- `adb`: daemon will not start (`ADB server didn't ACK`, twice). No device, no emulator, no screenshots, no TalkBack, no timing traces.
- Providers/corpus: no credentials, no recordings, no hardware — Gates D/F and all of §10 NOT MEASURED.

## Artifacts — what exists and what does not

- Existing APKs (`app/build/outputs/apk/{debug,release}/`, 2026-09-03) predate S01–S06 and MUST NOT be presented as containing this work. The release APK is unsigned, so even current builds would not be installable deliverables without a signing identity (see `release/SIGNING.md`).
- No SHA-256, no certificate fingerprint, no model/runtime provenance to record: nothing was built from this source.
- Unblock path: on a machine with a working Gradle daemon + Android SDK + keystore, run the §3 gate block (`testDebugUnitTest`, `lintRelease`, `assembleRelease`, `bundleRelease`, `writeReleaseDependencyInventory`, `verify-locales.py`, `verify-models.sh`, `check-apk.sh`), then sign per `release/SIGNING.md` and record hashes here.

## Unresolved issues / risks

- `AcousticBasis.UNKNOWN` at the production call site is a conservative default, not VAD integration (P02 open).
- `RefinementValidator` is a deterministic guard, not a semantic-equivalence proof; conservative-rejection cost unmeasured (`ValidationStats` is the instrument, no data yet).
- Legacy `parse()`/`isDeleteCommand` shims retained for existing callers; a future pass should migrate callers to `parseTyped` and delete them.
- Publisher decisions open: FastConformer/NGC rights review, F-Droid vs direct-download/Play channel, cloud-provider recommendation — none touched.
