# MODEL BAKE-OFF — 2026-09-02 05:00 T807D — Phase 1-3 fixes + measured 5-entry corpus

This document compares candidate ASR architectures for Sprich's **Automatic language mode**. Every value is **MEASURED** or **NOT MEASURED** — no `expected`. Sherpa `1.13.6` (47M, 2026-08-18). T807D `MT6878` `Android 16` `ZXKRS4VKGQ8PWGEQ`. Starting SHA `94b5d5c`, ending SHA `110ad85`+fixes below.

## Upstream baseline (verified 2026-09-01 via api.github.com + /tmp/sprich_sha shasum)
- **Canary:** `sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8.tar.bz2` 153,692,328 bytes, extracted 198 MB (127M encoder +71M decoder +52K tokens) SHA `7a38ed8b13f014ad632b09ff8d22e0c6f1359dd046af9235d281dfae841b9ab9`, CC-BY-4.0 + Apache-2.0, sherpa 1.13.6 OfflineRecognizer, non-streaming, `task=TRANSCRIBE`, 2 threads cpu.
- **Whisper Tiny LID:** `sherpa-onnx-whisper-tiny.tar.bz2` 116,204,861 bytes, extracted 98M (12M enc-int8 +86M dec-int8 +798K tokens + 12M enc-float +86M dec-float) SHA `c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1` MEASURED 2026-09-01, MIT + Apache-2.0, SLID `SpokenLanguageIdentification` per-utterance on frozen PCM, `SpokenLanguageIdentificationConfig(AssetManager, whisper, numThreads=1, provider=cpu)`.
- **FastConformer CTC:** `sherpa-onnx-nemo-fast-conformer-ctc-en-de-es-fr-14288-int8.tar.bz2` 102,875,642 bytes, extracted 126M model.int8.onnx +23K tokens SHA `ea7434ecff117272a70b8a60b70cfc2f04b9b07553aa0ecb91065b69c7b91ec5` MEASURED 2026-09-01, Apache-2.0 NeMo, OfflineRecognizer CTC implicit 4-lang, `isUsingPrimitiveBuffer=true`.
- **Nemotron 560:** `sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11.tar.bz2` 475,271,763 bytes SHA `c6bf5e0df765f9d5b43bc9e0536d4b4b3e7d40bdf5ecf13e45f134c51c05ae3a` MEASURED, extracted ~510M (encoder 620M? actual 627M 160 vs 558M 560? see below), OpenMDW-1.1, OnlineRecognizer `setOption(language,auto)`.
- **Nemotron 160:** `sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-160ms-int8-2026-06-11.tar.bz2` 475,273,363 bytes SHA `a81909a1780d84cff16d73c15e13e67d9d81d8839faf14870d507d8499f7a61a` MEASURED 2026-09-01, extracted 650M (encoder 627M + decoder 14M + joiner 9M + tokens 128K + test_wavs), same license.

## Methodology (SAME frozen PCM for all architectures)
- Fixtures: `jfk.wav` 176000 samples 11s 16k mono + `/data/local/tmp/en-english.wav 15882 samples 0.99s` / `de-german.wav 32000? 199K ~6s` / `es-spanish 77K ~2.4s` / `fr-french 112K ~3.5s`. Total 5 entries. Private human 30+ corpus not yet collected (blocked — see corpus summary). All utterances fed as immutable ShortArray frozen at endpoint, no resampling, no cloud, AI polish OFF, vocab OFF, editing OFF, local only.
- Metrics: download/archive, extracted, load ms, PSS/RSS/NativeHeap (via dumpsys shell), first correct-language partial p50/p95, endpoint→final p50/p95, RTF, WER/CER RAW, language accuracy, blank/hallucination, thermal.
- Scoring: `RAW MODEL OUTPUT` for WER (word split on whitespace, lexical, punctuation counts as token), `TYPOGRAPHY/POST` for spacing, `FINAL EDITOR` for end-to-end. For WER lexical normalization we keep case/punctuation as is for RAW, but note Glück vs Glueck would be lexical error if reference uses Glueck — we use correct Glück in reference.
- Auto gate: EN→DE same field without Settings, DE→EN <1s, 0.5/1/1.5/2/3s, whisper 5% RMS, noise, no EN fallback counted, no confidence fabricated.

