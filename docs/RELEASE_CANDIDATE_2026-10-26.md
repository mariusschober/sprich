# Release candidate — 2026-10-26 (rc20)

## Artifact

| Field | Value |
|---|---|
| Branch | `release-1.0-prep` |
| Source code | `de6640b` (R1 fix; only docs/scripts committed after the build) |
| Version | `1.0.0-rc20` (97) |
| APK | 29,422,187 bytes |
| SHA-256 | `d9971c713d6955d3f3353f6097ecc2af3ca00817f714e2913b24bb5ba15c0d8e` |
| Signing | local Android **QA/debug** certificate `C=US, O=Android, CN=Android Debug`, SHA-256 `3af50cab6e0515f478413e79786958671913b2cae67037defe537bb1e7465069`. No production key; nothing was published. |
| Pull request | https://github.com/mariusschober/sprich/pull/2 (release-1.0-prep → main, **not merged**) |

## Verification (MEASURED)

- Host: **605 tests, 0 failures**; `lintRelease` 0 errors; `assembleRelease` green; dependency inventory written.
- `verify-locales.py`: 378 production strings complete in en/de/fr/es; 31 localized notice descriptions.
- `verify-inputs.py --archives --inventory`: pinned AAR/runtime hashes; three model archives re-hashed; resolved dependency inventory matches notices; Silero VAD digest; backup/device-transfer exclusions; production manifest contains only `accurate` and `lid` (no NGC/FastConformer artefact accepted).
- `verify-release.py` / `check-apk.sh`: four ARM64 libs, 16 KB ZIP/ELF alignment, RELRO/NX, manifest permissions, no debug surfaces, notices present; APK SHA above.
- Device regression on this build (TCL T807D, Android 16/API 36): **31/31 OK** in 21.0 s.
- 16 KB pages: final release APK (non-debuggable, v97) on an Android 16 ARM64 16 KB-page emulator, `pageSize=16384`, `NativeRuntimeCheck` PASS for VAD/LID/Canary using pushed models (Canary PSS 385,270 KiB, native heap 432,605,456 B). Full IME editor dictation on the emulator NOT MEASURED (headless emulator has no host-audio route).
- R1 gate: same 200 FLEURS clips → **0 blanks** (was 7), overall WER 0.071; continuous 22/44/66 s public speech inserted completely on the TCL.
- R2: end-to-end dictation p50 **2,427 ms** / p95 **2,489 ms** (2 s loudspeaker-played clip, 25 ms polling, 28/30 insertions). Canary threads=4 measured no improvement and was reverted.

## Not measured / blocked

- Human/spontaneous-speech accuracy; acoustic evidence remains loudspeaker playback of public read speech.
- Full IME editor dictation on the 16 KB emulator (no host-audio route); native runtime measured instead.
- Parallel-LID and explicit warm-load latency variants were not built before the review was stopped (NOT MEASURED; LID p50 679 ms measured as the potential ceiling).
- Production signing/Zapstore/Nostr publishing (maintainer).

## Device state

Default IME restored to Gboard; font_scale 1.0, user_rotation 0, accelerometer_rotation 1,
animator_duration_scale 1.0; Mac volume 25%; QA editor uninstalled; bake-off data removed; only
Sprich QA packages remain. No other app touched.
