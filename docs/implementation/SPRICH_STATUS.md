# Sprich — Implementation status (plan §§2,12 working record)

**Branch:** `agent/sprich-integrity-sprint`
**HEAD:** `d0653d5` (U03 decode-preservation + policy test + E2E evidence; prior: P05 `9a1a4a7`, P07 `8834f41`, P03 `13ef290`, U02 `ea7a9e9`)
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
| P03 bounded queue + ordered commit lane (§6.2) | WIRED + TESTED (host 23/23 incl. 6 new Gate C; IME admit/shed/done/failed/cleanup/expire/cancel/finish verified in code) | `UtteranceQueue.kt` + `FinalizationClaims.kt` (bounded exactly-once claim set, transplanted from IME); IME admits at enqueue (count+24 MiB, shed-then-reject), marks done/failed on every finalize path, expires cleanup to faithful, cancels on session fail/stop. Serial actor keeps one commit order; lane cannot stall (failed advances, expiry releases). Gate C host tests: starvation-release order, late-cleanup-keeps-faithful, shed-order, first-wins/bounded claims |
| P04 streaming admission + handshake (§8.1) | IMPLEMENTED + TESTED (host 5/5) | `MetaRealtimeSession.kt`, `MetaMuseSttProvider.kt`, `RemoteProviderFactory.kt`, `LiveRemoteUtterance.kt`; `RemoteAdmissionContractTest` (Robolectric). Live-provider acceptance BLOCKED (credentials) |
| P05 bounded continuous speech (§5.3) | WIRED + TESTED (host 564/564) + DEVICE-PROVEN split | `SegmentReconciler.kt` wired: per-utterance 29 s hard cap (464,000 samples) forces endpoint with 16,000-sample overlap tail re-seeded into next utterance (`split continuation` lines); soft-cap safe-pause preferred when a VAD silence sits in the overlap window. Device (S23, 66.4 s dense JFK via Mac-speaker acoustic): utt-1 froze at exactly 464,128 samples → split, utt-2 another 464,512 → split, utt-3 remainder; all three committed applied=true (435+503+135 chars, continuous JFK text visible in field, full P07 tel chains). Zero `non-UI thread` violations post-fix. Evidence: `evidence-s23/p05-split-field.png`, `evidence-s23/p05-split-log.txt`. Device-found bug en route: audio-thread view touch → `E/ViewRootImpl: Accessibility content change on non-UI thread` 222×/run (fatal on future Android) + StrictMode DiskRead from dead `mmForLoad` line — fixed (main-thread guard + dead-code delete), `LiquidVisualThreadTest` 2/2 |
| P06 language/engine decoupling (§7) | IMPLEMENTED + TESTED (host 5/5 + device) | `LocalAsrRoute.kt` + fail-closed per-utterance LID; device: LID 8/8 alternation, Auto-without-Canary 2/2, ContinuousWinner 10+10, WerCer LID 4/4 correct |
| P07 typed outcomes + telemetry (§6.4) | WIRED + TESTED (host 25/25 incl. stage guard; DEVICE TRACE-PROVEN for local route) | `UtteranceTelemetry.kt` + `TelemetryStageGuard.kt` (once-per-utterance-type, bounded) + IME boundaries: SPEECH_ONSET, FIRST_PCM, QUEUE_ADMISSION, ENDPOINT, RECOGNITION_START/END (typed outcomes), PROVIDER_FINAL (local; remote via COMPLETE stage), CLEANUP_START/END/VALIDATED, COMMIT_ATTEMPT/RESULT, CANCELLATION, RELEASE (lane exit + audio-free rejects). DEBUG-only `tel utt=` trace lines. Device: live S23 dictation trace (speaker loopback) shows full per-utterance chains incl. overlapping onset — `evidence-s23/p07-tel-trace.txt`. Explicitly excluded: FOCUS/CAPTURE_REQUESTED (pre-utterance-identity, unkeyable), REMOTE_ACK (no ack signal exists), remote CONNECT/PREVIEW/BACKLOG/FINAL (mapped in `updateRemoteProgress`, unrunnable without provider credentials — BLOCKED) |
| U01 first-run incl. select step (§9.2) | IMPLEMENTED, SCREENSHOT-PROVEN (DE dark+light step 1/5) | Onboarding renders correctly both themes; `evidence-s23/` screenshots + UI dump |
| U02 correction sheet (§9.5) | IMPLEMENTED, TESTED (unit 548/548 + device E2E) | `CorrectionPlan.kt` (exact-span revalidation, wordAtCursor, canUndo) + classic-View sheet `ui/correction/CorrectionSheet.kt` (Compose dropped: IME service window has no Activity lifecycle — ComposeView crashed with ViewTreeLifecycleOwner-not-found; plain Views need no owners) + IME wiring (open/apply/undo/remember/dictate/close). Device E2E on S23 via QA editor: open on cursor word, edit replacement, apply H→Hallo, proposal+remember ("Schreibweise gemerkt"), undo Hallo→H, zero FATALs. Evidence: `evidence-s23/u02-sheet-*.png` |
| U03 recovery surface (§9.6) | WIRED + TESTED (host 567/567) + DEVICE-PROVEN (abandon→card→discard) | Store + 6 save sites + same-field retry (`retryEligible`: no blank/password/cross-field) + Home `Diktat aufbewahrt` card (text preview + hint + immediate discard) + lock-clear. Device-found hole FIXED this session: stop-during-decode (HOME/lock/field-switch) cancelled the snapshot decode via `endpointJob.cancel()` → captured speech silently lost, abandon sites unreachable. Fix: `StopReason.cancelsInflightDecode()` — abandon-type stops let decode finish, stale-generation validation refuses insertion and saves the draft; USER_STOP/PASSWORD/DESTROYED still cancel. Device E2E: 213 chars decoded post-HOME → `abandoned stale generation` → card with JFK text, field stayed empty → Verwerfen → card gone. Evidence: `evidence-s23/u03-recovery-card.png`, `u03-card/discard-uidump.xml`. Retry-tap insert: host-tested only (live user run pending). Card insert ("Hier einfügen"/Insert here + 4 locales, cursor-preserving, never replaces, consume-on-apply): implemented + `RecoveryInsertTest` 5/5, device live-run pending |
| U04/U05 voice bar + visual tokens (§§9.1,9.3–9.4, §9.8 visual pass) | IMPLEMENTED + SCREENSHOT-MATRIX PASS (device) | `UiTokens.kt` (72dp bar min, 48dp targets, 120–184dp preview), `VoiceGestureBar.kt`, full copy table (EN/DE/ES/FR). Matrix on S23 (384dp, de-DE), all settings restored after: light portrait idle/listening/setup, dark landscape top/card/listening (card reachable by scroll, bar compact full-width, no clipping), dark portrait font-200% idle/listening (all text wraps, bar grows, zero clipping — token claim holds). Targets: pill 76dp min-height, side buttons 48×48dp ImageButtons. Evidence: `evidence-s23/u0405/` (9 shots). RTL: `debug.force_rtl` did not mirror (limitation) — static pass instead: zero absolute left/right margins, paddings, or gravities in bar/home code (start/end + center only). Open (hands-on): TalkBack order/roles/focus, 320/411dp widths (single device). Animator-zero COVERED: this S23 already runs all animation scales at 0.0 — the entire session's evidence (incl. zero-violation thread-fix runs) was captured with animators disabled, exercising the direct visual-update path |
| E01 benchmark + comparison (§10) | MEASURED (local engines, fixture corpus) — 1,000-utt acoustic + Wispr BLOCKED | S23: Canary JFK RTF ≈0.13, Fast JFK RTF ≈0.038 (~3.6×); WER jfk C 0.09/B 0.05, en C 0.33/B 0.0, de C 0.6/B 0.5 (es/fr refs are stubs — actual outputs correct sentences); 60-utt burst 3.9 s; 15-min thermal PASS (avg 284 ms @ utt 1000, no degradation). Current binary re-check 2026-09-06 (BenchmarkOnDeviceTest, S23): Canary INT8 load 1641 ms, cold RTF 0.142, warm p50 1437 ms / p95 1635 ms / avg RTF 0.133, 108-char exact JFK decode — `evidence-s23/e01-canary-2026-09-06.log`. Full table: `docs/DEVICE_EVIDENCE_S23_2026-09-06.md` |
| E02 production/native/device tests (§11) | HOST 567/567 + DEVICE green on current binary | 2026-09-06 late battery via direct instrument (fa0b934 binary): 29/29 (Pipeline 12, IME 5, EditorMatrix 7, Audio 4, QueueActor 1) + 19/19 (isolation 3, FastConformer 3, LID 7, ContinuousWinner 1, WerCer 1, Benchmark 2, AutoWithoutCanary 2) = 48/48 OK. Earlier: 47/47 combined + FreshInstall + RealNetwork + thermal, all S23 |
| E03/E04 docs + release (§12) | DOCS CURRENT; release unsigned-internal | This file, `SPRICH_RELEASE_REPORT.md`, `DEVICE_RUNBOOK.md` (needs §0–3 refresh for direct-instrument method), `DEVICE_EVIDENCE_S23_2026-09-06.md` (new), `evidence-s23/` (new) |