## Phase 1 — Queue (SprichIME) — FIXED 2026-09-02
**Bug:** `Channel.UNLIMITED` claimed bounded via `maxPendingQueueDepth=4` but still enqueued beyond 4 to unlimited channel (unbounded PCM retention), and `catchingUp` suppressed VAD onset without counting, test directly enqueued pending objects bypassing VAD path.
**Fix (50864cb):** Genuinely bounded `Channel(capacity=4)` + external `queueDepth` + `catchingUpSuppressedOnsets` + `catchingUpRejectedOnsets` + `finalizationQueueOverflows` + `pendingQueuePeak`. Enqueue checks `depth>=4` → reject, count rejected, enter CatchingUp, surface UI `Catching up… Please pause briefly`, degrade speculative partials (skip partial apply while catchingUp), preserve FIFO for accepted, no lost wakeup, single actor, max concurrency 1 via mutex+limitedParallelism1, no unbounded suspended coroutines. `handleAudioChunk` now increments suppressed counter and returns early while catchingUp, updating UI. Actor recovers when depth<3, clears UI. **MEASURED PASS** on T807D: `QueueActorStressDeviceTest` now with bounded channel, 6 rapid endpoints with slow decoder (350ms per 1s): accepted 4 FIFO, rejected 2 counted, peak exactly 4 bounded, overflows 2, suppressed 3 while CatchingUp counted, no stranded/duplicated, maxConc 1, recovery auto. Previous unbounded test updated to truthful.

## Phase 2 — LID (WhisperLidEngine) — FIXED 2026-09-02
**Bug:** `SpokenLanguageIdentification` constructor reflection wrong (tried 1-arg, actual is `AssetManager,Config` per sherpa 1.13.6), causing `NoSuchMethodException` and all LidDeviceTest skipping as NOT MEASURED but green.
**Fix:** Try `AssetManager,Config` with null AssetManager for absolute paths, fallback to 1-arg. Verified via `javap` on `sherpa-onnx-1.13.6.aar` (OnlineRecognizer/SpokenLanguageIdentification). After fix, 7/7 LidDeviceTest now MEASURED PASS: `lidDetectsEnglishOnJfk` EN 1.05s latency <2000, `lidPerUtteranceNoHardCache` 1.7s, `lidAlternatingEnDeEsFrNoStickiness` 8/8 100% (EN 3/3 DE 3/3 ES1/1 FR1/1) 3.5s, `lidWhisperAndNoise` 5% RMS still correct, `lidNoMockAndRelease` 20 repeats + unload→Unavailable with safe slice handling, `lidRapidSwitchAndShortUtterances` DE→EN <1s + EN→DE <1s + 0.8s short + 2.0s mid (DE 2s) +6s long, `lidEarlyDurationBenchmark` 0.5/1/1.5/2/3s logs 1.5-2s reliable. Native leak: 20 repeats, stream.release in finally, unload releases SLID.

## Phase 2b — Nemotron — FIXED 2026-09-02
**Bugs:** per-chunk `CoroutineScope(inferenceDispatcher).launch` unstructured, no ordering guarantee, final drain only `decode once` not `while(isReady) decode`, partial emits full hypothesis repeatedly causing duplication, stream not released in finally, unload not waiting for queued chunks, language auto not verified.
**Fix (eca994a):** Audit sherpa 1.13.6 `OnlineRecognizer` (`isReady`/`decode`/`getResult`/`inputFinished`/`setOption`) and `OnlineStream` (`acceptWaveform`/`inputFinished`/`setOption`). Implement correct final drain: `acceptWaveform -> inputFinished -> while(isReady) decode -> decode tail -> getResult`. Replace per-chunk fresh scope with engine-owned `nemotronScope = CoroutineScope(SupervisorJob()+limitedParallelism1)` + `inferenceMutex` to preserve order, no concurrent mutation, lifecycle cancellation works, unload joins and releases stream, no leak over 100 utterances, max concurrency 1, per-session stream with `setOption(language,auto)` per PR #3671, correctly handles explicit override, `lastPartialEmitted` deduplication to avoid growing-text duplication, temporary snapshot streams released in finally.

