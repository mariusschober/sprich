# Sprich

Voice dictation for Android. Speak English, German, Spanish or French into the app you are already using. No account or subscription.

**Automatic** recognizes the language of each utterance. **Accurate** uses the language you select. Both run on your phone after a one-time download. Optional personal APIs can recognize your voice or clean up the local transcript. Choose a provider, paste your own key on the phone, check the connection and explicitly enable the use you want. [Setup, privacy and provider status](docs/PERSONAL_APIS.md).

## Use it

1. Open Sprich, allow microphone access and enable its keyboard.
2. Download Automatic (219 MB). Accurate is an optional 154 MB download in Settings.
3. Select Sprich in a text field and start speaking. It listens as soon as the keyboard opens. Pause to insert a sentence; tap the bar to finish.
4. Use the keyboard button to return to your regular keyboard.

Swipe left quickly to delete one word or symbol. A longer or slower swipe deletes the last phrase; hold at the end to keep deleting sentences. Swipe right to toggle Whisper mode, up out of the bar for your typing keyboard, or down to hide Sprich. Undo appears after a deletion, and holding the bar for 2½ seconds opens Settings. Moving the cursor or changing a selection stops listening and cancels words that have not been inserted. Password and PIN fields do not permit dictation. Spoken editing and personal vocabulary are in Settings.

Android 8.0–16 (API 26–36), ARM64 only. The interface supports English, German, French and Spanish and follows Android by default. On Android 13+, Settings → App language can override it for Sprich. The dictation language remains an independent choice. Recognition quality depends on speech and recording conditions; review the inserted text.

**Teach Sprich a word:** in Settings → Personal vocabulary, record a difficult name or term three times, then type its intended spelling or choose one app/contact name through Android's local picker. Choose which recognized forms to correct; two extra attempts are optional. Learned corrections apply to the recognition mode used for that lesson. Sprich never scans the address book or app list, audio is discarded, and only confirmed text observations are saved. [Design and safeguards](docs/VOCABULARY_LEARNING.md), [local-name privacy boundary](docs/LOCAL_NAME_LEARNING.md).

## Build and verify

Use JDK 17+, SDK 36 and NDK 27.0.12077973. Configure the SDK with Android Studio or `local.properties`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
python3 scripts/verify-locales.py
./gradlew -PsprichVersionCode=90 -PsprichVersionName=1.0.0-rc15 \
  :app:lintRelease :app:assembleRelease :app:writeReleaseDependencyInventory
python3 scripts/verify-inputs.py --inventory app/build/reports/release-runtime-dependencies.tsv
python3 scripts/verify-release.py app/build/outputs/apk/release/app-release-unsigned.apk
```

Choose an unused, increasing version code for distribution. Version 90 identifies this unpublished multilingual QA candidate. Release packaging requires explicit version inputs. Without signing configuration, outputs are unsigned. See [release/SIGNING.md](release/SIGNING.md) for first-time signing; the QA signing key is not a public distribution key.

The release build uses R8 and resource shrinking. Large recognition models are downloaded, hash-checked and installed atomically. A 644 KB speech detector is bundled. Native runtime provenance and build instructions are in [native/README.md](native/README.md).

## Review material

- [Architecture](docs/ARCHITECTURE.md), [models and licenses](docs/MODELS.md), [privacy](docs/PRIVACY.md)
- [API implementation and current focused evidence](docs/PERSONAL_APIS.md), [earlier core qualification](release/REVIEW.md), [known limitations](docs/KNOWN_LIMITATIONS.md), [measurement method](docs/LATENCY.md)
- [Zapstore preparation](release/SUBMISSION.md), [listing configuration](zapstore.yaml), [screenshots and artwork](release/store/README.md)

The opt-in [QA editor](qa-editor/README.md) exercises real Android editors and installed release native code. Debug fixtures, mock providers, experiments and developer screens are excluded from release artifacts. Dated sprint documents and old handovers are historical records, not current acceptance evidence.

The current release target is Zapstore. Play and F-Droid publishing are deferred. No public release has been published by this review; signing and publisher identity setup remain in the handoff.

App source is MIT. [Distribution requirements remain explicit](licenses/README.md), including the FastConformer model terms and corresponding-source delivery. Runtime and model terms differ; full notices are available in Settings → Licenses and notices and in [licenses/THIRD_PARTY_NOTICES.txt](licenses/THIRD_PARTY_NOTICES.txt).
