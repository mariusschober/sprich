# Sprich privacy notice

Applies to the production release candidate reviewed on 2 September 2026. Experimental debug builds can have different behavior and are not covered by the production store declaration.

## Dictation

Speech recognition runs on your phone. This release does not enable online transcription or online text refinement. It has no Sprich account, ads, analytics SDK, remote crash reporting or shared API credentials.

Microphone audio is held in bounded memory while Sprich listens and processes an utterance. Normal dictation does not write audio or transcripts to files. Listening stops when you hide the keyboard, leave the field, move the cursor or change the selection. Password and PIN fields cannot start dictation.

Sprich reads nearby editor text and the current selection to insert text and safely support deletion/undo. These checks run locally. Text inserted into another app is controlled by that app and its privacy policy. The practice field in Sprich is temporary and is not saved across process recreation.

## Downloads and network

Internet access is used when you request a speech-file download. Files are served by GitHub and its release-asset hosts; these services receive normal connection information such as your IP address. Sprich sends no dictation audio, editor text, vocabulary or identifiers with model downloads. Downloads use HTTPS, bounded redirects to the allowed GitHub asset hosts, and pinned archive hashes. There is no background model update or remote configuration service.

After the selected speech files are installed, dictation works offline. Android and apps receiving your text may make their own network requests; Sprich does not control those apps.

## Data stored on the phone

- Speech files, their integrity receipts and temporary files used during installation.
- A bounded local crash breadcrumb (exception type and stack frames) and numeric metadata about the previous process exit. These contain no dictation or editor text and are not transmitted.
- Preferences and personal vocabulary entries you explicitly save.
- Any legacy API secrets: encrypted using Android Keystore in private storage, bound to a particular provider and endpoint. Unbound legacy keys are invalidated and require re-entry in an experimental build. No production provider can use them in this release.

The release has no diagnostic recording control or transcript export. Diagnostic code does not log editor text, transcript fragments, credentials or provider response bodies. Development builds can explicitly record test audio and are separate packages.

Sprich disables Android backup and device-transfer backup. Uninstalling the app or selecting Settings → Advanced → Clear Sprich data removes its speech files, vocabulary, preferences, credentials and temporary files. Clearing data closes the process so pending work cannot recreate it. It does not remove text already inserted into another app.

## Permissions

`RECORD_AUDIO` permits dictation after you grant it. `INTERNET` permits downloads; `ACCESS_NETWORK_STATE` checks connectivity. `VIBRATE` supports optional keyboard feedback. Sprich is an Android input method protected by the system's `BIND_INPUT_METHOD` permission. It has no accessibility service, contacts/storage/location permission, notification permission, foreground recording service or advertising ID permission.

## Contact and publication

Questions can be raised through the [Sprich repository](https://github.com/mariusschober/sprich/issues). Do not include private dictation or API keys in a public issue. This notice is available in the public repository and linked from the prepared Zapstore listing. The publisher must retain a monitored support route. No store privacy declaration or Zapstore publication has been submitted by this review.
