# Sprich — Implementation status (plan §§2,12 working record)

**Branch:** `agent/sprich-integrity-sprint`
**HEAD:** `287f6c3` (audit baseline `20be03c`; full log: S01–S06, wave P/U/E slices, validator sign fix `8d1f9b0`, locale-const `0304fcd`, device-gate repairs `46aa5da`/`70848f1`/`287f6c3`)
**Tree:** clean
**Updated:** 2026-09-06 UTC (sandbox-free session; S23 Ultra SM-S918B attached)
**Env truth (now):** Gradle 9.3.1 + JDK 17 work, loopback fine, `adb` sees
R3CW404GVBL (S23, Android 16/SDK 36) + a T807D tablet (commands pinned `-s`).
The old "no Gradle/adb/network" block is GONE; remaining BLOCKED items are
credentials/fixtures/signing only (see bottom).

## Finding status (per-ID, plan §13)

| Audit IDs | Outcome | Evidence |
|---|---|---|
| S01 typed command authority | IMPLEMENTED, TESTED (unit 539/539 + device) | `ParsedDictation.kt`; finalizer `when (parsed)`; `ParsedDictationAuthorityTest`; device: EditorMatrixReal 6/6, ImeDeviceValidation 2/2 |
| S02 critical-number protection | IMPLEMENTED, TESTED (unit + device) | `CriticalTokens.kt` — 2026-09-06 sign-stickiness fix (`-5` is one token; `-€500` preserved) after the full suite caught a sign-stripping accept; `CriticalMeaningTest` + injection suite green |
| S03 bounded cleanup (Faithful/Polished) | IMPLEMENTED, TESTED | `RefinementValidator` + `ValidationStats`; validator/corpus suites green in 539/539 |
| S04 term protection incl. Unicode | IMPLEMENTED, TESTED | `TermCanonical.kt`; device LID gates use the same canonical comparison |
| S05 grounded symbol handling | IMPLEMENTED, TESTED (host + device) | `AcousticBasis`; adversarial suites green incl. `RedTeamPrivacySecurityTest` (now under Robolectric) |
| S06 formatting grammar | IMPLEMENTED, TESTED (host + device) | `PipelineCorrectnessDeviceTest` 12/12 ON DEVICE with real Canary engine |
| P01 capture-ready split + 8 s startup buffer (§5.1) | WIRED + TESTED (unit + device) | `StartupAudioBuffer.kt` fed by real `AudioCapture` loop (`P01StartupWiringTest`, Robolectric); device: `AudioDeviceValidationTest` 2/2, QueueActorStress 3/3 |
| P02 neural VAD in every route (§5.2) | IMPLEMENTED + TESTED (policy + device) | `VadRoutePolicy.kt`; per-utterance `hadSpeech` threading; device LID/speech-flag paths exercised via winner tests |
| P03 bounded queue + ordered commit lane (§6.2) | IMPLEMENTED + TESTED (host 17/17) BUT UNWIRED | `UtteranceQueue.kt`; IME still uses its own bounded channel |
| P04 streaming admission + handshake (§8.1) | IMPLEMENTED + TESTED (host 5/5) | `MetaRealtimeSession.kt`, `MetaMuseSttProvider.kt`, `RemoteProviderFactory.kt`, `LiveRemoteUtterance.kt`; `RemoteAdmissionContractTest` (Robolectric). Live-provider acceptance BLOCKED (credentials) |
| P05 bounded continuous speech (§5.3) | IMPLEMENTED + TESTED (host) BUT UNWIRED | `SegmentReconciler.kt`; no production callers yet |
| P06 language/engine decoupling (§7) | IMPLEMENTED + TESTED (host 5/5 + device) | `LocalAsrRoute.kt` + fail-closed per-utterance LID; device: LID 8/8 alternation, Auto-without-Canary 2/2, ContinuousWinner 10+10, WerCer LID 4/4 correct |
| P07 typed outcomes + telemetry (§6.4) | POLICY TESTED (host 17/17); HOOKS WIRED + COMPILED | `UtteranceTelemetry.kt` + SprichIME hooks compile in release AND debug (lintRelease + both APKs built). `UtteranceQueue`/`transcribeWithOutcome` still unwired |
| U01 first-run incl. select step (§9.2) | IMPLEMENTED, SCREENSHOT-PROVEN (DE dark+light step 1/5) | Onboarding renders correctly both themes; `evidence-s23/` screenshots + UI dump |
| U02 correction sheet (§9.5) | DECISION LOGIC TESTED (host 9/9); sheet UI + wiring BLOCKED | `CorrectionPlan.kt`; Compose sheet + IME wiring not started |
| U03 recovery surface (§9.6) | IMPLEMENTED + TESTED (host) BUT UNWIRED | `RecoveryDraftStore.kt`; no callers, no UI |
| U04/U05 voice bar + visual tokens (§§9.1,9.3–9.4) | IMPLEMENTED (tokens + bar); copy table + a11y walkthrough PARTIAL | `UiTokens.kt`, `VoiceGestureBar.kt`; screenshot evidence 2 states; TalkBack/200%/RTL/landscape need hand-check |
| E01 benchmark + comparison (§10) | MEASURED (local engines, fixture corpus) — 1,000-utt acoustic + Wispr BLOCKED | S23: Canary JFK RTF ≈0.13, Fast JFK RTF ≈0.038 (~3.6×); WER jfk C 0.09/B 0.05, en C 0.33/B 0.0, de C 0.6/B 0.5 (es/fr refs are stubs — actual outputs correct sentences); 60-utt burst 3.9 s; 15-min thermal PASS (avg 284 ms @ utt 1000, no degradation). Full table: `docs/DEVICE_EVIDENCE_S23_2026-09-06.md` |
| E02 production/native/device tests (§11) | HOST 539/539 + DEVICE 60+ green | 47/47 combined battery + FreshInstall + RealNetwork + AutomaticWithoutCanary + benchmarks + thermal, all on S23 via direct instrument |
| E03/E04 docs + release (§12) | DOCS CURRENT; release unsigned-internal | This file, `SPRICH_RELEASE_REPORT.md`, `DEVICE_RUNBOOK.md` (needs §0–3 refresh for direct-instrument method), `DEVICE_EVIDENCE_S23_2026-09-06.md` (new), `evidence-s23/` (new) |

