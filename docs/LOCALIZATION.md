# Sprich localization plan

## Product scope

Sprich ships one complete interface in four first-class languages:

- English (`en`)
- German (`de`)
- French (`fr`)
- Spanish (`es`)

The interface language follows Android by default. On Android 13 and later, people can choose a language for Sprich independently from the phone language. On Android 8–12, the language entry opens the phone language settings. Dictation language remains a separate product choice: **Automatic** recognizes all four supported speech languages, while **Accurate** uses the speech language selected in Sprich.

This work covers every production interface surface: onboarding, Home, Settings, model setup, the voice bar, gestures, optional personal APIs, vocabulary, word learning, errors, empty/loading states, accessibility actions, Android keyboard subtype labels, notices navigation and Zapstore copy.

Original license bodies, copyright attributions, component names, model names, API provider names, URLs and user/provider output are not translated. They are source material or proper names rather than Sprich interface copy. The explanatory descriptions around each license component are translated.

## Writing principles

Every language expresses the product intent directly. English sentence structure is not copied when the local language has a clearer convention.

- **English:** short, calm and direct.
- **German:** natural `du/dein` product language, compact compounds only where familiar, and clear verbs for irreversible actions.
- **French:** neutral `vous/votre`, idiomatic mobile terminology, and concise labels that avoid translated English noun stacks.
- **Spanish:** neutral, friendly `tú/tu`, familiar Android vocabulary and direct action labels.
- Product mode names remain consistent: **Automatic / Automatisch / Automatique / Automático** and **Accurate / Präzise / Précis / Preciso**.
- “Voice typing” is expressed as the familiar local concept: **Spracheingabe**, **Dictée vocale**, **Dictado por voz**.
- “Cleanup” describes the benefit rather than an implementation: **Text verfeinern**, **Améliorer mon texte**, **Mejorar mi texto**.
- Privacy copy says exactly what leaves the phone, where it goes and which choice enables it. It avoids “cloud” as a vague product label.
- Voice-bar text stays especially short because it must survive narrow fields, large text and translated verbs.

## Technical implementation

1. Keep English as the complete default resource set in `values/strings.xml`.
2. Add complete `values-de`, `values-fr` and `values-es` resource sets. Every translatable English key must exist in every set.
3. Register the four locales in `res/xml/locales_config.xml` and the application manifest so Android exposes per-app language controls.
4. Keep language splitting disabled for app bundles so APK, Zapstore and bundle-derived installs all contain every supported locale without a store-specific download service.
5. Add a visible **App language** row in Settings. It opens the per-app selector on Android 13+ and the phone language selector on older supported versions.
6. Resolve per-app resources explicitly inside the long-lived IME service. Render each status and hint from one locale snapshot, then refresh after configuration changes, so the keyboard cannot mix languages while the app locale changes.
7. Replace hard-coded language names with localized resources. Provider brands remain brand names; “Custom API” uses localized interface copy.
8. Map each notice component to a localized explanatory description while preserving the original legal documents and attribution text byte-for-byte.
9. Localize Zapstore/Fastlane title, summary, full description and release notes for Germany, France and Spain.

## Layout and accessibility rules

- Screens remain vertically scrollable and rows use flexible width; translated copy must never depend on a fixed single line.
- Buttons that can grow use full width or wrap naturally. Provider and language choices use flow layouts.
- The voice bar allows two lines but uses deliberately short state text and accessibility actions carry the full meaning.
- At 200% font size, essential controls must remain reachable and must not overlap, clip or render below navigation insets.
- German long compounds are shortened where a common alternative exists. French nonbreaking typography is avoided in resource text where it could force poor wrapping. Spanish labels favor verbs over long nouns.
- Right-to-left layout remains enabled for Android compatibility, although Arabic and Hebrew copy are outside this release.

## Build gates

`scripts/verify-locales.py` fails the build when:

- a key is missing or added only in one language;
- a formatting placeholder changes type, index or count;
- a locale is absent from Android locale metadata;
- app-bundle language splitting is re-enabled;
- a production Kotlin file introduces a hard-coded quoted `Text(...)` label;
- a production Kotlin or XML surface introduces another common form of hard-coded UI label;
- a notice description has no localized resource mapping.

Android resource compilation and release lint then validate XML escaping, formatting and resource use. Unit tests protect locale selection and notice mapping where practical.

## Acceptance matrix

For each language, verify the release candidate on a physical phone:

1. onboarding and microphone/keyboard setup;
2. Home ready, missing-model and download states;
3. Settings, all expanded sections and the language selector;
4. voice and writing API setup, including errors and progress;
5. personal vocabulary and the complete three-step learning flow;
6. notices list, search, component detail and original license text;
7. voice-bar idle, listening, writing, API progress, whisper, gesture previews and error states.

Repeat the high-density screens and voice bar in light and dark mode, portrait and landscape, at default and 200% font size. Restore the phone settings after testing. An automated key/placeholder gate is **PASS** only for structural completeness; untranslated nuance and physical layout remain separate evidence.

## Release handoff

The localization commit includes the final resource sets, Zapstore metadata, a versioned changelog and a final APK/AAB built from that exact commit. Publication remains separate from engineering verification and still requires the permanent Android signing key, the selected Nostr publisher identity and human legal review.
