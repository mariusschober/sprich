# MODEL BAKE-OFF — 2026-09-02 (T807D MT6878 Android 16)

This document compares candidate ASR architectures for Sprich's **Automatic language mode** and overall reliability. Numbers are measured where available; qualitative claims are marked as pending until a physical-device run.

## Methodology (same corpus for every engine when measured)

- Fixtures: `jfk.wav` (176000 samples, 11s, 16k mono PCM16) plus synthetic per-language tones for host tests; real human speech matrix still requires T807D human acoustic/editor runs per `docs/MANUAL_TEST_SCRIPT.md`.
- Metrics per engine: download size, cold load, warm startup, peak RSS, first partial p50/p95, endpoint→final p50/p95, RTF, WER/CER (where human transcripts exist), language-detection accuracy, unintended cross-language count, whisper WER, quiet-room WER, background-noise behavior, 5-min thermal, 15-min memory stability, battery impact.
- Automatic tests include alternating `EN→DE→EN→DE` without leaving field and `DE→EN` with <1s pause. An Automatic engine that cannot handle this is not Automatic enough.

## Baseline: Canary 180M Flash INT8 (production today, explicit languages)

| Metric | Measured on T807D (real sherpa 1.12.11, INT8, 2 threads, CPU) |
|---|---|
| Model files | `encoder.int8.onnx 127 MB` + `decoder.int8.onnx 71 MB` + `tokens.txt 52 KB` = **198 MB** on-device (`files/canary`) SHA `7a38ed8b13f014ad632b09ff8d22e0c6f1359dd046af9235d281dfae841b9ab9`, verified |
| Manifest sizeBytes | `207618048` (198 * 1024 * 1024) |
| APK | 47 MB, `libonnxruntime.so 15.9M` + `libsherpa-onnx-1.12.11` arm64-v8a 16KB, no bundled model |
| Cold load | 3.3–3.4 s (3421 ms, 3355 ms in 2 runs) |
| Cold inference (11s jfk) | 1565 ms RTF 0.142, 1550 ms RTF 0.140 |
| Warm (5 runs) | `[1510,1468,1459,1494,1500]` p50 1494 p95 1510 avgRtf 0.135; second run `[1516,1515,1657,1518,1492]` p50 1516 p95 1657 avgRtf 0.139 |
| Peak RSS (heap) | 5 MB **(heap only; real RSS 600 MB via dumpsys, native heap 308 MB — distinguish)** |
| Task | `TRANSCRIBE` only, `srcLang==tgtLang` enforced on every decode, 0/100 unintended translations in **synthetic** host fixtures (mock, not real human speech) — real speech pending human matrix |
| Languages | EN, DE, ES, FR via explicit `SpeechLanguage.Fixed` (BCP-47). **AUTO has no native support**: `capabilities.languageDetection=false`, `supportedLanguages` does not contain `AUTO`. When `Auto` is requested, engine logs and decodes as `en` fallback (single decode, no multi-decode guessing). Previous stopword multi-decode (EN/DE/ES 4s window + 30s hard cache) has been removed as architecturally invalid. Settings hides Automatic chip for Canary and shows fallback warning. |
| Streaming | Non-streaming windowed: speculative partial every 350 ms, LCP stabilizer N=2, deduped composing. True streaming false. |
| Concurrency | Single `inferenceDispatcher` (limitedParallelism(1)) + `Mutex` serializes setConfig/language switching, create/decode/getResult, final decode, release/unload. No concurrent partial+final on same recognizer. Stress test `CanaryConcurrencySerializationTest` proves `maxConcurrent==1`. |
| Per-utterance PCM | Isolated buffer from onset pre-roll (400 ms) through endpoint, frozen at finalization. Cloud fallback uses frozen slice, not `audio.ringBuffer.snapshotLast(30f)` global 30s. Regression `PerUtteranceAudioOwnershipTest` proves B contains no samples from A. |
| Insertion | Composing with `FieldSessionController` as single authoritative owner, `UtteranceToken` + `finalizeOnce` guarantees max one `endUtterance` and one final insertion per utterance, zero stale after field switch, zero replay with `SharedFlow(replay=0)`. 10k randomized transitions 0 duplicates (`ExactlyOnceStressTest`). Adversarial WebView (silent-commit) falls back to IME-local preview + single final (`CompositionAdversarialTest`). |
| French | Supported as explicit `fr` (Canary supports en/es/de/fr). Previous Auto detector ignored FR; now explicit FR works via `switchLanguage("fr")`. |
| Whisper/quiet | VAD calibration ignores speech frames, handles immediate speech and whisper profile (RMS 0.0008 threshold). Host and device VAD tests pass; human whisper still requires T807D acoustic matrix. |

