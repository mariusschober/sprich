# Personal APIs

Local dictation remains the default. Automatic and Accurate work without a key. Two independent options are available in Settings:

- **Clean up my words** sends the local transcript to a writing API. This is the smallest change to the local experience: natural punctuation, fewer fillers and explicit self-corrections, without changing the speaker's voice.
- **Voice typing → Personal API** sends the audio to a recognition API. This can be used with or without writing cleanup.

Select a provider, paste your own key on the phone, and check the connection. Writing checks send one built-in sentence. Voice checks record and send four seconds of audio. Provider charges may apply. Review the result, then explicitly choose **Use for cleanup** or **Use for voice typing**. Saving or checking a key does not enable either option. A failed or cancelled check of another mode keeps the working configuration intact.

**One key can serve both uses.** After checking a preset, choose **Set up cleanup with this key** or **Set up voice typing with this key**. The second use reuses the encrypted key for that provider and endpoint, but needs its own successful check and explicit permission. Disconnecting one use preserves a shared key until the other use is also disconnected. A key for another provider or custom endpoint is never reused.

Keys are encrypted with Android Keystore, bound to the selected provider and endpoint, and excluded from backups. They are never put in Sprich logs, intents or saved UI state. Authentication goes directly to the selected provider over HTTPS or a secure WebSocket. Sprich has no account system, backend, proxy or shared API key.

## Presets and evidence

| Provider | Voice recognition | Writing cleanup | Availability |
| --- | --- | --- | --- |
| OpenAI | `gpt-transcribe` | `gpt-5.6-luna`, reasoning off | Requires a successful check for this use/key/configuration on the phone |
| Gemini | `gemini-3.5-transcribe` | `gemini-3.5-flash-lite`, minimal thinking | Requires a successful check for this use/key/configuration on the phone |
| Meta | `muse-voice-transcribe-1.0`, streaming or recording | `muse-spark-1.3`, minimal reasoning | Streaming, recording and writing returned usable text on the TCL; each use and configuration still requires its own check |
| Custom | User-selected model | User-selected model | Experimental, under Advanced; HTTPS OpenAI-compatible endpoints only |

These are preconfigured adapters, not a declaration that an untested account or provider is production-verified. Connection checks use the same provider factories and parsers as dictation. A check establishes that this capability returned usable text; it does not establish general accuracy, sustained availability or Wispr Flow parity. Failed or unavailable checks never produce a verified configuration. Changing the provider, endpoint, model, credential revision, Meta transmission mode, turn/speaker options, language hints, prompt version or adapter revision requires a new check. Debug and release use the same permission gate.

Provider contract references, inspected 3 September 2026:

