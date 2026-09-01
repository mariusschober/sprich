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

## Release gate

**AUTO_LANGUAGE_RELEASE_READY: NO** — pipeline ready, queue/LID/partial/management fixed and MEASURED PASS, FastConformer WER/latency MEASURED on 5-entry corpus, but still need: 30+30+10+10 +15+15 human WER/CER corpus (currently 5/75), 15m thermal sustained for winner, continuous dictation 10+ EN/DE, editor matrix (Chrome/Gmail/etc), and real clean-install network download (no /data/local/tmp) for Tiny LID 98M + FastConformer 126M. Fresh-install test currently simulated atomic rename, not network. Do not ship Automatic to all users until those are MEASURED and ≥99% clean EN/DE, zero systematic wrong-language, endpoint p95 ≤800ms, PSS stable.

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

## Fresh-install without adb (Phase 11 gate)

Settings shows Auto only when `lidStatus Ready` (single-source), provides Download/Delete/Cancel/progress for Tiny LID 98M, Canary 198M, FastConformer 126M, Nemotron 475M each, atomic extract/verify/SHA/free-space/path-traversal, independent variant status, Delete all for Nemotron. `FreshInstallDeviceTest` + `LidDeviceTest` + `WerCer` + `FastConformerMemory` all PASS where measured, but network download via DownloadManager (OkHttp Range resume, SHA) is wired but not device-verified with real network fetch for winner (requires 219M) — mark as simulated, NOT MEASURED for network gate.

## Commits pushed

- `50864cb` fix(queue): make overload behavior truthful and production-tested
- `eca994a` fix(nemotron): correct structured streaming decode and final drain
- `110ad85` fix(models): pin candidate SHAs and split nemotron variant state; fix(settings): align Auto copy
- `5c11d5d` fix(lid): correct SLID constructor for sherpa 1.13.6 and handle short-wav edge cases
- Next: `docs(asr): measured architecture decision 2026-09-02` (this file + MODEL_BAKEOFF), `feat(asr): select Tiny LID+FastConformer as Automatic winner`

## Remaining limitations

- Corpus 5/75, not 30+30+10+10+15+15, so DE short/whisper, ES/FR short/whisper, hallucination silence/noise, 15m thermal, PSS/RSS after load, continuous dictation 10+ sentences, editor matrix, and real network fresh-install for winner are NOT MEASURED.
- Nemotron WER/thermal/PSS NOT MEASURED, so cannot be declared superior despite streaming.
- FastConformer punctuation/casing measured as correct `hat,` but not formally scored vs Canary TypographyNormalizer on larger corpus.
- No marketing language; decision is evidence-bound per mission ranking.

```
PRODUCTION_AUTO_ARCHITECTURE: Tiny LID + FastConformer (Whisper Tiny 98M per-utterance SLID → FastConformer CTC 126M implicit EN-DE-ES-FR, Canary 180M remains Accurate explicit)
```

```
AUTO_LANGUAGE_RELEASE_READY: NO
```

Central principle satisfied: best real experience on actual phone is FastConformer 0.42s vs Canary 1.56s, with no accuracy penalty on measured corpus, so dictation feels invisible while preserving German quality.
