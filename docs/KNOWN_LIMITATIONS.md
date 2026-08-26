# KNOWN LIMITATIONS — reliability build

## Release scope

- Fast Whisper Base Q5_1 is the only supported speech engine.
- The APK is arm64-v8a only.
- Canary is disabled and its runtime is not packaged.
- Nemotron is not implemented and its former dummy native library is removed.
- The current artifact is a debug build, not a signed Play release.

## Validation still required on a physical phone

- End-to-end IME dictation using the real microphone and a normal text field.
- Immediate speech, quiet speech, pauses, repeated utterances, tap-to-stop, and field/app switching.
- Password-field guard and microphone permission revoke/regrant.
- Chrome/WebView, Compose, and messaging-editor interoperability.
- Airplane mode plus a network-stat audit.
- Five- and fifteen-minute thermal, memory, and latency runs on a representative mid-range arm64 device.

The software ARM emulator is a correctness gate only. Its inference time is not evidence of phone performance.

## Product limitations

- Energy VAD is deliberately simple; thresholds may still need physical-device tuning for very noisy rooms or unusually quiet microphones.
- Fast is not a true streaming model. Partial results are speculative; committed text is finalized after the endpoint silence and a final decode.
- Cursor movement/manual typing while a composing hypothesis is visible still needs cross-editor physical testing; editor behavior is not uniform.
- Personal vocabulary management and “Learn my corrections” remain incomplete UI/product features.
- Fast-model and local-diagnostic backup exclusions need an explicit release-policy review before store distribution.
- Spoken deletion uses bounded character deletion rather than semantic sentence boundaries.
- The accessibility companion remains experimental. The primary supported integration is the Sprich IME.

## What is intentionally no longer claimed

- No mock or placeholder inference.
- No working Canary or Nemotron path.
- No measured budget-phone latency, thermal, or battery result until a physical run is recorded.
- No release-readiness claim based only on unit tests or emulator transcription.
