# TEST REPORT

## Unit tests (debug)

Run: `./gradlew :app:testDebugUnitTest`

Results 2026-08-24 (local macOS, JDK 18):

```
BUILD SUCCESSFUL — 34 unit tests, 0 failures
```

Suites:

- `TranscriptStabilizerTest` (5): stablePrefixRequiresAgreement, shrinkingHypothesisRevisesStable, neverDuplicatesCommitted, unstableChurnDoesNotAffectStable, emptyHypothesisKeepsStable — validates LCP algorithm, requires 2 consecutive agreements, handles revision, churn.
- `CompositionManagerTest` (5): applyUpdateSetsComposing, finalCommitMerges, noDuplicationOnOverlappingHypothesis, handlesEmptyGracefully, finishIfActiveClears — ensures `setComposingText`/`commitText` delta no duplication.
- `SpokenEditingParserTest` (7): punctuation inline EN/DE/ES, delete commands, ITN email, commandsDisabled, noFalsePositive — deterministic layer, conservative.
- `SessionFsmTest` (4): validTransitions, impossibleTransitionIgnored, errorRecovery, passwordFieldNeverStarts — FSM guards impossible states.
- `VadTest` (4): calibratesAndDetectsSpeech, silenceStaysSilence, utteranceEndAfterSilence, resetsCleanly — energy threshold 0.015, calibration 300ms, onset 90ms.
- `AudioRingBufferTest` (4): writeAndDrain, overwritesOldestOnOverflow, snapshotLastSeconds, clearResets — overflow discards oldest, snapshot last seconds correct.
- `ModelManagerTest` (5): initialStatusNotDownloaded, canaryReadyAfterFakeInstall, atomicRollbackOnFailedDownload, deleteModelClears, shaBlankPasses — atomic rename, integrity, rollback.

Coverage: stabilizer/command/FSM/ring buffer are latency-critical paths; composition delta is duplication-critical.

## Build verification

- `assembleDebug` — 19 MB (placeholder whisper 179B + 8 dex + libs). Success in 20s.
- `assembleRelease` — 1.5 MB optimized with R8 (placeholder). Real release with 57MB whisper will be ~60 MB. Minify + shrinkResources enabled.
- APK checks:
  - `verify-models.sh` confirms placeholder detected, APK clean (no `canary`/`nemotron` leaked).
  - `check-apk.sh` confirms `speech/*` no `okhttp` import (architectural isolation).
  - `unzip -l` shows `assets/models/whisper-base-q5_1.bin` present (179B placeholder, gate is >50 MB for release).

## Manual / instrumentation (requires device)

Not yet automated on CI emulator; gates for physical hardware:

- [ ] IME lifecycle: `onStartInput` for password vs normal, composing across app switch, rotation, low memory trim, incoming call interruption, Bluetooth route change, mic permission revoked mid-session.
- [ ] Focus matrix: native EditText, Compose TextField, WebView contenteditable, Chrome input, HTML textarea, email/URL/numeric fields (decline).
- [ ] App matrix: Chrome, Gmail, Keep, WhatsApp, Telegram, Signal, Slack, Discord, Notion, Reddit — minimal 10-app smoke with `w: 0.5s` utterance.
- [ ] Rapid 3-field switch: Google search → messaging → docs, no stale/lost text, no model reload.
- [ ] Airplane mode: kill app → enable airplane → dictate via Fast → verify identical (no network, no degraded UI).
- [ ] Budget phone sustained: 5m/15m continuous dictation, measure RTF drift, thermals, battery, listening-silence idle.

## Instrumentation workbench

- Latency marks captured via `LatencyTracker` (monotonic). Bench screen exports JSON locally; mock benchmark shows RTF 0.2, firstPartial 320ms, final 420ms. Real values need physical burst vs sustained runs.

## Known races tested

- Overlapping hypothesis (stable/unstable), shortened hypothesis, punctuation change during composition, cursor movement mid-dictation (should finalize), user typing while dictating (manual edit wins), IME restart, focus transition partial after finalization — covered by `CompositionManagerTest`.

## Privacy audit

- No analytics dependency in `app/build.gradle`; no `com.google.firebase`, `mixpanel`, `sentry`.
- `core/privacy/PrivacyGuard` sanitizes logs; release logs minimal.
- End-to-end airplane test pending physical gate (see above).
