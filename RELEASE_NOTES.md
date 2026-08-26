# RELEASE NOTES — Sprich 1.0.0 (2026-08-24)

## Highlights
- Local-only dictation IME: tap field → speak → text at cursor.
- Three engines: Fast (bundled Whisper Q5_1), Accurate (Canary INT8 download), Streaming (Nemotron Q4_K experimental).
- Instant vs Tap to Speak, password-field guard, haptics tick, composing text no duplication.
- Privacy: airplane mode, no transcript history by default, audio in RAM only, no analytics.

## Build artifacts
- `app-debug.apk` 19 MB (placeholder model) at `app/build/outputs/apk/debug/app-debug.apk`
- `app-release-unsigned.apk` 1.5 MB optimized (placeholder) at `app/build/outputs/apk/release/`
- Real release must replace `assets/models/whisper-base-q5_1.bin` with 57 MB real model (see docs/MODELS.md) → expected ~60 MB AAB.

## Verification
- `./scripts/verify-models.sh` — placeholder ok for debug, fails release if <50 MB
- `./scripts/check-apk.sh` — no canary/nemotron leak, speech network-free
- `./gradlew :app:testDebugUnitTest` — 34 tests pass
- Benchmark 7× tap version

## Upgrade notes
No migration; clear data via Settings → Clear local data removes models + prefs but keeps bundled Fast.

## Known issues
See docs/KNOWN_LIMITATIONS.md
