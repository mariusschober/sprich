# Zapstore release handoff

**Target: Zapstore.** Play and F-Droid publishing were deferred by the publisher. No store upload, Blossom upload or Nostr publication has been performed.

Findings and qualification limits are in [REVIEW.md](REVIEW.md). The first package is prepared as a **beta release candidate**, not a claim that every acceptance case passed.

## Prepared

- Root [zapstore.yaml](../zapstore.yaml): repository, description, tags, model-license disclosure, local icon/screenshots and release notes.
- English copy and genuine screenshots in [fastlane/metadata/android/en-US](../fastlane/metadata/android/en-US). Fastlane is a metadata format consumed by `zsp`; using it does not submit to Play or F-Droid.
- [Privacy notice](../docs/PRIVACY.md), [complete notices](../licenses/THIRD_PARTY_NOTICES.txt), [native provenance](../native/README.md) and aggregated device evidence.
- Final artifacts and provenance in the ignored `release-output/` directory: unsigned APK, QA-signed APK, hashes, certificate, R8 mapping and available native symbols. The production path is deliberately empty until a permanent signer is provided.

## Remaining publisher decisions

1. Create and securely back up the permanent Android app signing key; setup was deferred. Follow [SIGNING.md](SIGNING.md).
2. Choose your Nostr identity. Add its public `npub…` as `pubkey:` in `zapstore.yaml` and push that change. No publisher key has been invented or selected.
3. Complete human review of runtime/model redistribution terms, especially NVIDIA NGC terms for FastConformer. MIT covers Sprich's app source, not every downloaded model.
4. Review the listing and confirm publication. Retain a monitored support route; the listing points to the public repository and privacy notice.

These are specific remaining steps, not a reason to repeat the broad test campaign.

## Validate without publishing

After placing the permanent-key-signed APK at the configured path:

```sh
zsp publish --check zapstore.yaml
```

In `zsp 0.4.17`, `--check` loads the config and checks APK acquisition/parsing and ARM64 compatibility. It does **not** prove publisher ownership, certificate linking, relay acceptance, model rights or successful installation.

QA validation uses a temporary copy of the same config with local file paths resolved and the APK source changed to the QA artifact. Its result is explicitly QA evidence. Do not copy the Android-debug-signed APK into the production path.

With your public identity available, a local event preview is possible:

```sh
SIGN_WITH='your actual npub' zsp publish zapstore.yaml --offline --channel beta \
  --commit "$(git rev-parse HEAD)" > release-output/zapstore/unsigned-events.jsonl
```

Replace the quoted value with your actual public key. `--offline` performs no upload or relay publication; using an `npub` creates unsigned events for inspection. The upload manifest describes files still needing upload. Neither this check nor these events mean the app is published.

## Publish only after approval

From the committed repository, with your permanent-key APK and actual `pubkey` configured:

```sh
SIGN_WITH=browser zsp publish zapstore.yaml --channel beta --commit "$(git rev-parse HEAD)"
```

Use the preview, approve with your Nostr signer, and complete certificate linking with your Android keystore when requested. Keep the interactive confirmations. The CLI uploads the APK/media to Blossom and publishes listing/release events to the Zapstore relay.

For a new publisher, Zapstore fetches the public root `zapstore.yaml` to match its `pubkey` to the event signer. Commit it before first publication. Do not use `--skip-certificate-linking` to hide missing ownership proof.

After publication, inspect the actual listing, install its downloaded APK, compare its hash/certificate to the approved artifact, and confirm offline dictation and an update from this signer. **Relay acceptance and delivered-APK installation: NOT MEASURED.**

References: [Zapstore publishing](https://zapstore.dev/docs/publish), [trust model](https://zapstore.dev/docs/trust-model), [zsp source and CLI](https://github.com/zapstore/zsp).