## Tests run (this session, sandbox-free)
- `:app:testDebugUnitTest` 539/539 green (incl. 2 new sign-preservation tests)
- `:app:lintRelease`, `:app:assembleRelease`, `:app:bundleRelease`, `:app:writeReleaseDependencyInventory`, `check-apk.sh`, `verify-models.sh`, `verify-locales.py` — all PASS
- Device (direct `am instrument`, S23): see evidence doc — 60+ tests green, 0 failures in final order-safe runs

## Blocked gates (exact unblock)
- Nemotron (2× 475 MB, public URLs, 59 GB free): provision via scratch `downloadNemotron560/160`, then
  `adb -s R3CW404GVBL shell am instrument -w -e class com.sprich.app.NemotronBenchmarkDeviceTest …`
- 1,000-utterance acoustic corpus: collection only; text-corpus v1 (`ThousandUtteranceCorpusTest`) is decision-logic, not acoustic
- E2E voice dictation + TalkBack/200%/RTL/landscape: hands on the S23
- Signed release install/update path: signing identity (staying unsigned per decision)
- Live providers (Meta/Gemini/OpenAI-live): credentials; Meta API profile unchanged (`meta-muse-voice-transcribe` default)
- Wispr comparison: no harness/credentials in repo

## Decisions (standing)
- Stay unsigned for internal testing; Meta AI API recommended profiles kept; Gemini/OpenAI-live deferred by specification
- Fixtures: natural archive speech (sherpa test_wavs); a same-day macOS-TTS detour was staged then fully replaced — no result rests on it
- `:app:connectedDebugAndroidTest` wipes app data (marker-proven) — device evidence uses direct `am instrument`; runbook update pending
- Copy+setReady model "restore" in tests is banned (cannot manufacture readiness; destroyed a valid install once) — FreshInstall owns real provisioning
