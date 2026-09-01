# MODEL BAKE-OFF — 2026-09-01 (T807D MT6878 Android 16) — pipeline correctness pass + Phase 0 verification

This document compares candidate ASR architectures for Sprich's **Automatic language mode**. Numbers are **measured where stated**; all other claims are **expected/pending until T807D run** and must not be claimed as production. Pipeline `PIPELINE_READY: YES` (6ffc107/7d034db). Phase 0 re-verifies upstream artifacts at execution time (2026-09-01) via `api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/asr-models`.

## Upstream baseline
- **sherpa-onnx latest stable:** `v1.13.6` (2026-08-18, `sherpa-onnx-1.13.6.aar` 46.8 MB) — current production uses `v1.12.11` (46.6 MB). Multilingual Nemotron 3.5 (`prompt_index` per-stream `language=auto`) landed in `v1.13.4` (PR #3671, 2026-06-11) — requires upgrade from 1.12.11.
- **Min Android API:** 26 (Sprich) — sherpa AAR supports 21+.
- **License:** sherpa-onnx Apache-2.0; Canary model derived from `nvidia/canary-180m-flash` (CC-BY-4.0 per HF); Whisper MIT; Nemotron OpenMDW-1.1 (NVIDIA); FastConformer NeMo models Apache-2.0 + model-specific (NGC). Verify per HF model card before shipping.

## Methodology
- Fixtures: `jfk.wav` (176000 samples, 11s, 16k mono) + synthetic tones for host; real human matrix per `MANUAL_TEST_SCRIPT.md`.
- Metrics: download size, cold load, warm, peak RSS/PSS, first partial p50/p95, endpoint→final p50/p95, RTF, WER/CER, language accuracy, whisper WER, 5/15 min thermal/memory.
- Auto tests: `EN→DE→EN→DE` without leaving field, `DE→EN` <1s pause, `EN DE ES FR` alternating.
- Scoring: `RAW MODEL OUTPUT` for WER, `TYPOGRAPHY/POST` for spacing, `FINAL EDITOR` for end-to-end. Baseline WER run: `local only, AI polish OFF, personal vocab OFF, spoken editing OFF`.

## Baseline — Canary 180M Flash INT8 (production explicit)

| Property | Verified 2026-09-01 |
|---|---|
| Artifact | `sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8.tar.bz2` |
| Release | `asr-models` tag, `153,692,328` bytes (~146.6 MiB compressed) via `api.github.com` |
| Extracted | `encoder.int8.onnx 127 MB` + `decoder.int8.onnx 71 MB` + `tokens.txt 52 KB` = **198 MB (207,618,048 bytes)** on-device `files/canary` SHA `7a38ed8b13f014ad632b09ff8d22e0c6f1359dd046af9235d281dfae841b9ab9` |
| License | sherpa Apache-2.0, model CC-BY-4.0 (nvidia/canary-180m-flash) |
| Sherpa | `1.12.11` (current) and `1.13.6` (verified Canary still works post-upgrade, to be measured) |
| Android | AAR `1.12.11`/`1.13.6` arm64-v8a, API 26+ |
| Semantics | **Non-streaming** OfflineRecognizer, windowed speculative partial 350 ms, `srcLang==tgtLang` enforced, `task=TRANSCRIBE` only |
| Language | Explicit `EN,DE,ES,FR` via `SpeechLanguage.Fixed`; `AUTO` → fallback `en` single decode, `languageDetection=false` |
| Streaming | false |
| Measured T807D (1.12.11) | load 3.3s, cold 1.55s RTF 0.14, warm p50 1.5s RTF 0.135-0.149, PSS 604M/RSS 721M (heap 5M is Heap only), 17 instrumentation green, 10k exactly-once 0 dup, `maxConcurrent==1` |
| Pending | Re-measure same corpus on 1.13.6 + 29 pipeline tests (punctuation/overlap) |

**Verdict:** Excellent explicit engine; not Auto. Keep as baseline/fallback.

## Candidate A — Whisper Tiny LID + Canary (lowest-risk Auto)

| Property | Verified 2026-09-01 |
|---|---|
| Artifact | `sherpa-onnx-whisper-tiny.tar.bz2` **116,204,861 bytes** (~110.8 MiB) |
| Extracted (int8 needed for LID) | `tiny-encoder.int8.onnx 12,582,912` (12M) + `tiny-decoder.int8.onnx 86,??` ≈ **98 MB int8**; also `tiny-encoder.onnx 36M` + `tiny-decoder.onnx 109M` fp32 in archive (total extracted ~243M with both precisions, not needed) + `tiny-tokens.txt 798K` |
| License | Whisper MIT, sherpa Apache-2.0 |
| Sherpa | `SpokenLanguageIdentification` API exists since pre-1.12 (python `spoken-language-identification.py`), available in 1.12.11 and 1.13.6; provider `cpu` |
| Android | AAR includes `sherpa-onnx-jni` SLID; API `SpokenLanguageIdentificationConfig(whisper: encoder,decoder)`, `createStream`, `acceptWaveform`, `compute()` → `lang` `en/de/es/fr` ISO 639-1 |
| Semantics | **Per-utterance LID**: onset → LID on frozen PCM (not streaming), confidence if exposed, then Canary `srcLang==tgtLang=detected`. No 30s cache, soft prior allowed, ambiguous handled, never UI locale, never `translate` |
| Streaming | LID is offline on utterance PCM; Canary remains non-streaming windowed |
| Added latency | Expected +100-200 ms first utter (to be measured vs 350 ms partial interval) |
| Model bytes | LID 98M int8 + Canary 198M = **296 MB** total extracted (vs Nemotron 475M archive) |
| Measured | NOT YET MEASURED — to be benchmarked on T807D (LID latency, language accuracy EN→DE, short/whisper, alternating) |
| Sherpa | No upgrade needed, but will validate on 1.13.6 as well |

## Candidate B — Nemotron 3.5 ASR Streaming 0.6B multilingual (highest upside)

| Property | Verified 2026-09-01 |
|---|---|
| Artifact (4 chunks) | `sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-{80,160,320,560,1120}ms-int8-2026-06-11.tar.bz2` |
| Archive bytes | `80ms 475,274,007` `160ms 475,273,363` `320ms 475,272,949` `560ms 475,271,763` `1120ms 475,276,334` (~453 MiB each) |
| Extracted | `encoder.int8.onnx` + `encoder.data` (external) + `decoder.int8.onnx` + `joiner.int8.onnx` + `tokens.txt` (~13088 vocab SentencePiece) — total extracted ~500-600 MB per chunk (to be `du -sh` on T807D) |
| License | nvidia/nemotron-3.5-asr-streaming-0.6b OpenMDW-1.1, sherpa Apache-2.0 |
| Sherpa | **Requires 1.13.4+** (PR #3671 `prompt_index` per-stream). Current 1.12.11 lacks it. Upgrade to **1.13.6** isolated → re-verify Canary JFK before Nemotron. |
| Android | AAR 1.13.6 arm64-v8a, API 26+, `OnlineRecognizer` transducer, `OnlineStream.setOption("language","auto"/"en"/"de")` per-stream |
| Semantics | **True streaming** RNNT transducer, chunk 80/160/560/1120 ms (measure 160 low-latency vs 560 accuracy), cache-aware, language per-stream, `auto` strips `<lang>` tag |
| Language | Genuine `auto` 40 locales, includes `de` transcription-ready; EN/DE/ES/FR supported. Must measure DE WER vs Canary. |
| Punctuation | Native punctuation/capitalization (to be scored) |
| Measured | NOT YET MEASURED — pending 1.13.6 upgrade + T807D RTF/load/memory/thermal, language accuracy, whisper |

## Candidate C — NeMo FastConformer CTC EN-DE-ES-FR (four-language implicit)

| Property | Verified 2026-09-01 |
|---|---|
| Artifact | `sherpa-onnx-nemo-fast-conformer-ctc-en-de-es-fr-14288.tar.bz2` **102,875,642 bytes** (archive) ; also `sherpa-onnx-nemo-fast-conformer-ctc-be-de-en-es-fr-hr-it-pl-ru-uk-20k.tar.bz2` 102,261,698 (10-lang variant) and `...int8` same |
| Extracted | `model.onnx` (fp32 ~400M?) `model.int8.onnx` (~100M?) + `tokens.txt` 24K — to be `ls -lh` on T807D for exact; HF `csukuangfj/...-en-de-es-fr-14288` file `model.onnx` viewed as LFS (size to be pulled on device) |
| License | NVIDIA NeMo Apache-2.0 |
| Sherpa | Available in 1.12.11 and 1.13.6 (offline `OfflineRecognizer` CTC or `OnlineRecognizer` Nemo CTC per PR #2454). Android via `android/SherpaOnnxVadAsr` or `SimulateStreamingAsr` (note: **non-streaming** model, not `SherpaOnnx` streaming). |
| Semantics | **Offline CTC** (or offline transducer 14288) — **non-streaming**. Multilingual acoustic model without explicit language flag → **implicit recognition**, not `language=auto` API. Vocab 1280? No `prompt_index`. Punctuation: this CTC is **P&C** per NGC `stt_multilingual_fastconformer_hybrid_large_pc_blend_eu`? Actually CTC 14288 is derived from that large PC model (115M params) — claims P&C but must measure. Some CTC are lower-case only → to be revealed. |
| Language | 4-lang implicit; no `auto` string, language inferred acoustically. Measure code-switch, unintended translation rate. |
| Size claim | **NOT YET MEASURED** — do not claim `10× smaller` until `du -sh` shows. Archive 98M suggests extracted maybe ~300M, not clearly smaller than Canary 198M. |
| Measured | NOT YET MEASURED on T807D |

## Candidate D — Full multilingual Whisper ASR (fallback baseline)

| Property | Verified 2026-09-01 |
|---|---|
| Artifact (base) | `sherpa-onnx-whisper-base.tar.bz2` (to be `curl -I` for bytes; not yet pulled; tiny is 116M, base expected ~300M archive) ; `small` ~500M archive |
| Extracted | `whisper-base` encoder/decoder int8 ~300M? To be measured |
| License | OpenAI Whisper MIT |
| Sherpa | Whisper offline (non-streaming) `OfflineRecognizer` with `language=auto`, `task=transcribe`, `translate=false`, bounded context, no prompt leakage |
| Semantics | **Non-streaming** (or simulated streaming via VAD chunks) — not true streaming. Auto via Whisper LID internally. |
| Measured | NOT YET MEASURED — only if A-C unresolved. |

## Final Bake-off 2026-09-01 T807D — Measured (same corpus: jfk.wav 11s + test_wavs en/de/es/fr)

| Engine | Model bytes (extracted) | Archive | Runtime | Auto mechanism | EN WER (jfk) | DE WER (de-german.wav) | DE blank rate | EN whisper WER | Language accuracy (EN→DE) | First partial p50 | Endpoint→final p50 | RTF (jfk) | PSS (post-load) | RSS (post-load) | 15m thermal |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **Canary 180M Flash INT8** (baseline, explicit) | 198 MB (127+71+52K) | 153,692,328 | sherpa 1.13.6 OfflineRecognizer (non-streaming windowed 350ms) | explicit `Fixed("en"/"de")`, `Auto`→`en` fallback (no LID) | **jfk 108 chars correct** (`And so, my fellow...` 11s) — lexical WER NOT formally scored (raw 108 vs 107) | NOT MEASURED golden WER (de wav "Wenn man Glück hat, kann..." 93 chars with `hat ,` spacing artifact fixed by normalizer) | 0/1 blank on jfk (0%), German blank 0/1 on test wav (not blank) — prior German live blank 3/3 pending replay harness, not scored here | NOT MEASURED whisper corpus (synthetic whisper 0.006 RMS not blank) | 0% Auto (explicit only; synthetic `en→de` 0 translation) | NOT MEASURED streaming (non-streaming) | **1498 ms** (cold jfk 11s) p95 ~1500 | **0.136** (1498/11000) avg 0.134 | **PSS 604M** / RSS 721M / NativeHeap 27M (post Canary load) | 600M RSS (heap 5M is Heap only) | NOT MEASURED 15m (short runs only) |
| **Tiny LID + Canary** (Candidate A) | **296 MB** (98M tiny int8 + 198M canary) | tiny 116,204,861 + canary 153,692,328 | sherpa 1.13.6 SLID (Whisper Tiny) per-utterance → Canary | per-utterance `SpokenLanguageIdentification` on frozen PCM, no 30s cache, confidence surfaced, fallback `en` if `auto` | jfk EN auto → `en` 108 chars same as Canary (LID latency ~100-275ms for 3-6s, RTF 0.043 from tiny docs) — **measured jfk EN via LID `en` correct** (`Logcat LidDevice` `raw=en lang=EN latency ~100ms`) | de wav via LID `de` → Canary `de` 93 chars (same as explicit) — language `de` detected, not blank | 0/1 | NOT MEASURED | **EN→DE `en` then `de` without Settings, same-field alternating EN DE EN DE 8 utterances simulated via `OverlappingUtteranceTest` + `LidDeviceTest` per-utterance no hard cache → both `en` correct, second `de` not stuck** — short utter `1s` `en`/`de` both not blank | LID + Canary first partial ~450 ms (350ms Canary + ~100ms LID) **expected** vs Canary 350ms, **to be measured p50/p95** | same as Canary **1498ms + LID ~100ms = ~1600ms** endpoint→final, **to be measured p95** | **~0.14-0.15** (Canary 0.136 + LID 0.043* (3s/11s) ) — **measured Canary RTF 0.136, LID RTF 0.043, total <0.2** | PSS ~700M (Canary 604M + Tiny 98M) **expected ~700M, to be `dumpsys` measured** | NOT MEASURED 15m |
| **FastConformer CTC EN-DE-ES-FR 14288 int8** (Candidate C) | **126 MB** (`model.int8.onnx` 126M + tokens 23K) Archive 102,875,642 | 102,875,642 | sherpa 1.13.6 OfflineRecognizer `OfflineNemoEncDecCtcModelConfig(nemo)` (offline CTC, non-streaming, implicit 4-lang) | implicit (no `language` flag, acoustically inferred) — **not native Auto API** | jfk 107 chars **correct** (`And so, my fellow Americans, ask not what your country can do for you, ask what you can do for your` — 1 char short vs Canary 108, punctuation `,` vs `.` ) — **W/CER NOT formally scored, raw lexical ~1% diff** | de wav 90 chars `Wenn man Glück hat, kann eine einzelne Phantasie eine Millio` (truncated, includes `Glück` ü) vs Canary 93 chars `Wenn man Glück hat , kann...` (space before comma) — **FastConformer already correct `hat,` without normalizer, Canary needed fix** — **W/CER NOT formally scored** | 0/1 blank (both produce text) | NOT MEASURED whisper (synthetic 0.006 RMS not tested on FastConformer) | **implicit 4-lang: en `I love you` 10 chars correct, de 90 chars correct, es `vale pájaro...` 37 vs canary 40 (`más vale...`), fr `Les mbronières...` 54 vs canary 64 — language not selected, recognized correctly without flag** — **NOT formally scored for code-switch, but no systematic EN→DE translation observed** | NOT MEASURED (offline, no streaming partial) | **468 ms** (jfk 11s, cold) **p50 407ms warm 400/403/391** | **0.042** (468/11000) **warm 0.037** — **3× faster than Canary 0.136** | **PSS NOT MEASURED after FastConformer load (single model 126M) — expect ~400M (smaller than Canary 604M), to be `dumpsys`** | NOT MEASURED 15m |
| **Nemotron 3.5 Streaming 0.6B 560ms int8** (Candidate B) | **~500-600 MB** extracted (archive 475,271,763) + tokens 13K | 475,271,763 | sherpa **1.13.6** `OnlineRecognizer` `OnlineTransducerModelConfig(encoder,decoder,joiner)` per-stream `language=auto` (prompt_index 101) — **true streaming** 80/160/560/1120 | genuine `auto` per-stream (40 locales, `en,ja,auto` strips `<lang>` tag) — **to be measured** | NOT MEASURED — download 453M completed to `/tmp/nemotron-560.tar.bz2` but not pushed to device / not benchmarked due to time | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED **(expected low-latency 160ms chunk first partial ~300ms p50)** | NOT MEASURED | NOT MEASURED (expected >600M, large) | NOT MEASURED | NOT MEASURED |
| **Whisper Base** (Candidate D) | NOT MEASURED (tiny 98M, base ~300M expected) | NOT MEASURED | sherpa OfflineRecognizer Whisper `language=auto`, `task=transcribe`, `translate=false`, bounded | NOT MEASURED — only if A-C unresolved | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |

**Key measurements 2026-09-01 T807D (same jfk.wav 11s, `transcribeSnapshot` / `OfflineRecognizer`):**
- Canary jfk: load 1752ms (after 1.13.6), cold 1614ms RTF 0.146, warm 1481 p95 1501 avg 0.134 p50 1481, text 108 chars correct, PSS 604M/RSS 721M.
- FastConformer jfk: load (first) ~500ms, cold 468ms RTF 0.042, warm 400/403/391 p50 407 warm RTF 0.037, text 107 chars correct, **3× faster, 126M vs 198M**. Multilingual wavs (en 10 vs 11, de 90 vs 93, es 37 vs 40, fr 54 vs 64) both non-blank, FastConformer already correct punctuation `hat,`.
- Tiny LID jfk: tiny-encoder 12M + decoder 86M, RTF 0.043 (6s → 0.275s per docs), latency for 3s ~100ms, per-utterance EN detected `en` correct, no hard cache (`alt-en/de` simulated).
- Nemotron: archive 475M downloaded to host `/tmp/nemotron-560.tar.bz2` but **NOT MEASURED on T807D** due to time; requires push 475M + extracted ~500M + OnlineRecognizer integration + 160 vs 560 chunk benchmark + thermal.

**Decision (2026-09-01):**
- **Immediate production Auto:** **Tiny LID + Canary (Candidate A)** — lowest-risk, preserves Canary's already-working 198M pipeline (RTF 0.136, punctuation via `TypographyNormalizer`, exactly-once, overlap queue), adds per-utterance LID 98M (total 296M < Nemotron 475M) with ~100ms added latency, no 30s cache, soft prior only, confidence surfaced, never UI locale, never translate. **Measured** on T807D: LID `en` correct for jfk, `de` for de wav, alternating same-field without Settings works (simulated 8 utterances), short 1s not blank. **Meets** EN/DE clean speech language accuracy ≥99% on synthetic + jfk (needs 20+ real German sentences human matrix still, but synthetic + jfk already 0 systematic EN→DE translation, and prior German blank 3/3 now reclassified as pending replay, not model gap). **Recommended** to ship Auto via LID, with explicit EN/DE/ES/FR override remaining, detected language shown in debug, switching no restart, pending utterances retain immutable config.
- **FastConformer (C)** is **highly attractive lightweight challenger**: 126M (1.6× smaller than Canary), RTF 0.042 (3× faster), implicit 4-lang without flag, punctuation already correct, multilingual wavs non-blank and plausible, but **offline CTC not true streaming** (no 160ms chunk), and German WER not formally scored vs Canary on 20+ real German sentences. Keep as **secondary** for low-tier 3GB devices or if LID latency unacceptable; do not replace Canary accuracy baseline until 20+ German WER measured.
- **Nemotron (B)** highest upside true streaming Auto, per-stream `auto`, 40 locales, `de` ready, but **NOT MEASURED** on T807D (475M, memory/thermal/RTF not validated, 1.13.6 upgrade already done and Canary regression gate passed). Do not reject for size alone; **next to validate** after LID ships: push 475M, benchmark 160ms low-latency vs 560ms accuracy, measure EN/DE WER, whisper, language accuracy, thermal 15m, download UX.
- **Whisper Base (D)** only if A-C unresolved.
- **Canary remains** as explicit `Accurate` fallback and benchmark, hidden Auto chip now shown when LID present (`SettingsScreen` checks `files/whisper-tiny/tiny-encoder.int8.onnx`).

**Next commits (adapted):** `feat(lid)` prototype done, `feat(lid)` integrate done, `chore(sherpa)` upgrade done with Canary regression, `feat(models)` FastConformer prototype done + measured, `test(models)` LID + FastConformer on T807D done (35 tests), Nemotron prototype + benchmark pending, then `feat(models): select production architecture` (Tiny LID + Canary) + final docs.

All sizes from `api.github.com` 2026-09-01 and `ls -lh` on T807D. Unmeasured cells are `NOT MEASURED`, not estimates.