## Tests run (this session, sandbox-free)
- `:app:testDebugUnitTest` 567/567 green (564 + 3 `StopReasonDecodePolicyTest`) + `:app:lintRelease` clean
- U03 device E2E (S23): dictate 15 s dense → HOME mid-decode → 213 chars decoded post-stop → `abandoned stale generation`, draft saved, field empty → Home card with text → Verwerfen → gone. Pre-fix runs: `finalization actor cancelled pending` + silent loss (the hole)
- U04/U05 visual matrix (S23, settings restored to dark/1.0/auto/LTR/de-DE after): 9 shots in `evidence-s23/u0405/` — no clipping/overlap anywhere; landscape card needs one scroll (by design); 200% wraps cleanly; RTL static-only (force_rtl ineffective, no absolute-LTR code); TalkBack + reduced-motion still hands-on
- E02 late battery (S23, fa0b934 binary, direct instrument): 29/29 + 19/19 = 48/48 OK (143 s for batch 2)
- `:app:testDebugUnitTest` 564/564 green (562 + 2 `LiquidVisualThreadTest`: bg-call marshals to main, main-call synchronous) + `:app:lintRelease` clean
- P05 device (S23, Mac-speaker acoustic, the repo's `qa-editor/scripts/acoustic-stress.py` method): 87 s JFK → 24 onsets, 24 applied commits; 66.4 s dense variant → 2 hard-cap splits + remainder, all committed. Method correction: on-device `tinyplay` loopback is NOT viable (shell cannot open `/dev/snd/pcmC0D0p`; earlier captures were room audio) — use Mac `afplay` at full volume
- P05 device smoke #1 (S23, room audio — tinyplay never emitted): dictation gen-7, 5 endpointed utterances with full tel stage chains, utt-5 SUCCESS commit 57 chars applied=true; `Wird geschrieben…` finalizing strip state screenshot-proven
- RETRACTED (not a defect): the earlier "tap-to-speak wedge" was automation error — adb taps at y≈1975 hit inert app text above the bar, and two apparent responses were a commit-driven CURSOR_MOVED stop + a true toggle. Verified: bar toggle zone ≈ y2050–2550 (aim y2300); each toggle logs `toggle running=… sessionState=…` (new diagnostic line). Same-field re-taps are Android no-ops by design (no new onStartInput). Rule: always confirm toggles in logcat, never by screenshot alone
- Automation note: on-device `tinyplay` loopback unviable (shell can't open PCM); Mac-speaker `afplay` is the method; `sessionState` suffix on `dictation stopped` + `start skipped` lines added as permanent diagnostics
- `:app:testDebugUnitTest` 556/556 green (554 + 2 P07 stage-guard: first-wins/bounded)
- `:app:testDebugUnitTest` 554/554 green (548 + 6 P03 Gate C: starvation/late-cleanup/shed order + first-wins/bounded claims)
- `:app:testDebugUnitTest` 548/548 green (539 baseline + 9 U02 wordAtCursor/plan/undo: `CorrectionPlanTest`)
- U02 device E2E (S23, debug IME, QA editor): sheet open/edit/apply/proposal/remember/undo, 0 FATALs — `evidence-s23/u02-sheet-*.png`
- `:app:testDebugUnitTest` 539/539 green (incl. 2 new sign-preservation tests) [earlier same-session baseline]
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

## Session handoff (2026-09-06 night — dogfood week starts)

**Branch:** `agent/sprich-integrity-sprint` (pushed to `origin`). **Tip at handoff:** see log.
**Week build:** `app-debug.apk` v120/1.0.0, sha256 `7decefa9…`, installed on S23
(R3CW404GVBL) + TCL T807D (ZXKRS4VKGQ8PWGEQ). Unsigned internal, Meta AI API
profiles unchanged. S23 system settings restored (dark/1.0/auto/LTR/de-DE).

**Achieved tonight:** P05 29 s split device-proven (2 splits + 16 k overlap,
all committed) + audio-thread view-touch fix (222→0 violations);
U03 stop-during-decode hole fixed (decode finishes, stale-gen abandons to
draft) + Home card E2E (card→discard) + card Insert button (host-tested,
needs one live voice run); U04/U05 9-shot visual matrix (no clipping;
landscape needs one scroll; 200% wraps; RTL static-only); E01 Canary
re-check (RTF 0.133); E02 48/48 device battery on current binary; runbook
§2b (unsigned loop, toggle zone y≈2050–2550, afplay method); wedge
"defect" retracted (was automation coordinate error). Host 572/572, lint
clean, tree clean. Evidence: `evidence-s23/` (p05-*, u03-*, u0405/*, e01-*).

**Remains:** (a) live voice run: dictate ~15 s → HOME mid-decode → card →
"Hier einfügen" → text lands in field (user offered, pending);
(b) TalkBack order/roles/focus walkthrough (hands-on);
(c) real RTL-locale screenshot (force_rtl ineffective);
(d) 320/411dp widths (single 384dp device);
(e) 1000-utterance acoustic corpus, Nemotron models, signed release,
live-provider credentials, Wispr comparison (all externally blocked);
(f) intermittent blank decodes on loud looped audio (open flake; quiet
clips always decode).

**Next session:** pull branch, read this file, continue from (a)→(d) with
user's week bug list; then unblock (e) as credentials/hardware allow.
Session log: `~/.local/share/muse/sessions/2026/09/06/01a07638-5f0f-7723-8a02-0480f5d06b9d/`.

## Decisions (standing)
- Stay unsigned for internal testing; Meta AI API recommended profiles kept; Gemini/OpenAI-live deferred by specification
- Fixtures: natural archive speech (sherpa test_wavs); a same-day macOS-TTS detour was staged then fully replaced — no result rests on it
- `:app:connectedDebugAndroidTest` wipes app data (marker-proven) — device evidence uses direct `am instrument`; runbook update pending
- Copy+setReady model "restore" in tests is banned (cannot manufacture readiness; destroyed a valid install once) — FreshInstall owns real provisioning
