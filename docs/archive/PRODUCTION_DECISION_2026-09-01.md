# Production ASR Architecture Decision — 2026-09-01 22:00 UTC

**Starting SHA:** `bc5865045809d31ed643fbb5490945b26b30e81d` (pipeline PIPELINE_READY: YES after 6ffc107+e3bf958+7d034db)
**Ending SHA:** `3b88604` (docs evidence-bound) — will advance to `feat(asr)` after this file.

## Selected measured architecture

**Tiny LID (Whisper Tiny per-utterance SLID) → Canary 180M Flash INT8**, Final-only (no wrong-language partial), validated FastConformer fallback for Unsupported/Failed. This is the same code already in `SprichIME` (`WhisperLidEngine` per `PendingUtterance` before `transcribeSnapshot`, `ModelManager.isWhisperTinyReady()` single source, `Channel<PendingUtterance>` actor).

Rationale: lowest-risk, preserves Canary's already-working pipeline (RTF 0.136, correct `hat,` punctuation, exactly-once), adds 98M LID with ~100ms, no 30s cache, fail-closed (never EN fallback), `stream.release()` in finally, native memory bounded, 8/8 alternating correct on T807D. FastConformer 126M 3× faster remains attractive but offline CTC not streaming and formal DE WER not measured vs Canary. Nemotron true streaming Auto (160/560) highest upside but WER/thermal/memory not yet measured on T807D (archive SHA pinned, engine real, benchmark harness created).

**Canary explicit** remains `Accurate` fallback.

## Release gate

**AUTO_LANGUAGE_RELEASE_READY: NO** — pipeline ready, LID 8/8 +20/20 + rapid <1s + whisper correct, queue stress PASS, fresh-install atomic install PASS (simulated), but full 30+30+10+10 +15+15 human WER/CER corpus, 15m thermal, and network fresh-install download (no /data/local/tmp) remain NOT MEASURED. Do not ship Automatic to all users until those are MEASURED and ≥99% clean EN/DE, zero systematic wrong-language, endpoint p95 ≤800ms, PSS stable.

## Fresh-install without adb

`Settings` shows Auto only when `lidStatus Ready` (single source), provides Download/Delete/Cancel/progress for Tiny LID 98M, Canary 198M, FastConformer 126M, Nemotron 475M each, atomic extract/verify, free-space/path-traversal guard. `FreshInstallDeviceTest` clears data, deletes all models, verifies Auto unavailable, simulates atomic re-install via `filesDir/*.tmp`→`rename`, verifies LID EN/DE alternating same-field, delete→Unavailable→re-download PASS. Real network download via `DownloadManager` (OkHttp Range resume, SHA pin) is wired but not yet device-verified with 116M network fetch (requires 475M for Nemotron) — mark as simulated, not yet network-measured.