**Verdict for Canary as Automatic:** Canary is an excellent **explicit-language** engine (RTF 0.135, accurate, offline). It must **not** be presented as having native `language=auto`. For now it is the production default with explicit language required; Automatic will return when a true Auto engine is validated.

## Candidate 1 — NVIDIA Nemotron 3.5 ASR Streaming 0.6B (highest-priority challenger)

| Property | Status 2026-09-02 |
|---|---|
| Model | `nemotron-3.5-asr-streaming-0.6b` — sherpa-onnx streaming RNNT, cache-aware, native `language=auto`, punctuation, capitalization, configurable chunk (160/320/560 ms + larger) |
| Package | sherpa INT8 560-ms: encoder ~627 MB, decoder ~14 MB, joiner ~9 MB — total ~650 MB. Q4_K GGUF `model_q4_k.gguf` ~630 MB (as in `ModelManifest` streaming entry) |
| Current sherpa | Bundled `1.12.11` AAR does **not** expose Nemotron streaming API. Upgrading sherpa-onnx (1.12 → current) is required on an isolated atomic commit plus full native/device suite. |
| Host | `NemotronEngine` exists as mock (streaming chunk 160 ms, mock sentence) but native `sprich_nemotron` lib not present; `isMock()` true. No measured RTF, load, RSS, WER on T807D yet. |
| Gating criteria (from spec) | Native Auto must reliably distinguish EN/DE; zero EN↔DE translation in clean corpus; first partial warm p50 ≤350 ms, p95 ≤600 ms; endpoint→final p95 ≤600 ms; RTF ≤0.5 sustained; acceptable memory on 7.6 GB T807D; no severe thermal degradation; WER competitive with Canary on EN/DE; whisper reliable; download UX acceptable (~650 MB). |
| Measured | **Not yet measured** — pending sherpa upgrade and model download on T807D. Host mock shows no real inference. |
| Decision | Keep Canary explicit as production until Nemotron passes all gates on T807D with real model. If it passes, prefer Nemotron as production Automatic engine; keep Canary as “Accurate” explicit fallback. |

## Candidate 2 — Multilingual Whisper Base or Small (experimental, smaller Auto baseline)

| Property | Status |
|---|---|
| Runtime | Reintroduce modern whisper.cpp / sherpa-whisper with true `language=auto`, `task=transcribe`, `translate=false`, no prompt/previous-text leakage, bounded context. Whisper Q5_1 `bundled` was deleted per user request (59.7 MB, SHA 422f…), `fastEngine` alias points to Canary. |
| Sizes | Base ~150 MB, Small ~500 MB (depending on quantization). Smaller than Nemotron but larger than Canary per language. |
| Measured | **Not yet reintroduced** — no Base/Small benchmark on T807D. Former Whisper was not benchmarked with current pipeline. |
| Gating | Must show true Auto, translate flag always false, no prompt leakage between fields, WER competitive, RTF and memory acceptable on T807D. |
| Decision | Benchmark Base and, if viable, Small as smaller native-Auto baseline. Whisper does not have to win; it serves as reference for Auto reliability vs Canary explicit. |

## Candidate 3 — Whisper Tiny LID + Canary (best migration path)

| Property | Status |
|---|---|
| Architecture | Onset → short Whisper Tiny (≈40 MB) language-ID inference → obtain EN/DE/ES/FR probabilities → configure Canary `srcLang==tgtLang` → transcribe current utterance. Detect per utterance; previous language only as prior, never 30-s hard lock; keep confidence; if ambiguous, don't choose just to produce text. |
| Added latency | Tiny LID adds ~100–200 ms first-utterance latency (to be measured) but removes 3× multi-decode (3×400 ms) and 30-s cache bugs. Since current Auto Canary showed no partial before LID anyway, fast LID may improve both correctness and perceived latency. |
| Model | Whisper Tiny `~75 MB` (quantized) + Canary 198 MB = ~273 MB total, less than Nemotron 650 MB. |
| Measured | **Not yet implemented** — no Tiny LID inference on T807D, no per-utterance LID accuracy measured. Host test `LanguageAutoRegressionTest` stubs per-utterance language switching but not real LID. |
| Decision | Evaluate added latency and memory vs Auto accuracy. If Nemotron fails device gate, compare Whisper primary vs Tiny LID + Canary on measured end-to-end product behavior, not isolated WER. |

## Optional — Low-cost FastConformer CTC (Sherpa NeMo, EN/DE/ES/FR combined)

**Context:** Per reliability pass, model work is out of scope until pipeline is correct. This is a lightweight bake-off identification only — no implementation.

