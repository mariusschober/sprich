> Historical development snapshot. Commands, architecture and acceptance claims below describe an older build. Use [the current README](../README.md) and [the current qualification evidence](../release/REVIEW.md) for this release candidate.

# Manual physical-device test script — required matrix (2026-09-01)

When blocked by hardware/emulator/model assets, this exact script must be executed on a real device before release. Repository is left buildable; no cloud speech is introduced.

## Prerequisites

```bash
export ANDROID_HOME=/Users/schober/Documents/Circadiano/.android-sdk
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk   # 47M, arm64-v8a
# Ensure device is arm64, Android 8–16, 6GB RAM mid-tier ideally (low/mid/high tiers per docs/LATENCY.md)
```

## Phase A — First-run and daily UX (5 min)

1. Install → open Sprich → grant microphone → enable Sprich keyboard in system Settings → switch to Sprich via globe.
2. Permission → model ready/download state: Settings → Advanced → verify `files/canary` shows Ready or Download (SHA-256 `7a38ed8b…`, 147M). Download if needed over Wi-Fi; verify atomic rename and `isCanaryReady()` (encoder/decoder >50M).
3. 5-second test phrase: focus any EditText → tap pill “Tap to speak” → speak “This is much faster than typing.” → verify composing appears <450ms, final commits <500ms after endpoint, one final commit, no duplication.
4. Language chip: Settings → Language shows `Auto` (or EN/DE/ES/FR). Switch to `Auto` → dictate English 10s, then German 10s — verify diagnostics (`files/diagnostics/latest.log` → `resolvedLanguage`, `task=TRANSCRIBE`, `sessionId`) logs correct tag without using `Locale.getDefault()`.
5. Mic state discoverability: verify `Listening…` + liquid bar + glow + aura visible <150ms after tap, one-tap pause stops (mic released <1s, `adb shell dumpsys audio` shows no recording), no silent background listening.

## Phase B — Languages (30 trials)

- Languages: English, German, Spanish; modes: `Auto` and each fixed language; test switching between consecutive fields (EN field → DE field → check `fieldB` does not contain EN text).
- Code-switching: single utterance mixing EN/DE — document as unsupported if WER >50% (expected, decoder src==tgt).

## Phase C — Acoustic conditions (each 5 utterances)

- Normal voice (50cm), whisper (15cm, quiet), far-field (1.5m), car, café, music/TV, fan, Bluetooth headset, wired headset.
- Measure WER/CER per condition (golden fixtures with consent, versioned `assets/jfk.wav` is baseline; create local `diagnostics/wav` captures via developer switch `AudioDiagnostics.isEnabled=true` for comparison, never by default).

## Phase D — Utterance shapes

- One word (“hello”), punctuation (“hello comma world period”), names/numbers (“Marius at example dot com”), 30 seconds continuous, pauses (“I think… hmm… Thursday” — must not prematurely commit), corrections (“Let’s meet tomorrow actually Friday”), immediate speech after focus (tap then speak within 100ms — verify first phoneme retained via pre-roll snapshot, 99/100 target).

## Phase E — State interruptions

- Permission denied/revoked (revoke via Settings → re-grant, no restart required), incoming call (GSM), audio focus conflict (play music → dictate → music ducks/pauses), screen off/on, rotation/configuration, app/service killed (`adb shell am force-stop`), model load failure (delete `files/canary` mid-session), low memory (`adb shell am send-trim-memory` level 80+), low storage (<450M free should block download), offline (airplane mode → identical behavior in `local` mode), rapid field switching (3 fields × 20 switches), repeated start/stop (50×), keyboard change (Sprich ↔ Gboard).

## Phase F — Editors (each 5 insertions)

- Native EditText, Compose `TextField`, WebView `contenteditable`, major apps: Chrome address bar, Gmail compose, WhatsApp/Telegram/Signal/Slack/Notion editors, Notes, multiline fields.
- Verify: composing span replaces previous partial, final commits exactly once, selection/spacing preserved, password fields (`TYPE_TEXT_VARIATION_PASSWORD`) never start mic, numeric fields handle, non-editable nodes ignored, secure apps blocked.

## Phase G — Performance and hardening (requires named device)

- Cold load, warm load, RTF (`inferenceMs / audioMs`), time to first partial, final latency, peak RAM (`adb shell dumpsys meminfo`), battery 10min continuous dictation, first-phoneme loss rate, duplicate/misdirected insertion count, microphone-release latency (`<1s` via `audio.stop()` join 300ms).
- Report P50/P95 per tier (low/mid/high) honestly; benchmark screen (7× tap version) exports `files/benchmark/export.json` locally.
- Verify no main-thread disk/model/inference (StrictMode `detectAll` in debug), no leaked `AudioRecord`/model/coroutine jobs after lifecycle tests (repeat 50× begin/cancel without unload).
- Run `adb shell dumpsys netstats` during dictation in `local` mode → 0 bytes; verify no raw audio retained (`files/diagnostics/wav` empty unless developer switch).

## Expected artifacts after manual run

- `files/diagnostics/latest.log` with `resolvedLanguage`, `task=TRANSCRIBE`, `sessionId`, `rtf`, memory.
- `files/benchmark/export.json` with deviceModel, SoC, RAM, engine, RTF, firstPartialMs, endToFinalMs, peakRssMb.
- Manual WER/CER table per language/condition.

## Blocker documentation (if hardware/emulator/model not available)

If any of the above cannot be executed, record:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./scripts/verify-models.sh && ./scripts/check-apk.sh
ls -lh app/build/outputs/apk/debug/app-debug.apk
cat app/build/reports/tests/testDebugUnitTest/index.html  # 79 tests
```

State exactly which matrix rows were skipped and why (e.g., “no Bluetooth headset available”, “no mid-range device, only Mac emulator”). Do not claim P95 or WER without measurement.
