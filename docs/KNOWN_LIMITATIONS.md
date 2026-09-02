# Current limitations

See [release/REVIEW.md](../release/REVIEW.md) for measured acceptance against the current artifact. Dated sprint reports describe older builds.

- ARM64 only, Android API 26–36. No 32-bit, x86 or pre-Android-8 package.
- Dictation supports English, German, Spanish and French. The UI currently uses English. Unsupported speech is not a translation feature.
- Large offline speech files require a one-time download and spare installation space. Automatic detects the language per utterance; Accurate requires an explicit language.
- Moving the cursor/selection, changing field or hiding the keyboard cancels uninserted words. Review inserted text; speech recognition is fallible.
- Conservative editor checks can refuse an insertion/deletion in editors that do not expose sufficient reliable context. Sprich never retries an ambiguous mutation.
- Cloud providers are disabled in production. Mock/adapter tests establish error handling, not live provider readiness.
- Long acoustic fixtures do not replace spontaneous speech, accents, café/TV/headset conditions or a representative WER corpus. USB tests do not measure unplugged battery life.
- Zapstore publication requires a permanent Android app signer and Nostr publisher identity. The first signing setup was deferred. See the release handoff for the remaining publisher steps.
- FastConformer's upstream model is governed by NVIDIA NGC terms, not Apache-2.0. Human redistribution review remains separate from engineering qualification. Play and F-Droid publication are outside the current scope; no source-only F-Droid build-chain claim is made.
