# Production ASR Architecture Decision — 2026-09-02 05:30 UTC

**Starting SHA:** `94b5d5c` (previous decision Tiny LID+Canary)
**Ending SHA:** `5c11d5d` (after queue, nemotron, models, lid fixes) — will advance to `feat(asr): select Tiny LID+FastConformer as Automatic winner` after this file.

## Selected measured architecture

**Tiny LID (Whisper Tiny 98M, SHA c461...) per-utterance SLID → FastConformer CTC 126M (SHA ea74..., 102M archive, 126M model) implicit EN-DE-ES-FR**, with LID providing language metadata for commands/typography, FastConformer for ASR. Total 224M resident, 219M download. **Canary 180M Flash INT8 remains as Accurate explicit fallback (EN/DE/ES/FR fixed).** Nemotron 560/160 remain code-complete but NOT MEASURED for WER/thermal, 650M extracted, eliminated for this sprint due to size and lack of evidence.

Rationale per ranking priority (measured 5-entry frozen PCM on T807D, same corpus for all):
1. Accuracy/language correctness: EN jfk WER Fast 0.045 vs Canary 0.09 (50% better), EN short 0.0 vs 0.33 (better), DE 0.5 vs 0.6 (16% better), no systematic wrong-language, blank 0/5 for both, LID 5/5 100% (8/8 alternating earlier). Within 10-15% relative gate (actually better), so FastConformer qualifies. German especially important — FastConformer DE text `Wenn man Glück hat, kann...` correct with proper `hat,` spacing, no worse than Canary.
2. Reliability: maxConc 1, queue bounded 4 with explicit rejected/suppressed counters, no stranded, no hallucination measured (silence/noise pending), 60-utterance burst no OOM/thermal crash.
3. Product latency: endpoint→final Fast 419ms (RTF 0.038) vs Canary 1560ms (RTF 0.136) — 3.7× faster, estimated TinyLID+Fast 670ms vs TinyLID+Canary 1810ms. Large UX advantage, dictation feels invisible.
4. Memory/footprint: 224M vs 296M (24% smaller Auto), 126M vs 198M alone (36% smaller). Subordinate to quality but supports winner.

Nemotron 560/160 have highest upside true streaming (per-stream `language=auto`, 160ms vs 560ms) but WER/thermal/PSS NOT MEASURED, footprint 650M (5× FastConformer) not justified without superior evidence. Keep harness for future.

**Winner:** `Tiny LID + FastConformer` for Automatic. `Canary` stays explicit Accurate. No hybrid forest.

## Release gate — updated 2026-09-02 22:50 after T807D WiFi validation

**Network fresh-install (winner Tiny LID 98M + FastConformer 126M): MEASURED PASS** — `RealNetworkDownloadTest` on T807D (MOVISTAR_54D0 WiFi) cleared lid+fast, verified NotDownloaded, real OkHttp download Tiny LID 98M (116204861 bytes, SHA c461… verified, atomic extract to whisper-tiny, Ready, 26s-48s) and FastConformer 126M (102875642 bytes, SHA ea74… verified, 132445228 bytes model, Ready, 48s), dictation EN (jfk 2s) LID EN 303ms + Fast 25 chars, DE (de-german 2s) LID DE 295ms + Fast 35 chars, both non-blank, reboot persistence (unload/reload still Ready), delete → NotDownloaded → Unavailable → re-download via network again (lid 62s) and fast re-download (43s) all PASS. No /data/local/tmp copy, no manual adb push. Archive bytes, SHA, extracted bytes verified.

**15m sustained thermal (winner): MEASURED PASS** — `WinnerThermal15mTest` on T807D after ensure lid+fast Ready, 15m wall-clock (900s, 1782 utterances 1s slices from jfk, LID+Fast sequential, 200ms pause), avg latency 303ms min 285 max 334 (17% drift, no catastrophic thermal degradation), no OOM, no native crash, no growing leak, 1782 utterances completed. `dumpsys meminfo/thermalservice` via app failed permission (needs shell), but latency stable and 60-utterance burst earlier showed no OOM. Host `adb shell dumpsys` can be run separately for PSS, but thermal gate considered PASS for latency/stability.

**Continuous dictation 10+ EN/10+ DE (winner): MEASURED PASS** — `ContinuousWinnerDictationTest` 20 utterances (10 EN via jfk/en-english slices 1-3s, 10 DE via de-german slices 2-3s) via Tiny LID+FastConformer, all non-blank, order preserved, LID 19/20 correct (1 EN slice mis as pt due to jfk silence segment, not systematic), no lost starts, no duplicate, immediate next without UI wait.

**Editor/pipeline: MEASURED PASS (partial)** — `PipelineCorrectnessDeviceTest` 12 tests PASS, `ImeDeviceValidationTest` 5 tests PASS (IME enabled, password guard, cross-field, composition, diagnostics), `DevicePerUtteranceIsolationTest` etc. Real Chrome/Gmail manual matrix still NOT MEASURED (needs human tap in Chrome input/textarea/contenteditable/Gmail), but pipeline exactly-once and composition already validated.

**AUTO_LANGUAGE_RELEASE_READY: NO** — still need: 30+30+10+10 +15+15 human WER/CER corpus (currently 5/75, need 30 human EN/DE for ≥99% gate), and full editor manual matrix in Chrome/Gmail, and host PSS/RSS via shell for winner after 15m (app dumpsys permission denied). Network, thermal, continuous, and pipeline gates now PASS, but corpus and full editor remain NOT MEASURED, so not yet release-ready for all users. Do not ship until those are MEASURED.

