# Sprich current status

Updated: 22 October 2026 · branch `improve/2026-10-22` · base `20be03c`.

This is the current capability summary. Dated sprint reports, handovers and the
2 September release review are historical evidence for the builds they name, not a
description of every later source change.

## What works now

- Android input method for API 26–36, ARM64 only. English, German, Spanish and French
  interface; dictation language is independent of the interface language.
- On-device Automatic (Whisper Tiny LID + FastConformer CTC) and Accurate (Canary 180M
  Flash) after user-initiated model downloads. Downloads are hash-verified and installed
  atomically. Settings exposes per-mode storage use and deletion.
- Optional personal voice or cleanup APIs with a user-supplied key. Keys are Keystore
  encrypted and provider/endpoint bound. A provider is not enabled merely by saving a key:
  a successful connection check and explicit enable are required. Providers can be turned
  off without deleting the key.
- Spoken editing commands, exact-authority delete/undo, vocabulary learning, local
  vocabulary search/management, whisper mode, gestures and four interface locales.
- A corrupt vocabulary file is preserved as `vocabulary.corrupt.<timestamp>.json` instead of
  being silently replaced.
- API configuration/key failures show API-specific guidance in the IME; setup, microphone
  and model errors open Sprich rather than retrying a dead end.

## Verification

Host checks (Linux, JDK 17, SDK 36):

```sh
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Instrumentation tests under `app/src/androidTest` require a real ARM64 device and are not
part of the host-only gate. Model accuracy claims remain limited to the fixtures described
in `release/REVIEW.md` and `docs/CORPUS.md`; there is still no representative per-language
WER corpus gate.

## Known release blockers

- No permanent Android signing key or Nostr publisher identity is configured. Public
  distribution remains blocked; see `release/SIGNING.md` and `release/REVIEW.md`.
- Human review of model/runtime redistribution terms (especially FastConformer/NGC) is
  still required.
- CI does not run instrumentation tests or an API 26/36 device matrix.
- Large-model memory/download lifecycle work still needs physical-device validation.

See `docs/KNOWN_LIMITATIONS.md` for product limits and `docs/PRIVACY.md` for data handling.