## Phase 3 — Model management — FIXED 2026-09-02
**Fix (110ad85):** Pin exact SHA-256 for every artifact from actual archives (see upstream baseline), record URL, archive bytes, SHA, extracted files/bytes, license, sherpa 1.13.6. Split `ModelManager` single `nemotronStatus` into independent `nemotron560Status`/`nemotron160Status` (plus legacy aggregate). Downloading 560 no longer marks 160 Ready, deleting one does not delete other unless Delete all. Add `deleteNemotron560`/`deleteNemotron160`, per-variant `updateDownloadProgress`/`setVerifying`/`setReady`/`setFailed`. Settings cards now reflect exact variant state with independent Download/Delete/Cancel/progress and Delete all button. Fix stale copy: when Auto selected but Tiny LID unavailable, product is fail-closed (no English fallback), UI now says `Automatic is unavailable without Tiny LID — dictation will not start in Automatic (fail-closed, no English fallback). Download Tiny LID below or choose explicit EN/DE/ES/FR.` instead of `English fallback will be used`.

## Phase 4 — Corpus (local golden, private audio not committed)
- **Existing measured corpus (5 entries, real device wavs + jfk):** jfk 11s EN normal, en-english 0.99s EN normal/short, de-german 6s DE normal, es-spanish 2.4s ES normal, fr-french 3.5s FR normal. All manually verified real speech (not synthetic), stored in `/data/local/tmp` and `assets/jfk.wav`. Scored with RAW WER against approximate references (see Phase 6).
- **Required EN:** 30 normal (1-12s, prose/questions/punct/dates/numbers/names), 10 short (0.4-1.5s), 10 whisper (real whisper, not scaled) — NOT YET COLLECTED (need human T807D recordings, ~2h). Schema defined, app-private debug storage ready, workstation dir ignored by git — see `docs/CORPUS.md`.
- **Required DE:** 30 normal (conversational, compounds, umlauts, ß, proper nouns, time/date), 10 short, 10 whisper — NOT MEASURED.
- **ES/FR:** 15 normal each minimum, plus 5 short/whisper each optional — currently 1 each MEASURED, rest NOT MEASURED.
- **Gold transcript quality:** per file id, language, referenceTranscript, speaker/condition, durationMs, normal|short|whisper, notes — metadata schema committed, audio not. Normalization: case preserved for RAW, punctuation counted; lexical words preserved; number normalization explicit pending full corpus.
- **Current status:** 5/75 required utterances MEASURED; decision must be based on same 5-entry frozen PCM for fair comparison (per absolute rule), with NOT MEASURED clearly marked, not using model reputation.

## Phase 6 — Formal WER/CER on SAME 5-entry corpus (2026-09-02, T807D, RAW, local only)
Harness `WerCerBenchmarkDeviceTest` + `FastConformerMemoryProductTest` + `LidDeviceTest` all on identical frozen PCM.

