# Sprich privacy notice

Applies to the production release candidate updated on 3 September 2026. Debug builds can expose explicit test-audio recording and are separate packages.

## Dictation and optional personal APIs

Speech recognition runs on your phone by default. Automatic and Accurate remain useful without an API key. Sprich has no account, subscription, ads, analytics SDK, remote crash reporting, backend, proxy or shared API credentials.

You can separately enable an API for voice recognition or writing cleanup using your own provider account and key. A voice API receives the utterance as a recording, or as audio chunks while you speak if you select Meta streaming. A writing API receives the transcript, its language and relevant vocabulary terms. Requests go directly from the phone to the selected provider over HTTPS or a secure WebSocket. Sprich does not receive them. Provider prices and data policies apply, including any retention, monitoring or training terms associated with your plan. A paid account and a free account may have different terms.

Selecting a provider or saving/checking a key does not enable dictation through that API. A writing connection check sends a built-in sample sentence. A voice connection check records and sends four seconds of your voice. Each action identifies what it sends before you start it; API charges can apply.

Microphone audio is held in bounded memory while Sprich listens and processes an utterance. Normal dictation does not write audio or transcripts to files. Listening stops when you hide the keyboard, leave the field, move the cursor or change the selection. Password and PIN fields cannot start dictation. Cancelling a request cannot undo data that the selected provider has already received.

## Editor text and vocabulary

Sprich reads nearby editor text and the current selection locally to insert text and safely support deletion/undo. Text inserted into another app is controlled by that app and its privacy policy. The practice field in Sprich is temporary and is not saved across process recreation.

**Teach Sprich a word** records three to five short attempts using the current primary voice recognizer. Local recognition stays on the phone; an already-enabled voice API receives the recordings directly under its own data policy. Learning does not call a writing API or send dictionary hints. Audio is discarded after each attempt. Completed draft text stays in memory while the lesson is open; leaving discards it unless you select **Save word**. Saving stores the correct spelling, the original recognition text, your selected replacements and an identifier for the recognition configuration in Sprich's private vocabulary storage. API credentials are not part of that identifier. The typing keyboard used to enter the spelling is governed by its own privacy policy.

This is a correction dictionary, not voiceprint storage or speech-model training. Tap a learned word in Personal vocabulary to inspect its observations or remove it. Optional vocabulary sharing sends intended spellings, not the saved recognition errors or lesson history. Ordinary dictation is not added to this dictionary automatically.

Writing cleanup sends only saved vocabulary terms already present in the transcript, up to 20. **Use my vocabulary** can separately share up to 100 saved terms with a voice API and is initially off. Selected language hints are also sent with voice requests. Partial transcripts and progress events stay in the temporary dictation bar; partials never modify the receiving editor.

The optional **Use nearby text** setting lets the writing provider receive up to 512 Unicode code points before the cursor for spelling and continuity. It is initially off, and does not apply to password/PIN fields or fields marked private by the receiving app. Changing writing providers resets this permission. Turning it off cancels pending work that had the previous permission.

OpenAI and Gemini requests set `store=false`. Meta streaming sets `zdrOverride=true`, which its documentation describes as requesting metadata-only session logging. These controls do not override account-level data policies or establish zero retention. Custom APIs have their own behavior and policies.

## Downloads

When you request speech files, GitHub and its release-asset hosts receive normal connection information such as your IP address. Model downloads contain no dictation audio, editor text, vocabulary or API credentials. They use HTTPS, bounded redirects to allowed asset hosts and pinned archive hashes. Sprich has no background model-update or remote-configuration service.

After local speech files are installed, dictation works offline with personal APIs off. Android and apps receiving your text may make their own network requests; Sprich does not control those apps.

## Data stored on the phone

- Speech files, integrity receipts and temporary installation files.
- Preferences and personal vocabulary entries you explicitly save, including the recognition text from confirmed word lessons.
- API keys encrypted with Android Keystore in private storage, bound to their provider and endpoint. Preferences hold references, not plaintext keys. Unbound legacy keys require re-entry. Key-entry screens hide the key and block screenshots while you enter it.
- A bounded local crash breadcrumb and numeric previous-process exit metadata. They contain no dictation or editor text and are not transmitted.

Release diagnostics do not log editor text, transcript fragments, credentials or provider response bodies. A bounded, key-redacted explanation from Meta's documented client-safe error field may appear on the connection screen; it is not saved to files or diagnostics. There is no release audio-recording export control.

Android backup and device-transfer backup are disabled. Uninstalling Sprich or choosing **Settings → About your data → Clear Sprich data** removes its speech files, vocabulary, preferences, credentials and temporary files. Clearing data closes the process so pending work cannot recreate it. It does not remove text already inserted into other apps or data already received by an enabled API provider. Disconnecting an API stops that use; a key shared by voice recognition and writing cleanup remains stored until both uses are disconnected.

## Permissions

`RECORD_AUDIO` permits dictation after you grant it. `INTERNET` permits requested downloads and optional API calls; `ACCESS_NETWORK_STATE` checks connectivity. `VIBRATE` supports optional feedback. Sprich is an Android input method protected by the system's `BIND_INPUT_METHOD` permission. It has no accessibility service, contacts/storage/location permission, notification permission, foreground recording service or advertising ID permission.

## Contact

Use the [Sprich repository](https://github.com/mariusschober/sprich/issues) for questions. Do not include private dictation or API keys in public issues. This notice is linked from the prepared Zapstore listing; preparation does not mean the app has been published.