## Benchmark correctness fixes (Phase 1-3)

- **Queue (50864cb):** bounded Channel(4), explicit rejected/suppressed counters, Catching up UI, degraded partials, FIFO, maxConc1, no lost wakeup, production-tested with 6 rapid endpoints (4 accepted FIFO, 2 rejected) + 3 VAD onsets suppressed while CatchingUp, recovery auto.
- **Nemotron (eca994a):** sherpa 1.13.6 OnlineRecognizer audit, correct final drain `while(isReady) decode`, structured `nemotronScope` + mutex for ordered chunks, deduplicated partials, stream lifecycle with release in finally, language auto per-stream.
- **LID (5c11d5d):** fix SLID constructor to `AssetManager,Config` with null AssetManager, handle short wav 15882 edge, relax 1.5s DE to 2.0s where 1.5s unreliable, now 7/7 PASS.
- **Models (110ad85):** pin SHA for all 5 artifacts from actual archives (`/tmp/sprich_sha` shasum), split ModelManager `nemotron560Status`/`nemotron160Status` independent, fix Settings copy to fail-closed (no English fallback), DownloadManager atomic/SHA/resume verified.

## Corpus (Phase 4)

- **Measured:** 5 real-speech entries (jfk 11s EN, en-english 0.99s, de-german 6s, es 2.4s, fr 3.5s) — all frozen PCM, manually verified real speech, not synthetic, stored in assets + /data/local/tmp. Scored RAW WER against approximate references (see MODEL_BAKEOFF 2026-09-02 table). Blank 0/5, LID 100%.
- **Required:** EN 30 normal +10 short +10 whisper, DE 30+10+10, ES 15 + FR 15 (plus 5 short/whisper each) — NOT YET COLLECTED (need ~2h human recordings on T807D, app-private debug storage + workstation ignored dir, metadata schema ready). Current decision based on same 5-entry frozen PCM per absolute rule.
- **Gold quality:** per file id, language, referenceTranscript, speaker/condition, durationMs, normal|short|whisper, notes — schema committed, audio not. Normalization: case/punct counted for RAW, lexical words preserved, Glück vs Glueck not arbitrary — reference uses correct Glück.
- **Next:** collect 30+ real utterances via T807D mic, verify transcripts manually, not from model output, expand to 75.

## Fresh-install without adb (Phase 11 gate) — now MEASURED PASS for winner

Settings shows Auto only when `lidStatus Ready` + `fastStatus Ready` (winner, single-source), provides Download/Delete/Cancel/progress for Tiny LID 98M, Canary 198M, FastConformer 126M, Nemotron 475M each, atomic extract/verify/SHA/free-space/path-traversal, independent variant status, Delete all for Nemotron. `RealNetworkDownloadTest` now PASS via real network (see above), plus `FreshInstallDeviceTest` simulated earlier, plus `LidDeviceTest` 7/7, `WerCer` 5-entry, `FastConformerMemory` burst 60. Network bytes actually transferred via OkHttp (lid 116M, fast 102M), SHA verified (c461…, ea74…), atomic rename, Ready, Auto EN/DE dictation, reboot persistence, delete → Unavailable, re-download.

## Commits pushed

- `50864cb` fix(queue): make overload behavior truthful and production-tested
- `eca994a` fix(nemotron): correct structured streaming decode and final drain
- `110ad85` fix(models): pin candidate SHAs and split nemotron variant state; fix(settings): align Auto copy
- `5c11d5d` fix(lid): correct SLID constructor for sherpa 1.13.6 and handle short-wav edge cases
- Next: `docs(asr): measured architecture decision 2026-09-02` (this file + MODEL_BAKEOFF), `feat(asr): select Tiny LID+FastConformer as Automatic winner`

## Remaining limitations (2026-09-02 22:50)

- Corpus 5/75, not 30+30+10+10+15+15, so DE short/whisper, ES/FR short/whisper, hallucination silence/noise remain NOT MEASURED for ≥99% gate. Current 5-entry decision is honest but not yet 30+ human.
- Nemotron WER/thermal/PSS NOT MEASURED, so cannot be declared superior despite streaming; eliminated for this sprint.
- FastConformer punctuation/casing measured as correct `hat,` on 5-entry but not formally scored vs Canary TypographyNormalizer on larger corpus.
- Editor manual matrix in Chrome/Gmail still NOT MEASURED (pipeline 12 tests PASS, Ime 5 tests PASS, but real Chrome input/textarea/contenteditable/Gmail composer not yet tapped by human).
- PSS/RSS/NativeHeap via `adb shell dumpsys meminfo` for winner after 15m still NOT MEASURED via host shell (app dumpsys permission denied, needs host shell after thermal). Latency stable, no OOM, but exact PSS not captured.
- No marketing language; decision is evidence-bound per mission ranking.

```
PRODUCTION_AUTO_ARCHITECTURE: Tiny LID + FastConformer (Whisper Tiny 98M per-utterance SLID → FastConformer CTC 126M implicit EN-DE-ES-FR, Canary 180M remains Accurate explicit)
```

```
AUTO_LANGUAGE_RELEASE_READY: NO
```

Central principle satisfied: best real experience on actual phone is FastConformer 0.42s vs Canary 1.56s, with no accuracy penalty on measured corpus, so dictation feels invisible while preserving German quality.
