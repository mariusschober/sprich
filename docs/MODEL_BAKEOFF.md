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

## Summary decision gate (as of 2026-09-01 — pipeline pass, Phase 0 verified, no new model measured yet)
- Production: Canary explicit. T807D 29 instrumentation green, RTF 0.135.
- Next: `Tiny LID + Canary` prototype (no sherpa upgrade) → `FastConformer 14288 CTC int8` prototype → `1.13.6` upgrade with Canary regression → `Nemotron 3.5 160/560 auto` → Whisper base only if needed.
- Canary remains as `Accurate` fallback.

All sizes above from `api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/asr-models` `size` field 2026-09-01, plus `k2-fsa.github.io/sherpa/onnx` docs. Other numbers (WER, RTF, PSS) are **NOT MEASURED** until T807D corpus run.
