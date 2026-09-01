# KNOWN LIMITATIONS — reliability build (2026-09-02)

## Release scope (post-closure 2026-09-03)

- **Automatic (selected):** Tiny LID (Whisper Tiny 98M per-utterance SLID) → FastConformer CTC 126M implicit EN-DE-ES-FR (**224 MB total, no Canary**). Single source `isAutomaticReady()`; fail-closed if either missing; no silent fallback to EN or Canary. Architecture diagram: `Mic → PCM collector ─┬─ Tiny LID → FastConformer ─ Automatic / └─ Canary ─ Accurate`.
- **Accurate explicit:** Canary 180M Flash INT8 (`files/canary` 198 MB 127+71+tokens, SHA 7a38… ) via `OfflineRecognizer`, fixed EN/DE/ES/FR, manual selection, benchmark retained.
- Canary required for Automatic: **NO** (hidden dependency removed, `CanaryLoadAttempts=0` proven via `AutomaticWithoutCanaryDeviceTest` + unit `AutomaticWithoutCanaryTest`).
- APK arm64-v8a only, `libonnxruntime.so` + `libsherpa-onnx-1.13.6` (16KB aligned). Whisper Q5_1 legacy alias kept for benchmark compat but not primary.
- Nemotron 560/160 code complete but NOT MEASURED for WER/thermal on 5-entry corpus, not primary, retained experimental.
- Remote STT/API mode future shape `UtteranceAudioCollector → PendingUtterance → TranscriptionSource { Local Auto, Local Accurate, API }` — collector independent so API STT can reuse same frozen PCM without engine coupling.
- Current artifact debug build, not signed Play release.

## What was fixed on 2026-09-03 — ASR Closure (Audio decoupling, hidden Canary removal, Unknown-LID safety)

- **Hidden Canary dependency removed (P0):** `SprichIME` no longer owns PCM via `CanaryEngine.beginUtteranceCapture/pushAudio/snapshot`. New neutral `UtteranceAudioCollector` (primitive ShortArray, bounded 30s, pre-roll exactly once, freeze immutable copyOf) in `core/audio` is single authoritative source. `Microphone → AudioCapture → UtteranceAudioCollector → immutable PendingUtterance.pcm → Transcription route` (Tiny LID→FastConformer for Automatic, Canary for Accurate). Automatic no longer loads Canary (`engine.load()` conditional on route, `canaryLoadAttempts=0` when Auto with Lid+Fast Ready). `SprichApp` no longer unconditional Canary preload; `SprichIME` selective preload based on `speechLanguage` and `isAutomaticReady()`.
- **Route snapshot & coordinator:** `PendingUtterance` now includes `route: LocalAsrRoute` (AutomaticFastConformer vs AccurateCanary) captured at endpoint; Settings change cannot mis-route queued utterance. Extracted `LocalTranscriptionCoordinator(lid, fast, canary)` isolates selection, easier to test without IME, prepares API `TranscriptionSource` shape.
- **Auto readiness single source:** `ModelManager.isAutomaticReady() = isWhisperTinyReady() && isFastConformerReady()`; Settings and IME use same derived flag, not just `lidStatus`. UI now shows `Automatic — Requires Language detector + Fast transcription model — Missing: X` and combined `Set up Automatic (download both)` CTA; current transcription display is dynamic (`Automatic · Fast on-device` / `Accurate · DE` / `Unavailable`) not stale `Canary 180M Flash INT8`.
- **Unknown-LID safety:** Introduced `ResolvedUtteranceLanguage { Known(Language) | Unknown }`. When LID returns Unsupported/Failed/Unavailable, FastConformer still decodes but post-processing uses generic-only path (`TypographyNormalizer.normalizeForUnknown` = fix `hello .`→`hello.`/`word ,`→`word,` only, preserve `? ! : ;`; `SpokenEditingParser` disables language-specific commands/English email ITN). Prevents German/Spanish/French transcript from receiving English `delete that`/`period` map.
- **Memory:** Automatic target `LID+Fast` (224 MB) not `LID+Fast+Canary`; `maybeUnloadUnused` unloads Canary when Auto active and queue drained, and vice versa. Diagnostics expose `collectorSamples/frozen/canaryLoads/fastLoads`.
- **Exactly-once preserved:** Bounded `Channel<PendingUtterance>(4)` + `Pending.pcm.copyOf()` isolation ensures endpoint+USER_STOP race at most one commit; field switch zero stale insertion; queue overlap preserves independent PCM (A/B isolation tests still PASS).

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

## Product limitations remaining (post-closure)

- Corpus still 5/75 measured (need 30+30+10+10 EN/DE + 15+15 ES/FR human WER/CER) — `AUTO_LANGUAGE_RELEASE_READY: NO`.
- Editor manual matrix (Chrome/Gmail input/textarea/contenteditable) still requires human tap validation (pipeline 12 tests + Ime 5 tests PASS, but real apps not yet fully matrixed).
- Energy VAD thresholds (45/400/650ms) simple, may need noisy-room tuning.
- Canary non-streaming windowed partials 350 ms + LCP N=2 (only for Accurate explicit; Automatic is final-only, no wrong-language partials by design).
- Cursor/manual typing while composing handled via `finishIfActive` but needs cross-editor physical testing.
- Personal vocabulary / Learn my corrections UI incomplete.
- Model diagnostics backup exclusions need release-policy review.
- Spoken deletion bounded char deletion, not semantic.
- 15-minute sustained PSS/RSS/NativeHeap via `adb shell dumpsys meminfo` for winner after load + after 20 utterances + after 15m still requires host shell measurement (app dumpsys permission denied, latency stable but exact PSS pending).

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

