# Current limitations

See [release/REVIEW.md](../release/REVIEW.md) for measured acceptance against the current artifact. Dated sprint reports describe older builds.

- ARM64 only, Android API 26–36. No 32-bit, x86 or pre-Android-8 package.
- Dictation and the interface support English, German, Spanish and French. The interface follows Android, while dictation language is chosen independently. Unsupported speech is not translated.
- Large offline speech files require a one-time download and spare installation space. Automatic detects the language per utterance; if language detection fails or returns an unsupported language, the utterance is not inserted (fail-closed, no guessed language). Accurate requires an explicit language.
- Moving the cursor/selection, changing field or hiding the keyboard cancels uninserted words. Review inserted text; speech recognition is fallible.
- Conservative editor checks can refuse an insertion/deletion in editors that do not expose sufficient reliable context. Sprich never retries an ambiguous mutation.
- Cloud providers are disabled in production. Mock/adapter tests establish error handling, not live provider readiness.
- Recovery text (E6): after an insertion fails or the field is abandoned, recognized text is held only in a bounded in-memory draft — one draft, at most 16,384 characters, 120-second lifetime — for the Home review/discard card. It is not bound to a field, is cleared on lock/discard/expiry/process death, and is never inserted automatically. The former unlimited same-field retry copy was removed together with the unreachable retry affordance; no recovery path bypasses fresh editor proof.
- Long acoustic fixtures do not replace spontaneous speech, accents, café/TV/headset conditions or a representative WER corpus. USB tests do not measure unplugged battery life.
- Zapstore publication requires a permanent Android app signer and Nostr publisher identity. The first signing setup was deferred. See the release handoff for the remaining publisher steps.
- FastConformer's upstream model is governed by NVIDIA NGC Terms of Use, which prohibit public redistribution. It is therefore not part of the production configuration; Automatic uses Whisper Tiny language detection plus Canary 180M Flash. Play and F-Droid publication are outside the current scope; no source-only F-Droid build-chain claim is made.
