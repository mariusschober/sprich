# Local app and contact names

## Decision

Sprich can use an installed app name or a contact name without uploading it,
without address-book access and without visibility into every installed package.
The feature belongs in **Teach Sprich a word**, after the recognizer has produced
three observations. Android
shows a system picker and Sprich reads only the display name the person chooses.

The feature deliberately does not build an automatic name dictionary. A name by
itself does not reveal how the active recognizer mishears it. Applying fuzzy
matches from a large address book would also make ordinary dictation less
predictable. A lesson supplies the missing evidence: actual recognition output,
an explicit intended name, the recognizer configuration and a reviewed mapping.

## Platform analysis

| Need | Android capability | Decision |
|---|---|---|
| One contact name | `ACTION_PICK` with `Contacts.CONTENT_TYPE` grants temporary access to the chosen contact | Use it; query only `DISPLAY_NAME_PRIMARY` |
| Every contact | Contacts Provider with `READ_CONTACTS` | Reject; unnecessary blanket access |
| One app name | `ACTION_PICK_ACTIVITY` lets Android present launchable apps; a narrow launcher-intent `<queries>` declaration makes the selected label visible | Use it; never call the app-list query APIs |
| Every installed package | PackageManager queries or `QUERY_ALL_PACKAGES` | Reject; service packages are irrelevant and broad visibility is unnecessary |

Android 8–16 use the established contact and activity picker contracts. Android
17 introduces a newer contact-picker session API; Sprich can adopt it after its
target SDK advances while retaining the current contract as the compatibility
path. No compatibility path may request full contacts access.

## User flow

1. Record the difficult name three to five times in the current voice mode.
2. On **How should it be written?**, type the name, choose an app or choose a
   contact.
3. Android owns and renders the list. Cancelling it changes nothing.
4. Sprich reads the selected display name off the main thread, normalizes Unicode
   and spaces, and applies the same 128-character/control-character validation as
   typed spelling.
5. Review observed replacements. Nothing reaches storage until **Save word**.
6. The saved record contains the spelling and the existing recognition lesson.
   It contains no contact URI, contact ID, package name, component, source flag,
   icon or list snapshot.

## Privacy and security invariants

- No `READ_CONTACTS`, `WRITE_CONTACTS` or `QUERY_ALL_PACKAGES` permission. The
  manifest declares only launcher-activity visibility because Android 11+
  otherwise hides the selected label returned by its own activity picker.
- No app/contact-list query, index, cache, background refresh or change observer.
- No network request is introduced. The picker itself is an Android/local app
  interaction.
- A chosen name follows the existing vocabulary rules. It can be sent as a hint
  only if the person separately enables vocabulary sharing for a personal voice
  API. Recognition errors, contact identifiers and app identifiers are never
  hints.
- Picker results are untrusted input. Reject missing components, non-content
  contact URIs, missing columns, invalid Unicode, controls and oversized labels.
- Cancellation, lookup failure or an unavailable picker preserves the current
  draft and offers typing as the fallback.
- No display name appears in logs, diagnostics or state `toString()` output.

## Lifecycle and failure behavior

Opening a picker backgrounds the learning activity, which stops any recorder and
revokes late recognition authority through the existing lifecycle path. The
picker is offered only in the spelling step, after recording has ended. Result
lookup is owned by the screen ViewModel. Leaving the screen cancels lookup; a
late value cannot save a lesson. Rotation can retain an in-memory draft, while
process death persists nothing unconfirmed.

If an OEM has no compatible picker or a selected provider does not expose a valid
display name, Sprich shows a calm local error and leaves manual typing available.
It never asks for a broader permission as a fallback.

## Acceptance

- Host: picker contracts, normalization, one-row contact lookup, manifest
  permission absence and draft-only persistence.
- Device: confirm both picker activities resolve, choose a known app and verify
  its label returns, open/cancel the contact picker without leaking names into
  logs, and check back/cancel behavior.
- Release: inspect the merged manifest and final APK permissions; install the
  exact artifact on the S23 and repeat the picker flow.

An actual personal-contact selection remains a human privacy-sensitive check. A
system picker opening proves availability, while a controlled provider test
proves the display-name query; neither should be described as reading the user's
private contacts without an explicit selection.
