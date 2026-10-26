# Device evidence — 2026-10-26 (R1–R5)

Device: TCL T807D, Android 16 / API 36, serial ZXKRS4VKGQ8PWGEQ. Release QA builds signed with
the local QA/debug certificate only. Emulator: `sprich_review_16k`, Android 16, ARM64,
16 KB pages (`getconf PAGE_SIZE` = 16384, kernel `pageSize` reported by the check = 16384).

## R1 — Canary blank fix (MEASURED)

Root cause: defensive Canary silence gate (RMS < 0.0005) rejected quiet but valid English speech
(6 of 7 blanks at RMS 0.00042–0.00049); the last one (RMS 0.00289) decoded empty at that level and
correctly at ~10x gain. Durations 6.6–14.9 s: not the 29 s PCM bound, and the sherpa export exposes
no token-budget parameter.

Fix: `CanarySpeechGate` (near-digital silence only), `CanaryInputGain` (bounded deterministic gain
for RMS < 0.01), `LongUtteranceSegmenter` (>20 s split at quietest plateau, zero-crossing snap,
segments decoded in order, `TypographyNormalizer` join), wired through
`LocalTranscriptionCoordinator` for both Automatic and Accurate. IME still performs one editor
mutation per utterance.

Frozen re-check (same committed FLEURS manifest, 200 speech clips, production-shaped LID→Canary,
debug build): **0 blanks** (was 7, all EN), WER EN 0.087 / DE 0.065 / ES 0.059 / FR 0.074,
overall 0.071 (was 0.109). Gate: PASS.

Long-speech gate (Mac speaker → TCL microphone → release rc20 IME → QA editor real EditText;
continuous repeated public speech, no inserted pauses): 22 s → 204 chars, 44 s → 439 chars,
66 s → 643 chars; all non-blank, final phrase present (multi-utterance above the 29 s PCM bound).
Script: `scripts/long-speech-test.py`.

## R2 — End-to-end latency (MEASURED; exploratory changes rejected/not run)

30 runs, 2 s public clip, Mac speaker → TCL mic → production IME → real EditText, poll 25 ms,
end-of-playback to first inserted character: **p50 2,427 ms, p95 2,489 ms** (28/30 insertions).
Canary decode threads=4: p50 2,432 ms / p95 2,563 ms → no ≥15% improvement, reverted. Measured
LID p50 = 679 ms (28% of end-to-end p50). Warm-load and parallel-LID variants were not built:
the review was stopped by the maintainer before those changes; NOT MEASURED, documented in
LATENCY.md. Canary warm PSS 565,900 KiB (frozen run); 16 KB emulator native Canary PSS 385,270 KiB.

## R3 — System IME picker rows (REPRODUCED, no app defect)

Reproduced on rc20: the picker shows one "Sprich" row per enabled IME without a subtype subtitle.
That is normal Android behaviour when exactly one typing subtype is implicitly enabled; the row
is selectable and switching works (verified with only the release IME enabled: tap → release IME
selected). On this QA device both release and debug IMEs are installed with the identical label
"Sprich", so two rows appear and selection is ambiguous — QA-only artefact. Instrumentation
`PickerSubtypeDiagnosticsDeviceTest` shows display names resolve ("English", "Voice"), explicit
subtype list is empty and implicit list is [keyboard, voice]. The onboarding "enable keyboard"
step needed one manual IME refresh in one automated run; it is not reproducible as an app defect
(Settings dialog confirmation was not persisted; once enabled, the app detected it).

## R4 — 16 KB pages (MEASURED native runtime; editor dictation NOT MEASURED)

Final rc20 release APK (v97, non-debuggable) installed on the Android 16 ARM64 16 KB-page
emulator. Pushed Canary + Whisper model files into the release app's private dir (adb root on the
emulator), ran `com.sprich.qa.editor/.NativeRuntimeCheck` with `expectedPageSize=16384`:

`{"status":"PASS","target":"com.sprich.app","versionCode":97,"debuggable":false,"sdk":36,
"pageSize":16384, modes vad/lid/canary}` — VAD 50.8 ms, LID 419.9 ms, Canary decode 1,009.0 ms;
PSS 385,270 KiB, native heap 432,605,456 B for Canary. The release APK's JNI/ONNX Runtime works
on 16 KB pages with a public WAV. Full IME editor dictation on the emulator is NOT MEASURED
(headless emulator has no host-audio route).

## R5 — rc20 candidate (MEASURED)

`1.0.0-rc20` (97), 29,422,187 bytes, SHA-256
`d9971c713d6955d3f3353f6097ecc2af3ca00817f714e2913b24bb5ba15c0d8e`, QA/debug certificate
`3af50cab…`. Host: 605 tests, 0 failures; lintRelease 0 errors; assembleRelease green; locales,
inputs/archive pins, release APK and check-apk PASS. Device regression on the final build:
**31/31 OK** (20.995 s). PR to `main` opened without merging (see final report).

## Device state at pause

Default IME restored to Gboard (verified); default settings unchanged: font_scale 1.0,
user_rotation 0, accelerometer_rotation 1, animator_duration_scale 1.0. Mac speaker volume 25%.
QA editor uninstalled; bake-off data removed from app storage and `/data/local/tmp`; temporary
emulator stopped. Only Sprich QA packages remain installed; no other app touched.
