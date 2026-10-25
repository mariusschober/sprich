# Sprich — UX spec deltas (integrity sprint, 2026-09-06)

Only behavior this work changes is listed under Implemented. Everything else in plan §9 (voice-bar layout, first-run, correction sheet, recovery UI, screenshots, TalkBack pass) is NOT IMPLEMENTED — no device was available and no UI code was touched.

## Implemented (user-visible)

- **Default cleanup is Faithful.** New users with cleanup on get conservative automatic cleanup (punctuation/casing/spacing + validated filler/repetition/self-correction removal). Broader rephrasing requires explicitly choosing Polished. (`Preferences.kt`, `RefinementMode.kt`)
- **Medial single-word punctuation stays literal.** Saying “Hello comma world” now inserts exactly that text; utterance-final (“Hello period” → “Hello.”) and multiword (“question mark”, “new paragraph”) commands still convert, in EN/DE/ES/FR grammars. Quoted/code spans are always literal.
- **No sentence deletion by voice.** The grammar offers only whole-utterance delete-last-owned; there is no spoken “delete sentence” that silently does nothing.
- **Symbol-only results usually produce nothing.** Standalone punctuation/emoji without a validated speech episode is suppressed (no-speech-hallucination guard); with a real episode, legitimate symbols (“?”, “...”) insert as text. Silence still yields no text.
- **Fallback is honest.** Rejected or timed-out cleanup inserts the faithful deterministic transcript; nothing is rewritten after commit.

## Not implemented (plan §9, explicit)

U01 first-run flow, U02 correction sheet with “Remember this spelling”, U03 recovery surface, U04/U05 voice-bar states/copy/tokens, visual-token audit, screenshots (light/dark, widths, landscape, 200% font, RTL), TalkBack/reduced-motion verification. No screenshots exist for this work (no device). Visual tokens in §9.1 were not audited against code.