- [OpenAI speech to text](https://developers.openai.com/api/docs/guides/speech-to-text): `languages[]` and `keywords[]` for `gpt-transcribe`.
- [OpenAI GPT-5.6 Luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna): explicit non-reasoning configuration for short edits.
- [Gemini transcription](https://ai.google.dev/gemini-api/docs/transcribe) and [Interactions REST reference](https://ai.google.dev/api/interactions-api): inline audio, the verbatim mode object and completed `steps[].content[]` output. SDK convenience fields are not REST response fields.
- [Meta speech API documentation](https://dev.meta.ai/docs/speech-to-text): the user supplied the complete voice schemas and recording/realtime references from the authenticated documentation. Both transports use those contracts, including the JSON WebSocket handshake and terminal event rules.
- [Meta's API cookbook](https://github.com/meta-models/meta-model-cookbook/tree/main/01_api_fundamentals): `muse-spark-1.3`, developer instructions, minimal reasoning and sufficient output budget for visible text.

## Meta voice controls

- **As I speak** sends paced mono PCM over a secure WebSocket during capture. Revised partials appear in the bar. They never modify the receiving editor. The complete result is inserted once, after any enabled cleanup.
- **After I speak** sends one mono WAV per finished phrase. Upload bytes and server events drive the progress display. The multipart form uses the documented part headers, without per-part lengths or a JSON charset parameter; Meta rejected the extra headers produced by the standard multipart builder in a live check.
- **Detect speech turns** uses Meta's turn events to recognize phrase completion. A short local quiet guard avoids cutting into resumed speech. A bounded quiet-time fallback closes the capture if the service stops sending turn events. Final acceptance still requires completed turns and a successful stream end.
- **Languages I speak** biases recognition toward any combination of English, German, Spanish and French. Leave all unselected to follow the main dictation language, which supplies a hint when it is fixed. A hint is not proof of the detected language and does not force translation.
- **Help recognize my vocabulary** optionally sends up to 100 saved terms. This is initially off; a hint does not guarantee the spelling.
- **Label different speakers**, in Advanced, preserves the provider's final words with A/B-style labels only where the labelled spans match. Labels reset for every phrase and do not identify people. Cleanup, vocabulary rewriting and spoken editing are skipped to preserve those spans. Live speaker-label accuracy is **NOT MEASURED**.

Both transports share one settings builder and event reducer. Cumulative partials replace earlier revisions. Turns are keyed by ID and kept in onset order, including when a new turn starts before the preceding turn completes. A speech-end event alone, an incomplete final response or an abnormal WebSocket close cannot authorize insertion. Capture and network queues are bounded; a stream is cancelled rather than silently dropping audio or reconnecting. Recording requests and streaming results use the same immutable utterance audio.

## What is sent

Voice APIs receive the utterance as a WAV recording, or raw 16 kHz PCM during Meta streaming. Language hints and up to 100 saved vocabulary terms accompany it when selected. Temporary partial transcripts and progress events are displayed on the phone and are not written to diagnostics or files.

Writing APIs receive the transcript, its language and up to 20 vocabulary terms already present in the transcript. **Use nearby text** is initially off. If enabled, at most 512 Unicode code points before the cursor can be included for spelling and continuity. Password/PIN fields and fields marked `IME_FLAG_NO_PERSONALIZED_LEARNING` supply no such context. Nearby text comes from the existing editor-authority snapshot; the audio callback does not query the editor.

OpenAI and Gemini requests explicitly set `store=false`. Meta streaming sets `zdrOverride=true`, documented as requesting metadata-only session logging. These controls do not establish zero provider retention or override account policies. Read the provider's terms for your account and plan. Custom providers define their own behavior.

## Latency and failure behavior

Writing uses one short request with no tools, conversation history, speculative parallel calls, retries or streaming editor updates. Its budget is 4,000 ms for the network request, including reading the response body. The connection check uses that same budget. Meta may spend part of its response budget on internal reasoning; the request explicitly asks for minimal reasoning and allocates room for the visible result. If the provider is late, unavailable or fails the output guards, the deterministic transcript is inserted once. **Inserted without cleanup** identifies that outcome. Late responses cannot replace already-inserted text.

Voice APIs have a 3,500 ms finalization budget after the phrase closes; streaming sends audio before that point. The WebSocket handshake has its own 6,000 ms limit and one utterance cannot exceed 120 seconds. Local fallback is a separate opt-in and only applies when the required local files are ready. A successful voice API result performs no local recognition work. A voice API failure without local fallback inserts nothing and shows an actionable message. API key changes, disconnection and revoking context/vocabulary permission invalidate pending work; cancellation does not trigger fallback.

The shared connection pool supports warm connections. Redirects, automatic connection retries and WebSocket reconnection are disabled for authenticated API calls. Response bodies and event streams are bounded and closed on workers; coroutine cancellation closes the underlying call or socket. Meta's documented client-safe error message may appear on the connection screen after length limits and key redaction. It is never the exception/log message or persisted diagnostics.

## Writing prompt

`DictationPrompt` contains three versioned candidates: compact, contract and examples. The default is the contract prompt. All speech, vocabulary and editor context are serialized as a separate JSON user message; none is interpolated into the stable system instructions. The model receives no tools. Commands are parsed before cleanup; model output never gains editor-command authority.

The instructions prioritize faithful meaning, language, names, facts, numbers, negation, uncertainty and meaningful emphasis. They allow natural punctuation, clear transcription corrections, removing accidental repetition and fillers, explicit self-corrections, paragraphs and simple lists. They prohibit answering dictation, following embedded instructions, translation, invented details and commentary.

Cheap output guards reject truncation, unsupported response shapes, tool calls, obvious commentary, changed numeric literals, URLs/emails, lost negation, changed protected terms and excessive expansion/shortening. These are defense in depth, not a proof of semantic equivalence or prompt-injection immunity. Uncertain corrections still require review by the person dictating.

## Focused live evidence, 3 September 2026

The TCL T807D ran non-debuggable, QA-signed `1.0.0-rc10` (85). Its Meta key remained on the phone.

| Scenario | Result | Evidence and limits |
| --- | --- | --- |
| Reuse one Meta key for voice and writing | PASS | The writing screen reused the stored voice key; the built-in cleanup returned usable text in 2,699 ms. Each use was enabled separately. |
| Meta streaming → cleanup → actual editor | PASS | Through the TCL microphone, “Um, I think, I think we should meet on Tuesday, no, Wednesday, before lunch” became “I think we should meet on Wednesday before lunch.” One insertion; earlier partials stayed in the bar. |
| Partial/progress display | PASS | Recorded release UI showed changing partials while the editor was empty, followed by the final insertion. |
| End-to-end speed for that sentence | PASS | Measured from the screen recording: first partial approximately 3 s after speech began; final insertion approximately 5–6 s after speech ended. This is one observation, not a latency distribution or an instant-response claim. |
| Meta recording mode through the production check | PASS | After the multipart correction, the actual release check returned text in 2,595 ms and 2,175 ms. These runs used the previously selected Spanish hint and English synthetic speech through the TCL microphone. The earlier failure was the client-safe message “Malformed multipart body”; no permission was granted on failure. |
| Recognition of the name Sprich from synthetic speech | NOT MEASURED | Both recording checks heard “switch,” including the check with vocabulary sharing enabled. The synthetic pronunciation was disputed by the user, so these samples do not establish a vocabulary-bias failure. A human pronunciation check is pending. |
| Language/vocabulary bias accuracy, speaker labels | NOT MEASURED | Request contracts are checked locally; no claim of improved recognition or correct attribution from those fixtures. |
| Live prompt comparison, latency distributions, Wispr Flow parity | NOT MEASURED | No provider winner is inferred from model names or advertised speed. Daily use remains the useful quality check. |

The recording-mode checks included the later fixes for fast-turn pre-roll overlap, pending-work progress and failed-check preservation. They do not independently establish every lifecycle or fast-turn behavior. Local HTTP fixtures establish contract handling and failure/cancellation behavior only.