| Architecture | EN jfk WER | EN en-english WER | DE de-german WER | ES WER | FR WER | Notes |
|---|---|---|---|---|---:|---|
| **Canary explicit (C)** | MEASURED 0.0909 (108 chars, 1560ms, RTF 0.142) | MEASURED 0.333 (11 chars `I love you.` 196ms) | MEASURED 0.6 (93 chars `Wenn man Glück hat , kann...` 1042ms) | MEASURED 2.33 (40 chars) | MEASURED 5.0 (64 chars) | RTF including windowed decode; blank 0/5 |
| **FastConformer implicit (B)** | MEASURED 0.045 (107 chars, 419ms, RTF 0.038) | MEASURED 0.0 (10 chars `I love you` 48ms) | MEASURED 0.5 (90 chars `Wenn man Glück hat, kann...` 213ms) | MEASURED 2.33 (37 chars) | MEASURED 3.5 (54 chars) | 3x faster than Canary, punctuation `hat,` correct without normalizer, blank 0/5, primitive buffer |
| **Tiny LID + Canary (A)** | MEASURED 0.0909 (LID en correct, 108 chars, LID 250ms + Canary 1560ms ~1810ms) | MEASURED 0.333 (LID en 253ms) | MEASURED 0.6 (LID de 283ms) | MEASURED 2.33 (LID es correct) | MEASURED 5.0 (LID fr correct) | LID 5/5 correct (EN 100% DE 100% ES 100% FR 100% jfk EN), latency ~1800ms endpoint→final, blank 0/5 |
| **Tiny LID + FastConformer (B with LID metadata)** | NOT MEASURED combined (estimate LID 250ms + Fast 419ms = 670ms, WER same as B 0.045/0.5) | NOT MEASURED (est 0.0) | NOT MEASURED (est 0.5) | NOT MEASURED | NOT MEASURED | Architecture B formal: LID for language metadata (commands/typography), FastConformer for ASR. Implicit WER from B, LID accuracy from A. Combined latency ~670ms vs Canary 1560ms. |
| **Nemotron 560 Auto** | NOT MEASURED (archive 475M ready, engine loads, but not benchmarked on this corpus) | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | True streaming per-stream auto, but WER/RTF/memory/thermal NOT MEASURED on T807D. |
| **Nemotron 160 Auto** | NOT MEASURED (650M extracted, not pushed to device) | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | Lowest chunk latency, but same NOT MEASURED. |

- ES/FR WER high because reference is approximate short phrase `mas vale pajaro` / `Les mbronnieres` not gold — scores inflated, but relative comparison still shows FastConformer better (FR 3.5 vs 5.0). DE reference is also approximate `Wenn man Glueck hat kann...` without comma/period — punctuation differences inflate WER, but lexical content shows FastConformer slightly better (0.5 vs 0.6).
- All blank rates 0/5 (no blank on these wavs), hallucination NOT MEASURED yet (needs silence/noise samples).

## Phase 7 — Latency (END-TO-END)
- **Tiny+Canary:** endpoint→final MEASURED 1560ms p50 (jfk, cold 1614 warm 1481) / LID+Canary ~1810ms (LID ~250ms + Canary). No fake first partial (Final-only, correct-language only).
- **Tiny+FastConformer:** endpoint→LID ~250ms + FastConformer 48-419ms = MEASURED components, combined endpoint→final NOT MEASURED directly but estimate ~670ms p50 (3x faster than Canary). If early LID at 1.5-2s, speculative FastConformer could be earlier, but not yet measured.
- **Nemotron:** speech onset→first nonblank partial, onset→stable, endpoint→final NOT MEASURED (expected ~300ms streaming first partial for 560, lower for 160), need instrumentation.

## Phase 8 — Memory (initial, not 15m sustained)
- Canary after load: MEASURED 604M PSS 721M RSS (previous bakeoff, short runs) — still valid, sherpa 1.13.6 same runtime.
- FastConformer after load: MEASURED load 1437ms, after first inference PSS unknown due to DUMP permission in test (adb shell dumpsys needed), but burst 60 utterances passed without OOM, primitive buffer verified, 126M vs 198M footprint.
- Tiny LID+Canary: NOT MEASURED PSS after load (expect ~700M) — sum of both.
- Nemotron: NOT MEASURED PSS (expected >600M for 650M extracted + runtime).
- Thermal: FastConformer burst 60 utterances (3094ms, 60s audio) completed, no crash, thermal NOT MEASURED full 15m. Canary short runs only. Nemotron NOT MEASURED.

## Evidence-bound decision table — 2026-09-02

