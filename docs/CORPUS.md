# CORPUS — Decision-grade real speech for Sprich Auto (2026-09-02)

This corpus is the **same verified real-speech frozen PCM** used for all 4 architectures. Private recordings are **not committed** — only metadata/schema/scripts are committed. Audio stored in app-private debug storage (`filesDir/debug_corpus/`) and workstation `local_benchmark/` ignored by git.

## Required counts (per mission)

- EN normal: 30 (1–12s, prose/questions/punct/dates/numbers/names/addresses/technical, 1–3s +3–6s +6–12s mix)
- EN short: 10 (0.4–1.5s, e.g., "Yes." "No." "Tomorrow." "At nine." "Send it." "That works.")
- EN whisper: 10 actual whispered (not amplitude-scaled)
- DE normal: 30 (conversational, compounds, umlauts ÄÖÜ, ß, proper nouns, numbers, time/date, appointments, punctuation, filler, short/long clauses, e.g., "Ich habe morgen um neun Uhr einen Termin in München." "Die Donaudampfschifffahrtsgesellschaft …")
- DE short: 10 real mic
- DE whisper: 10 actual whispered
- ES normal: 15, FR normal: 15, plus optional 5 short +5 whisper each

Current measured: 5/75 (jfk 11s EN normal, en-english 0.99s EN normal/short, de-german 6s DE normal, es-spanish 2.4s ES normal, fr-french 3.5s FR normal) — all real device wavs + jfk. The rest NOT MEASURED pending human collection.

## Gold transcript quality

Per file stored as JSON:
```
{
  "id": "en-001",
  "language": "en",
  "referenceTranscript": "I love you.",
  "speaker": "marius-t807d",
  "condition": "normal",
  "durationMs": 990,
  "type": "normal|short|whisper",
  "notes": "real mic, 16k mono, no post"
}
```
Every golden transcript manually verified, not from model output. WER scoring uses lexical normalization: case policy (RAW keeps case, TYPOGRAPHY normalizes), punctuation policy (RAW counts punctuation as token, TYPOGRAPHY strips), lexical words preserved, number normalization explicit (e.g., "nine" vs "9" not conflated), umlaut preserved (Glück vs Glueck is error if reference is Glück).

## Storage

- Private audio: `/data/data/com.sprich.app.debug/files/debug_corpus/` (app-private, debug builds only) and `/Users/schober/Projects/Sprich/local_benchmark/` (workstation, gitignored).
- Committed: `docs/CORPUS.md` (this file), `app/src/test/...` scoring harness, `scripts/` corpus helpers, not private wav.

## Scoring

- Report WER/CER separately for EN normal, EN short, EN whisper, DE normal, DE short, DE whisper, ES normal, FR normal — do not merge EN+DE average.
- Also report language accuracy (LID and Nemotron auto), blank rate (critical for DE), hallucination (silence/noise).
- Latency: endpoint→final p50/p95, first correct partial if streaming, LID latency, RTF.

## Current status 2026-09-02

- EN normal: 1 (jfk) +1 (en-english) =2 measured, 28 remaining NOT MEASURED
- EN short: 1 measured (en-english as short), 9 remaining
- EN whisper: 0 measured (scaled test only, not real), 10 remaining
- DE normal: 1 measured (de-german), 29 remaining
- DE short: 0, 10 remaining
- DE whisper: 0,10 remaining
- ES: 1 measured, 14 remaining
- FR: 1 measured, 14 remaining

All decision table cells for these 5 are MEASURED; rest are NOT MEASURED. Decision between Tiny+Canary vs Tiny+FastConformer is based on these 5, which show FastConformer within 10-15% relative (actually better). Full 30+ required before release.
