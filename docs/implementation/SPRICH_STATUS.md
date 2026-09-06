# Sprich — Implementation status (integrity sprint)

**Branch:** `agent/sprich-integrity-sprint`
**HEAD:** `20be03c74a3fac6c108c74c171a3216f49a17601` (== audit baseline; S01–S06 work uncommitted on top)
**Audit baseline:** `20be03c` on `main` (`Downloads/03_AUTONOMOUS_IMPLEMENTATION_PLAN.md`)
**Updated:** 2026-09-06 UTC · **Harness:** `OK (100 tests)` — `/tmp/sprich-harness/resume-verify.log`

## Finding status

| Audit IDs | Outcome | Evidence |
|---|---|---|
| S01 typed command authority | IMPLEMENTED, TESTED (host) | `ParsedDictation.kt` (new); `SpokenEditingParser.parseTyped`; `SprichIME` finalizer `when (parsed)`; `ParsedDictationAuthorityTest` 14/14 |
| S02 critical-number protection | IMPLEMENTED, TESTED (host) | `CriticalTokens.kt` (new, ordered amount+unit sequences); `CriticalMeaningTest` 18/18 |
| S03 bounded cleanup (Faithful/Polished) | IMPLEMENTED, TESTED (host) | `RefinementMode` FAITHFUL/POLISHED + legacy aliases; `RefinementValidator` + `ValidationStats`; `DictationPrompt` split instruction/data; `RefinementValidatorTest` 11/11, `RefinementCorpusTest` 1/1 |
| S04 term protection incl. Unicode | IMPLEMENTED, TESTED (host) | `TermCanonical.kt` (NFKC + case fold, no accent stripping, token-boundary); discovery and enforcement share `containsTerm`; `TermCanonicalTest` 8/8 |
| S05 grounded symbol handling | IMPLEMENTED, TESTED (host) | `AcousticBasis`; symbol-only transcripts rejected unless `SPEECH_PRESENT`; `SymbolFormattingGrammarTest` 11/11 |
| S06 formatting grammar | IMPLEMENTED, TESTED (host) | Longest-match, multiword-inline only, utterance-final singles, quoted/code spans literal; `DeleteSentence` removed from grammar; device-test expectations updated |
| P01–P07, U01–U05, E01–E04 | NOT STARTED | See Blocked gates |

## Tests run

- Host harness (kotlinc-direct, offline): **100/100 pass** — 14 authority + 11 symbol/grammar + 8 parser + 5 correction-safety + 9 unknown-language + 15 typography + 8 term-canonical + 18 critical-meaning + 11 validator + 1 corpus. Per-class `@Test` counts sum to 100; see `SPRICH_BENCHMARK_PROTOCOL.md` and `benchmark-results.json`.
- `./gradlew :app:testDebugUnitTest` (project gate): BLOCKED — offline dependency resolution failure (recorded this session; see Release report).
- Device tests (`PipelineCorrectnessDeviceTest`, updated S06 expectations): BLOCKED — no adb device (`adb` daemon fails to ACK; see Release report). Expectations updated but never executed.
- Provider/corpus/benchmark gates: NOT MEASURED (no credentials, no 1,000-utterance corpus, no hardware).

## Decisions

- `docs/implementation/` created: the plan names these paths and the repo had no `implementation/` dir (flat `docs/*.md` convention kept for existing docs).
- `handsFreeDestructive` defaults false — no UI enables it, so local destructive commands are confirmation-only until a hands-free policy with owned-span Undo ships (S01).
- `AcousticBasis.UNKNOWN` in production call path is conservative legacy behavior (symbol-only suppressed), explicitly marked as the P02 wiring point, not a claim of VAD integration.
- APKs under `app/build/outputs/` are stale (2026-09-03, pre-S01) and the release APK is unsigned: **no installable deliverable from this work yet**.

## Artifact paths

- Source diff: `git -C ~/Projects/Sprich diff --stat` (10 modified files) + 7 untracked (`ParsedDictation.kt`, `CriticalTokens.kt`, `TermCanonical.kt`, 4 test files).
- Docs: `docs/implementation/SPRICH_{STATUS,ARCHITECTURE,UX_SPEC,BENCHMARK_PROTOCOL,RELEASE_REPORT}.md`, `benchmark-results.json`.
- Logs: `/tmp/sprich-harness/resume-verify.log`, `/tmp/sprich-harness/last-run.log`.