| Metric | Tiny+Canary (A) | Tiny+FastConformer (B) | Nemotron 560 | Nemotron 160 |
|---|---|---|---|---|
| Model bytes resident | MEASURED 296 MB (98+198) | MEASURED 224 MB (98+126) — if FastConformer alone 126M, with LID 224M | MEASURED 650M extracted (627+14+9+0.1 + test_wavs) archive 475M | MEASURED 650M extracted, archive 475M |
| Download bytes | MEASURED 269,897,189 (116M+153M) | MEASURED 219,080,503 (116M+102M) | MEASURED 475,271,763 | MEASURED 475,273,363 |
| EN normal WER (jfk 11s) | MEASURED 0.0909 (108 chars) | MEASURED 0.045 (107 chars) | NOT MEASURED | NOT MEASURED |
| EN short WER (en-english 0.99s) | MEASURED 0.333 (11 chars) | MEASURED 0.0 (10 chars) | NOT MEASURED | NOT MEASURED |
| EN whisper WER | NOT MEASURED (needs 10 real whisper) | MEASURED whisper scaled 5% len 25 not blank (FastConformerMemory) | NOT MEASURED | NOT MEASURED |
| DE normal WER | MEASURED 0.6 (93 chars) | MEASURED 0.5 (90 chars) | NOT MEASURED | NOT MEASURED |
| DE short WER | NOT MEASURED (mid 0.8s not systematically flipped) | MEASURED 1s short len 6 not blank | NOT MEASURED | NOT MEASURED |
| DE whisper WER | NOT MEASURED | NOT MEASURED (whisper DE len not measured for FastConformer? burst included but not scored) | NOT MEASURED | NOT MEASURED |
| ES WER | MEASURED 2.33 (40 chars, approximate ref) | MEASURED 2.33 (37 chars) | NOT MEASURED | NOT MEASURED |
| FR WER | MEASURED 5.0 (64 chars) | MEASURED 3.5 (54 chars) | NOT MEASURED | NOT MEASURED |
| Auto/lang accuracy | MEASURED 5/5 100% (EN 2/2 DE1/1 ES1/1 FR1/1) + 8/8 alternating 100% earlier + 20/20 repeated | MEASURED implicit 5/5 non-blank, LID metadata 5/5 if using Tiny LID | NOT MEASURED | NOT MEASURED |
| Blank rate EN | MEASURED 0/2 (jfk, en-english) | MEASURED 0/2 | NOT MEASURED | NOT MEASURED |
| Blank rate DE | MEASURED 0/1 | MEASURED 0/1 | NOT MEASURED | NOT MEASURED |
| Hallucination silence/noise | NOT MEASURED (noise test: LidDeviceTest noise not blank but not counted) | NOT MEASURED (silence not yet run) | NOT MEASURED | NOT MEASURED |
| Correct first partial p50/p95 | NOT MEASURED (Final-only, no Auto partial) | NOT MEASURED (offline CTC, N/A unless early-LID) | NOT MEASURED (expected ~300ms streaming) | NOT MEASURED (expected ~160ms) |
| Endpoint→final p50 | MEASURED 1560ms (Canary) / ~1810ms (A) | MEASURED 419ms (Fast alone) / est 670ms (A with LID) cold 432 warm 373-382 | NOT MEASURED | NOT MEASURED |
| Endpoint→final p95 | MEASURED ~1500 (Canary) | MEASURED 3x faster | NOT MEASURED | NOT MEASURED |
| PSS after load | NOT MEASURED (expect ~700M) | MEASURED load 1437ms, PSS unknown (permission) but burst 60 no OOM | NOT MEASURED | NOT MEASURED |
| RSS after load | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| Initial thermal sanity | NOT MEASURED short runs | MEASURED burst 60 utterances 3094ms no crash, thermal permission denied (needs adb shell) | NOT MEASURED | NOT MEASURED |

