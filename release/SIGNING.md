# Signing Sprich for Zapstore

**BLOCKED: the first permanent Android app signing key was deferred by the publisher.** Tested APKs use the local Android debug certificate for QA. They must not be distributed as the public app.

A keystore is a password-protected file holding your Android app's private signing key. You create it locally. Android uses its certificate to recognize future updates. Keep the same key for every Sprich release and retain secure backups of the file, alias and passwords.

Zapstore also uses your **Nostr identity** to sign the listing. The Android key signs the APK; your Nostr signer identifies its publisher. `zsp` links them by proving you control both. There is no Play account or Play upload key in this workflow.

## Create the Android key when ready

Android Studio → Build → Generate Signed Bundle / APK → APK → Create new creates a keystore. Use a location outside this repository, a unique alias such as `sprich`, strong passwords and a long certificate lifetime suitable for future updates. Back up the file and credentials before publishing. Follow [Android's signing guide](https://developer.android.com/studio/publish/app-signing).

Do not send passwords or private keys in chat or commit them. The repository ignores local signing configuration, JKS/PKCS12 key files and dotenv files.

## Sign the reviewed release

Copy `keystore.properties.template` to the ignored `keystore.properties`, using an absolute local keystore path. Alternatively supply all four values through a local secret store:

```text
SPRICH_KEYSTORE_FILE
SPRICH_KEYSTORE_PASSWORD
SPRICH_KEY_ALIAS
SPRICH_KEY_PASSWORD
```

Incomplete signing configuration fails. No signing configuration produces an unsigned release. Build the committed source with explicit version inputs:

```sh
./gradlew -PsprichVersionCode=89 -PsprichVersionName=1.0.0-rc14 :app:assembleRelease
```

Version 89 identifies the current unpublished QA candidate. Increase it for a later public payload rather than reusing a published code. Verify the APK with SDK `apksigner verify --verbose --print-certs` and retain its certificate SHA-256 with the release. Confirm it matches your designated key and is not `CN=Android Debug`. Keep the R8 mapping and native symbols for the same build.

Only then copy the verified signed APK to `release-output/zapstore/sprich-arm64-v8a.apk`. Unsigned and QA-signed copies are kept separately. Complete the narrow installation/dictation check with the permanent-key build before public distribution.

If a QA-signed Sprich is installed, the new signature cannot update it in place. Account for any local vocabulary/settings before removing that test installation; Android's signature check is expected.

## Connect your Nostr signer

Put your actual public `npub…` in the root `zapstore.yaml` as `pubkey:` and commit/push that public value. Never put an `nsec` or bunker secret in that file. Prefer your existing browser or remote signer.

With the listing prepared, `SIGN_WITH=browser zsp publish zapstore.yaml --channel beta` requests approval from your browser's Nostr signer and prompts for certificate linking on first publish. This command publishes externally. See [SUBMISSION.md](SUBMISSION.md) for the handoff.

Installed CLI verified here: `zsp 0.4.17`. Primary reference: [Zapstore publishing and certificate linking](https://zapstore.dev/docs/publish).
