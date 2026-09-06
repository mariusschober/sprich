# Sprich — Architecture (as implemented, 2026-09-06)

Scope: the S01–S06 text-integrity path. Capture/VAD/queue/provider internals are unchanged by this work; §5–8 of the plan remain NOT STARTED.

## Finalization pipeline (production)

```
transcript (raw, trimmed)
  → origin classification (LOCAL_FAST/LOCAL_CANARY → LOCAL, else REMOTE)
  → CommandPolicy(commandsEnabled, origin, handsFreeDestructive=false,
                   speakerLabelsPresent, allowInlineSingleWord=false)
  → SpokenEditingParser.parseTyped(text, resolvedLanguage, policy, AcousticBasis.UNKNOWN)
  → when (parsed):
      Editing    → PreparedFinalAction.DeleteLast(token)   // local-origin only; see below
      Formatting → PreparedFinalAction.Text(rendered)       // no cleanup round-trip
      Rejected   → PreparedFinalAction.Text("")             // zero mutation
      Text       → vocabulary.apply → relevantTerms (TermCanonical, cap 20)
                     → refinement plan (Off → deterministic; else LLM)
                     → RefinementValidator.validate → Accept ? candidate : deterministic fallback
                     → PreparedFinalAction.Text
  → mutation only with fresh session/field/connection/span proof at mutation time
```

Code: `app/src/main/java/com/sprich/app/input/ime/SprichIME.kt` (~line 1800),
`app/src/main/java/com/sprich/app/input/commands/SpokenEditingParser.kt` (`parseTyped`, line 101),
`.../input/commands/ParsedDictation.kt`.

## Authority rules

- Only `parseTyped` constructs `Formatting`/`Editing`, and only when: commands enabled, language Known (Unknown → generic typography normalization, no commands), exact whole-utterance match on the NFKC-folded key, and (destructive) `origin == LOCAL`.
- Literal `__*__` strings never acquire authority (any case/whitespace/Unicode variant stays text). Speaker-labelled output and quoted/code spans never become commands. Vocabulary and field context are data, never commands.
- Legacy `parse()` delegates to the typed core and maps `Editing → "__DELETE_LAST__"` for old callers; `isDeleteCommand` accepts only that exact string. `DeleteSentence` no longer exists anywhere in the grammar or `PreparedFinalAction` (S06: unprovable span).
- Remote-origin destructive phrases are confirmation-only text. `handsFreeDestructive=false` (no enabling UI) keeps even local destructive commands to the owned-span-proof path in `executeSpokenDeleteLast`.

## Refinement (S02/S03/S04)

- Modes (`RefinementMode.kt`): FAITHFUL (default automatic cleanup) and POLISHED (explicit opt-in); CORRECT→faithful, CLEAN_DICTATION→polished legacy aliases; OFF skips the LLM. New-user default flipped FAITHFUL in `Preferences.kt`.
- `RefinementValidator.validate(original, candidate, mode, protectedTerms): Accept | Reject(reason)`; every call counted in `ValidationStats` (`total`, `reject:<reason>`).
- Always protected: ordered `CriticalTokens` sequences (amounts fused with units, identifiers opaque, `1.000 ≠ 1,000`); email/URL/currency occurrences; negation presence and clause scope; modality (`may≠must`) and uncertainty markers; `TermCanonical`-matched protected terms. Explicit numeric self-corrections (`50 Euro, nein, 15 Euro → 15 Euro`) resolve before length bounds.
- Faithful additionally allows only its catalogue (punctuation/casing/spacing, fixed filler list, immediate tandem repetition, explicit self-corrections); anything else falls back to the deterministic transcript. Polished additionally requires topic overlap. Length bounds: candidate must be within 0.5×–(1.6×+24) of the resolved original (0.25× lower in Polished).
- Prompt hygiene (`DictationPrompt.kt`): fixed system instruction + JSON data blob (transcript, language, ≤20 terms, optional context). Output budget `(codepoints/2+96)` clamped 128–4096; validator rejects blank/oversized (>16,384 chars)/markup/commentary responses.

## Known wiring gaps (explicit, not claims)

- `AcousticBasis.UNKNOWN` at the production call site: symbol-only transcripts are suppressed as possible hallucinations until per-utterance VAD episode flags exist (P02).
- No absolute utterance deadline, no queue-depth/byte accounting, no per-stage attempt telemetry beyond `ValidationStats` (P03/P07 NOT STARTED).