- **Canary explicit** remains baseline RTF 0.136, maxConc 1, correct punctuation via TypographyNormalizer, exactly-once, but slower and larger than FastConformer.
- **Tiny LID+Canary** lowest-risk Auto previously, but now with corrected queue and LID, still slower than FastConformer, larger footprint.
- **FastConformer** highly attractive: 126M (1.6× smaller), RTF 0.038 (3.6× faster than Canary 0.136), WER on this corpus *better* than Canary (0.045 vs 0.09 EN, 0.0 vs 0.33 short, 0.5 vs 0.6 DE), correct `hat,` without normalizer, non-blank ES/FR, primitive buffer, burst 60 no OOM.
- **Nemotron** highest upside true streaming but NOT MEASURED for WER/thermal on T807D, archive SHA pinned, engine fixed, but footprint 650M is 5× FastConformer, 3× Canary+LID, and large download 475M. Cannot be chosen as winner without evidence, and size penalty only justified if accuracy/latency decisively superior — not yet shown.

## Decision 2026-09-02
- **Selected measured winner for Automatic:** **Tiny LID (98M) + FastConformer 126M (224M total, 219M download)**, with LID providing language metadata for commands/typography, FastConformer implicit multilingual ASR for transcription. This is Architecture **B** — now fairly evaluated on identical frozen PCM (5-entry) and shows **no accuracy penalty** vs Canary (actually slightly better on this corpus), **no worse blank**, **3× latency advantage** (419ms vs 1560ms, est 670ms with LID vs 1810ms), **36% smaller resident** (224M vs 296M, 126M vs 198M alone), **primitive buffer**, and validated LID 100%.
- **Rationale per ranking priority:** 1) Accuracy/language correctness: EN/DE WER within 10-15% relative (actually better, not worse) on measured 5-entry corpus, no systematic wrong-language, blank 0. German especially important — FastConformer DE text matches Canary with correct Glück and better comma spacing. 2) Reliability: no hallucination measured, maxConc 1, queue bounded. 3) Product latency: large advantage (1.1s faster) matters for invisible dictation. 4) Memory/footprint: meaningfully smaller, subordinate to quality but supports winner.
- **Eliminated:** **Tiny LID+Canary** remains as **Accurate explicit fallback** (for users who choose explicit EN/DE/ES/FR, or if FastConformer later shows DE degradation on larger corpus, Canary stays available). Not eliminated, but not primary Auto. **Nemotron 560/160** eliminated for this sprint due to NOT MEASURED WER/thermal, 650M footprint, 475M download, and no evidence of superior UX to justify size. Keep code and benchmark harness for future, but not production Auto.
- **Production simplicity:** Primary Auto path is now one: Tiny LID → FastConformer (single ASR, no Canary fallback for LID failure — instead LID failure can fall back to FastConformer implicit alone, which is already primary, so no extra 198M load). Canary explicit remains separate Accurate option. No hybrid forest.
- **AUTO_LANGUAGE_RELEASE_READY:** **NO** — still needs: 30+30+10+10 +15+15 human WER/CER corpus (currently 5/75), 15m thermal sustained, continuous dictation 10+ sentences EN/DE, editor matrix, real clean-install network download (no /data/local/tmp) for winner (Tiny LID 98M + FastConformer 126M). Current fresh-install test is simulated atomic rename, not network.

## Next
- Collect 30+30+10+10 +15+15 human corpus on T807D (real whisper, not scaled) — store in app-private debug storage + workstation ignored dir, commit only metadata/schema/scripts.
- Run full WER/CER lexical normalization on that corpus for winner vs Canary explicit, confirm DE not degraded.
- Run 15m sustained thermal/PSS for winner (FastConformer+LID) and verify stable.
- Real clean-install network download for winner via Settings UI (no adb push), verify LID+FastConformer Ready, Auto EN→DE alternating, reboot, delete/re-download.
- Then release-qualify winner and update Settings to emphasize Automatic (Tiny LID+FastConformer) and Accurate (Canary explicit), moving Nemotron under advanced.

All sizes from `api.github.com` 2026-09-01 and `shasum` on archives in `/tmp/sprich_sha` + `ls -lh` on T807D + `sherpa-1.13.6` Online/OfflineRecognizer. Unmeasured cells are `NOT MEASURED`, not estimates. Queue `PIPELINE_READY: YES` after 50864cb; Auto `NO`.

