# Sprich — Benchmark protocol and current results (2026-09-06)

## Method (host regression gate, Gate A)

Full Gradle is unavailable offline (see Release report), so unit tests compile and run via a kotlinc-direct harness: `/tmp/sprich-harness/run.sh` compiles `app/src/main` production sources used by the tests plus the ten listed test files against cached `kotlin-stdlib`, then runs JUnit 4.13.2 with `TEST_CLASSES` set to the ten classes below. Command and raw log: `/tmp/sprich-harness/resume-verify.log`. Machine-readable: `benchmark-results.json` (this directory).

## Results

`OK (100 tests)`, ~0.08 s. Per-class `@Test` counts:

| Test class | Tests |
|---|---|
| `ParsedDictationAuthorityTest` (new, S01) | 14 |
| `SymbolFormattingGrammarTest` (new, S05/S06) | 11 |
| `SpokenEditingParserTest` (updated S06) | 8 |
| `SpokenCorrectionSafetyTest` | 5 |
| `UnknownLanguagePostProcessingTest` (updated) | 9 |
| `TypographyNormalizerTest` (updated) | 15 |
| `TermCanonicalTest` (new, S04) | 8 |
| `CriticalMeaningTest` (new, S02/S03) | 18 |
| `RefinementValidatorTest` | 11 |
| `RefinementCorpusTest` | 1 |

Coverage notes: multilingual critical-meaning cases (mg→g, moved negation, may/must, uncertainty removal, recipient change, before/after reversal, assistant-commentary rejection) across EN/DE/ES/FR; authority cases (literal sentinels, case/whitespace/Unicode variants, Unknown language, commands-off, speaker mode, remote origin, cleanup-output-poses-as-command); symbol/noise negatives (silence, taps, fan/room noise, repeated punctuation, emoji+variation selectors).

## NOT MEASURED (explicit)

No 1,000-utterance held-out corpus, no speakers/recordings, no devices (low-tier/flagship), no latency/WER/correction-effort numbers, no Wispr comparison. The E01 quality/latency gates and the §10 corpus/device/editor matrix are entirely open. `ValidationStats` counters exist in code so a future field evaluation can measure conservative-rejection cost; no such data exists yet.
