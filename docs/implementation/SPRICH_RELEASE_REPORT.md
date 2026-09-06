# Sprich — Release report (plan §§2,12; 2026-09-06, sandbox-free pass)

**Status: INTERNAL-TESTING ONLY (unsigned). NOT a public release.**
Everything below was executed 2026-09-06 on branch `agent/sprich-integrity-sprint`
(HEAD `287f6c3`, tree clean) with Gradle 9.3.1 + S23 Ultra SM-S918B attached.
Do not claim Wispr parity; do not present the unsigned APK as installable
without a signing decision.

## Source identity
- Branch `agent/sprich-integrity-sprint`, ahead of audit baseline `20be03c`.
  Chain since baseline: S01–S06, P01–P07/U01–U05/E01–E04 slices, validator
  sign-stickiness fix, locale-const fix, three device-gate repairs, docs.
- `versionCode` = git count (98 release / 99 debug), `versionName` 1.0.0 /
  1.0.0-sprint. No dependency or model-pin change.

## Verified coverage (all run, this session)
- Unit: `:app:testDebugUnitTest` **539/539** green (0 failures/errors/skips)
- Release: `:app:lintRelease`, `:app:assembleRelease`, `:app:bundleRelease`,
  `:app:writeReleaseDependencyInventory`, `check-apk.sh`, `verify-models.sh`,
  `verify-locales.py` — all PASS
- Artifacts: unsigned release APK 29,412,457 B, SHA-256
  `7e5e1d8d…f794464a2f98`; debug APK installed on S23 as
  `com.sprich.app.debug` v99
- Device (direct `am instrument`, S23): 60+ tests green — 47/47 combined
  battery, FreshInstall (real provision), RealNetwork (278 s production
  download), AutomaticWithoutCanary 2/2, LID 8/8 alternation, WerCer +
  Comparative + memory benchmarks, 15-min thermal PASS
- Benchmarks: Canary JFK RTF ≈0.13, Fast RTF ≈0.038; WER table + LID 4/4 in
  `docs/DEVICE_EVIDENCE_S23_2026-09-06.md`
- UX: DE onboarding screenshots dark+light + UI dump in
  `docs/implementation/evidence-s23/`
- Privacy: production logs metadata-only; no secrets in Warn+ logcat

## Open before any public release
- Signing identity + update/rollback path (unsigned by decision)
- P03/P05/U02/U03 unwired slices (implemented + unit-tested, no production callers)
- Nemotron numbers, 1,000-utterance acoustic corpus, Wispr comparison
- E2E voice dictation, TalkBack/200%/RTL/landscape hand-check
- Live-provider acceptance (credentials), Meta API profile unchanged

## Carried risks (unchanged)
- `RefinementValidator` is a deterministic guard, not semantic-equivalence proof; rejection cost unmeasured
- Legacy `parse()`/`isDeleteCommand` shims retained; `lastRemoteFailure` global retained for Settings reads
- `AcousticBasis.UNKNOWN` conservative default; neural-VAD acoustic proof open