| Property | Research note 2026-09-02 (sherpa-onnx docs, no device run) |
|---|---|
| Exact artifact | `sherpa-onnx-nemo-fast-conformer-transducer-en-de-es-fr` family (also en-de-es, de-es-en etc.) — NeMo FastConformer CTC/Transducer exported to ONNX via `nemo2onnx`. Check sherpa `model` listings for `nemo_fast_conformer_ctc_be-de-en-es-fr` / `nemo_transducer_en_de_es` variants. Hugging Face: `csukuangfj/sherpa-onnx-nemo-ctc-giga-am-en` style plus multilingual FastConformer checkpoints. Must verify exact current ONNX on sherpa 1.12 docs `https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo/index.html` |
| Size | CTC ~30–70 MB encoder + small decoder (FastConformer small); Transducer ~120–180 MB total. **10× smaller than Canary 198 MB**, ~5–20× smaller than Nemotron 650 MB. Fits low-tier 3GB devices and reduces download UX. |
| Punctuation/capitalization | FastConformer CTC models are typically **case-insensitive and punctuation-free** (lowercase, no punctuation) — requires external inverse text normalization (ITN) or LLM polish. Transducer variants may emit cased/punctuated but less reliably than Canary/Nemotron which have built-in punctuation. Must measure punctuation accuracy on T807D. |
| Language selection vs native Auto | **Not native Auto in the sherpa sense** — models are trained multilingually and infer language from acoustics without explicit `srcLang` switch, but accuracy depends on training mix. For EN/DE/ES/FR combined, they claim multilingual recognition without per-utterance language flag. This is *de facto* Auto for those 4 languages, but WER on code-switching and whisper, and unintended translation rate, must be measured vs Canary explicit and Nemotron true Auto. No hard 30-s cache; language is inferred per utterance from acoustics. |
| Why it may be useful lightweight candidate | Very small, fast CTC beam search, suitable for **streaming or fast WER baseline** before heavy Nemotron. Could be used as **tiny LID front-end** alternative to Whisper Tiny (≈75 MB) — CTC encoder much smaller and faster for LID, but LID accuracy unknown. |
| Why it may not be | CTC punctuation/casing gaps require ITN that may introduce false corrections (see SpokenEditingParser audit). Accuracy on German/Spanish/French conversational speech may lag Canary 180M/360M and Nemotron 0.6B. Must validate WER, RTF, and real human whisper on T807D before production. |
| Decision | Do **not** integrate this pass. Note for bake-off after pipeline is `PIPELINE_READY: YES`: Benchmark one NeMo FastConformer CTC EN-DE-ES-FR (≈60 MB) on T807D for WER/RTF/punctuation vs Canary explicit and Nemotron Auto. Only choose if WER competitive and punctuation handling via safe ITN is validated — otherwise keep Canary accuracy baseline. |

## Optional — Omnilingual ASR 300M INT8 (sherpa ~348 MB)

| Property | Status |
|---|---|
| Model | ~348 MB CTC, supports many languages |
| Measured | **Not benchmarked** — implementation would be cheap via sherpa, but no measurement yet. |
| Decision | Benchmark only if cheap; do not choose merely for language count. Evaluate punctuation, capitalization, EN/DE conversational WER, latency, whisper vs three primary candidates. Qwen3-ASR 0.6B larger INT8 package is deprioritized unless evidence shows outperformance on T807D. |

## Summary decision gate (as of 2026-09-02)

- **Production today:** Canary 180M Flash INT8 **explicit** (EN/DE/ES/FR, no Auto). It meets RTF, memory, concurrency, exactly-once, and insertion reliability gates on T807D as measured. Automatic is hidden in Settings for Canary; explicit language is required.
- **Next to validate:** Upgrade sherpa-onnx and benchmark **Nemotron 3.5 Streaming 0.6B** on T807D against all gates (Auto, RTF, WER, thermal, memory, whisper). This is the highest-priority challenger.
- **If Nemotron fails:** Compare **Whisper Base (and Small if viable)** vs **Whisper Tiny LID + Canary** on measured end-to-end behavior (Auto accuracy, latency, memory) and choose based on numbers, not isolated WER.
- Canary remains available as explicit-language “Accurate” engine even if another model becomes Automatic default.

All numbers above for Canary are from real `BenchmarkOnDeviceTest` (jfk.wav 11s, RTF 0.135–0.149, 198 MB) and host unit/property tests (133 tests, 0 failures, 2026-09-02; synthetic fixtures, not human goldens). Other candidates’ numbers are pending physical-device runs and are not claimed.
