# KNOWN LIMITATIONS — reliability build (2026-09-02)

## Release scope

- Primary engine: Canary 180M Flash INT8 (`sherpa-onnx` INT8) via `files/canary` device-side download, not bundled. **198 MB** (127M encoder + 71M decoder + 52K tokens), SHA `7a38ed8b…`, verified.
- APK is arm64-v8a only, includes `libonnxruntime.so` + `libsherpa-onnx-1.12.11` (16KB aligned).
- Whisper Q5_1 deleted per user request; `fastEngine` alias points to Canary for benchmark compatibility.
- Nemotron streaming exists as mock (`NemotronEngine` with 160 ms chunks) but no native `sprich_nemotron` lib; `isMock()` true until sherpa upgraded to support `nemotron-3.5-asr-streaming-0.6b`. Not production.
- Remote STT (`speech/remote`) and AI polish (`ai/GrammarFixer`) are opt-in OpenAI-compatible endpoints (Grok x.ai, Groq, fal Wizper, etc.) but default to `local` (no network). Per-utterance PCM ensures fallback for B contains no samples from A.
- Current artifact is a debug build, not yet a signed Play release.

## What was fixed on 2026-09-02

- **Canary Auto architectural fix**: Removed stopword multi-decode (EN/DE/ES 4s window + 30s hard cache) and French omission. Canary has no native `language=auto`; `capabilities.languageDetection=false`, `supportedLanguages` no longer contains `AUTO`, explicit EN/DE/ES/FR only. Auto now requires explicit picker (onboarding EN/DE/ES/FR with locale suggestion, IME blocks Auto with clear prompt, Settings hides Auto for Canary and warns).
- **Pre-roll PCM**: `beginUtteranceCapture(preRoll)` now owns seeding exactly once; `pushAudio(preRoll)` duplication removed. Primitive `UtterancePcmBuffer` (no boxing, O(1), bounded 30s, frozen immutable) is single authoritative PCM owner; exact identity `[1,2,3,4,5,6,7]` proven.
- **Field/utterance lifecycle**: FieldSession can contain 10+ utterances; `commitUtterance` (Inserting→Listening keeps field alive) vs `commitFinal` (Ending→Idle only on field loss). `utteranceId` monotonic per field, finalized set ensures exactly-once; 5-utterance test `FieldSessionUtteranceLifecycleTest` proves same sessionId, listening continues, no new field session needed.
- **Stop generation safety**: Cancellation reasons (FIELD_LOST etc.) advance generation immediately and never insert; USER_STOP/ENDPOINT freeze PCM, claim utterance, finalize, then advance generation — no self-invalidation. Endpoint vs USER_STOP race exactly one `endUtterance` via finalized set.
- **Composition**: `discardPartial` (setComposingText("",1)+finish) vs `commitFinal` distinct; cancellation never commits speculative partial; silent-commit detection no longer deletes user text destructively, falls back to final-only preview; intentional repetitions (`very very`) preserved.
- **Native inference**: Every recognizer op inside `inferenceMutex` (partial loop, switch, decode, unload) + `limitedParallelism(1)` single owner; `sessionEpoch` drops late partials after session cancel/new session; max concurrency 1 measured via real `nativeDecodeMaxConcurrency`.
- **Spoken corrections**: Substring backtracking removed entirely (no "no" in "not"); ITN language-aware (EN email only, no unconditional "zero"/"one" → "0"/"1").
- **Silence & continuous**: Canary `isSilence` 0.004→0.0005 trusts VAD for whisper; VAD onset not blocked by previous final, frozen snapshot queued via `endUtteranceWithSnapshot` so next sentence first words not lost while previous decodes.
- Host suite 79→133 tests, 0 failures, including exact PCM identity, 5-utterance lifecycle, correction safety corpus, plus earlier 10k exactly-once, concurrency, adversarial, per-utterance.

## Validation still required on a physical phone (T807D)

