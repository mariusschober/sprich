# Release candidate — 2026-10-25 (release-1.0-prep)

## Artifact

| Field | Value |
|---|---|
| Branch | `release-1.0-prep` |
| Source | `8f485ed` (T5 truth pass) |
| Version | `1.0.0-rc19-audit` (95) |
| APK | 29,422,191 bytes |
| SHA-256 | `acd495f07edb4d51bbe73b8e57e7207cd5330c755ab05f690f8e316a4a39803a` |
| Signing | local Android **debug/QA** certificate `C=US, O=Android, CN=Android Debug`, SHA-256 `3af50cab6e0515f478413e79786958671913b2cae67037defe537bb1e7465069`. **Not a distribution key; nothing was published or signed with a production key.** |

## Host verification (MEASURED)

- `:app:testDebugUnitTest` — 594 tests, 0 failures/errors/skips.
- `:app:lintRelease` — 0 errors (warnings do not abort).
- `:app:assembleRelease` + `:app:writeReleaseDependencyInventory` — green.
- `scripts/verify-locales.py` — 378 production strings complete in en/de/fr/es; 31 localized notice descriptions.
- `scripts/verify-inputs.py --archives --inventory` — pinned AAR/runtime hashes, three model archives re-downloaded and SHA-256/size matched, resolved dependency inventory matches notices, Silero VAD digest, backup/device-transfer exclusions. The production manifest contains only `accurate` and `lid`; no NGC/FastConformer artifact is accepted by the gate.
- `scripts/verify-release.py` / `check-apk.sh` — 4 ARM64 libs, 16 KB ZIP/ELF alignment, RELRO/NX, manifest permissions, no debug surfaces, notices present, APK SHA printed above.
- 16 KB **runtime** behavior is NOT MEASURED by these host-only gates.

## Device verification (MEASURED, TCL T807D, Android 16 / API 36, serial ZXKRS4VKGQ8PWGEQ)

- Debug-package device regression suite (the instrumentation harness only targets the debuggable
  `com.sprich.app.debug` build; the release APK is non-debuggable by design): **31/31 OK** in
  18.9 s — IME validation, pipeline correctness, real editor matrix, audio device validation,
  queue/actor stress, and the two upgrade-migration tests.
- Final release APK installed (`adb install -r` over the previous QA build) and exercised through a
  real editor: practice field + public-speech fixture -> inserted `I love you.` via
  Automatic = Whisper Tiny LID + Canary. Screenshot `artifacts/phaseB/device/t6-release-dictation.png`.
- Earlier in this cycle: upgrade from rc16 (v92) to the T3 configuration, fresh install with the
  270 MB Automatic download, and the debug file-level migration test are recorded in
  `artifacts/phaseB/device/t3-upgrade-migration.md`.

## Device settings

| Setting | Start | End |
|---|---|---|
| default IME | Gboard (`com.google.android.inputmethod.latin/...LatinIME`) | **restored to Gboard**, verified |
| font_scale / rotation / accelerometer_rotation / animator_duration_scale | 1.0 / 0 / 1 / 1.0 | unchanged |
| Google Voice Typing IME | enabled | enabled (temporarily disabled during T2 discovery, restored) |
| temporary QA keyboards (FlorisBoard, HeliBoard) | absent | installed only for T2 handoff, **uninstalled** |
| QA editor app | absent | installed for editor tests, **uninstalled** |
| Mac speaker volume | 25% | 25% (75% only during fixtures) |
| Sprich packages | previous QA build | QA release `com.sprich.app` v95 + debug/test packages remain installed for QA; no other app touched |
| bake-off data (`files/bakeoff`, `files/parakeet`, `/data/local/tmp` copies) | — | deleted after measurement; only summaries/hashes leave the device |

## Not measured / blocked

- Acoustic 20-clip-per-language subset (Mac speaker -> device microphone -> real editor) for the
  bake-off: **NOT MEASURED** (not run; T4 frozen-PCM measurement completed first and the device was
  being detached). The debug-only Parakeet engine has no editor routing, so a like-for-like
  acoustic comparison is additionally BLOCKED; no acoustic claim is made.
- 16 KB-page runtime behavior on a 16 KB emulator: NOT MEASURED in this cycle (host alignment
  checks passed).
- Public distribution, production signing, Zapstore/Nostr publishing: out of scope / maintainer.