- Human speech matrix: normal voice, whisper (synthetic host tones not human), far-field, car, café, music/TV, fan, Bluetooth/wired headset — requires human utterances. Host has synthetic tones for short/normal/whisper but not gold WER.
- Alternating `EN→DE→EN→DE` and `DE→EN <1s` without leaving field — host has synthetic per-utterance language switching but not real bilingual human goldens; needs T807D human `EN→DE→EN→DE` with transcript verification.
- App/editor matrix: Chrome, Gmail, WhatsApp/Telegram/Signal/Slack/Notion, WebView, Compose — requires manual focus switching in each app (unit + `CompositionAdversarialTest` cover logic, but not each app's real `EditorInfo` and composing behavior). New fallback should be verified in Chrome/WebView/contenteditable.
- Thermal/memory sustained: 5- and 15-minute runs with `dumpsys meminfo` + `ThermalMonitor` on T807D after exactly-once refactor (host proves no concurrent decode, but not sustained thermal).
- Airplane mode network audit: `dumpsys netstats` zero when `stt_mode=local` (code guarantees remote not invoked when local, plus per-utterance PCM isolation, but needs manual airplane toggle).
- Human immediate-speech-after-focus, long pauses (700 ms two sentences), intentional repetitions (`very very`, `no no no`), punctuation/names/numbers, 30-s utterance.
- Model bake-off: Nemotron 3.5 Streaming 0.6B, Whisper Base/Small, and Tiny LID + Canary have **not** been benchmarked on T807D (see `docs/MODEL_BAKEOFF.md`). Current numbers are Canary-only; other candidates pending sherpa upgrade and model downloads (~650 MB Nemotron). Must not claim WER/battery until measured.

## Product limitations remaining

- Energy VAD is simple; thresholds (onset 45ms, hesitation 400ms, endpoint 650ms) tuned for normal/whisper but may need device-specific tuning for very noisy rooms or unusually quiet mics. Calibration ignores speech frames for immediate-speech case.
- Canary is non-streaming: partials windowed 350 ms + LCP N=2; true streaming only if Nemotron passes gates.
- Cursor movement/manual typing while composing handled via `finishIfActive` but still needs cross-editor physical testing.
- Personal vocabulary and “Learn my corrections” remain incomplete UI features.
- Model diagnostics backup exclusions need release-policy review.
- Spoken deletion uses bounded char deletion not semantic sentences.
- Automatic language detection is not available on Canary; explicit language is required. Genuine Auto requires Nemotron or Whisper Tiny LID + Canary (see `MODEL_BAKEOFF`).

## What is intentionally no longer claimed

- No mock or placeholder inference (Canary mock only when sherpa not available in Robolectric; on device it is real).
- No working Nemotron/Whisper Auto path until measured — `MODEL_BAKEOFF` shows pending.
- No claim of “zero translations” from config tests alone — now `LanguageAutoRegressionTest` inspects actual `FinalTranscript.text` but still mock on host; real WER requires human goldens on T807D.
- No claim of duplication fixed from fake InputConnection alone — `CompositionAdversarialTest` plus production `FieldSessionController` path, but real editors still need T807D matrix.
- No 16 KB or bundle-size claim without `verify-models.sh` / `check-apk.sh`.

## Hardware-tier latency budgets (defined, to be measured physically)

- **Mid (6GB RAM, Snapdragon 730 tier)**: focus→capturing p95 <150ms, endpoint→final p95 <800ms, RTF <0.5, first phoneme loss <1/100.
- **High (8GB+, flagship SoC)**: focus→capturing p95 <100ms, endpoint→final p95 <500ms, RTF <0.3.
- **Low (3GB RAM)**: focus→capturing p95 <250ms, endpoint→final p95 <1200ms (streaming not recommended).

Benchmark screen (7× tap version) reports load time, inference time, RTF, peak RSS, backend, and exports `files/benchmark/export.json` locally. `MODEL_BAKEOFF` is the source for model choice, not `BENCHMARK` alone.

