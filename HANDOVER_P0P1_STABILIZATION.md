# Handover — P0/P1 Stabilization → Sol Pro + Muse Spark

**Date:** 2026-09-02  
**Starting HEAD:** `6393dfc13fbe443458d638bf95b40c1b5a40b2f3` (`origin/main` at start, verified, no reset, no force-push authorized but not needed)  
**Ending HEAD:** `ec493f7f6eb8a5c39e4dd157c1492d66a3f828ae` (pushed to `origin/main`)  
**Commits:**
- `a216023878f673e9b6f27f93f6f31f474a530ff0` fix(security,api,lifecycle): P0/P1 stabilization — fail-closed secrets, true API-primary isolation, command safety, pooled HTTP, bounded reads, deterministic lifecycle (25 files, +1538/-604, deletes 2 dead files, creates 6)
- `a20bf6e2867173f1a0255248541d366e4b5e81d3` docs(stabilization): correct API/security/release claims (2 files, +92/-31)
- `ec493f7f6eb8a5c39e4dd157c1492d66a3f828ae` fix(reliability,privacy,security): P0/P1 core — at-most-once editor, FIFO stop/endpoint, isolated failures, no-cancel-fallback, redirect-block, backup privacy, local-cold, episode backpressure (23 files, +1145/-354, 5 new tests)

**Branch:** `main` up to date with `origin/main`, working tree clean.

---

## What Sol Pro should know

### Frozen
- **Local ASR architecture frozen:** `Tiny LID (98M) → FastConformer CTC 126M` for Automatic; `Canary 180M Flash INT8` for Accurate explicit. Do not reopen model selection.
- **General pipeline now true in every production path:** `Mic → UtteranceAudioCollector → immutable UtterancePlan + PCM → TranscriptionCoordinator (Local Auto/Canary/API Primary/Local→API fallback) → deterministic spoken-command → optional Correct/Clean refinement → RefinementValidator → exactly-one commit`. The sprint made that diagram truthful.

### P0/P1 fixed (truthful, tested, pushed) — 2026-09-02 initial

**Secrets — fail closed**
- `ApiSecretStore` now `SecretCryptoBackend` injected, production `AndroidKeystoreCryptoBackend` AES-GCM, `saveSecret(): SecretStoreResult.Success|Failure` (no `fallback:` Base64), `hasSecret()` = decryptable, file deleted on key invalidation, Settings shows “Could not securely save” on `Failure` and “needs to be entered again” after invalidation. `FakeSecretCryptoBackend` lives only in `src/test`.
- `LegacyApiCredentialMigrator` migrates `KEY_STT_API_KEY`/`KEY_AI_API_KEY` once: secure empty+legacy→try encrypted save→on success delete plaintext; secure present→delete plaintext; failure→delete plaintext fail-closed. After migration no production reads `prefs.sttApiKey`/`aiApiKey` (grep remaining only `Preferences.kt` definitions + migrator).

**Redaction & logging**
- `RemoteSttRequest/Config` and `RefinementRequest/Config` custom `toString()` redacted (`credential=[REDACTED]`, endpoint `scheme://host/[REDACTED_PATH]`). `UtterancePlan` is privacy-safe summary (mode/provider/model/languagePolicy/refinement). No `plan=$plan` whole-object credential leak; `check-apk.sh` enforces network isolation.

**Command escalation**
- `PreparedFinalAction` sealed (`Text` vs `DeleteLast`/`DeleteSentence`). `SpokenEditingParser` called exactly once on raw. `Delete` → `executeDeleteCommand` locally, `refinementCalls=0`. Text → `vocabStore.apply` → relevant protected terms only → `providerForRefinementConfig(frozen config)` → `withTimeout` → `RefinementValidator` → `commitFinalText` **without second parse**. LLM “delete that” can only be literal text.

**API-primary isolation**
- `onStartInput`/`onWindowShown` skip local preload when `transcriptionMode==API_PRIMARY` (unload idle locals). `handleAudioChunk` pushes live Canary only for `Local`/`LocalApiFallback` with Accurate; `API_PRIMARY` fallback stays idle until remote failure (`frozen PCM → lazy load → transcribeSnapshot`). `finalizePending` re-arms on `plan.transcription` → `API_PRIMARY` neutral, no `beginSession`. Metrics split: `localNativeDecodeStarts` vs `remoteTranscriptionStarts`.

**Refinement immutability**
- `buildRemote/SttConfig`/`buildRefinementConfig`/`buildUtterancePlan` are synchronous, zero `runBlocking`/`DataStore` on VAD/audio thread (in-memory `*State` collected via `prefs.collect`). `refinementProviderIdState` separate from `sttProviderId`; `providerForRefinementConfig` recreates per-utterance via `sharedClient.newBuilder()` (pool shared).

**Language & vocab**
- Remote `Known(DE)` updates `effectiveConfig` to `Fixed("de")` so spoken processing, refinement `language=de`, and typography all use German. `RELEVANT` protected terms only (present in current transcript, ≤20) leak no unrelated vocab; STT hints only if provider `keywordBiasing` true (OpenAI-compatible false → setting hidden).

**HTTP**
- Single `OkHttpClient` in `SprichIME`, providers via `sharedClient.newBuilder().build()` (Dispatcher/ConnectionPool shared, HTTP/2). Bounded reads via `ResponseBody.source().read(buffer, limit)` `8192+1` → `InvalidResponse` if oversized. Strict `isValidHttpsUrl` (`https://` or debug `http://localhost/127.0.0.1/10.0.2.2`, rejects `file:`, `httpfoo:`, userinfo). `MockWebServerProviderTest` 26 tests (STT 13, refinement 9, pooled+redaction) + `CoordinatorExtendedTest` 7.

**Lifecycle & PCM**
- `onDestroy` ordered: `stopDictation` → `closeChannel` → `cancelAndJoin actor 800ms` → `unload 1000ms each` → `clear` → `release` → `cancel scope` (bounded). `FastConformer/Canary` streams in `finally`. `UtteranceAudioCollector.freeze()` releases chunks; `PendingUtterance` single isolated copy; `USER_STOP` checks `activeUtterance && collector.size()>8000 && not yet finalized` (not `pipelinePushedSampleCount`); `finalizedUtterances` bounded `LinkedHashSet` 128 LRU.

**Dead paths**
- Deleted `RemoteSttEngine.kt` and `ai/GrammarFixer.kt` (duplicate networking, unbounded, `runBlocking` providers). One STT path, one refinement path.

**Settings & release**
- Meta Muse chip disabled (`Not available yet`), no Grok/Gemini/GPT presets (only `OpenAI-compatible`+`Custom`). STT Test uses `assets/jfk.wav` + `sharedClient` factory, shows safe preview. `hasKey`=decryptable. `Audio storage: Never` vs `Debug capture enabled` truthful. `release` unsigned (`PLAY_SIGNING_READY:NO`), `ENABLE_BENCHMARK` `debug true`/`release false`, `BenchmarkActivity` `exported=false`.

### P0/P1 additional fixes — 2026-09-02 ec493f7 (this push, 286 tests)

**Editor exactly-once (P0-1)**
- `CompositionManager.applyUpdate` for `isFinal=true` does **one** irreversible `commitText` – ambiguous `false` (hostile editor appends+returns false) never retried, `FieldSessionController.commitUtteranceTyped` claims via exactly-once `finalizedUtteranceIds` before mutation. `SprichIME.commitFinalText`/`applyFinalText` on `EditorRejected` no longer `composition.applyUpdate` retry. `deleteLastWord` single `deleteSurroundingText` no fallback `DEL`. Regression `CompositionAdversarialTest.ambiguousFinal_commitTextAppendsButReturnsFalse_exactlyOnce_noRetry` proves `commitTextCalls==1` no duplication no global teardown.

**Partial IME-local (P0-2)**
- Partials stay inside Sprich IME – `CompositionManager` for `isFinal=false` never calls external `setComposingText`; `SprichIME` shows IME preview instead. Guarantees `HelloHello` impossible for any editor. 6 editor types tested: correctly-supporting, rejecting, silent-commit-true, throwing, WebView-like, ambiguous final. `CompositionAdversarialTest` 7 tests + `CompositionManagerTest`/`CompositionTypographyTest`/`ExactlyOnceStressTest` updated to IME-local expectation.

**Stop/endpoint FIFO (P0-3)**
- `SprichIME` single `pendingChannel(4)` is authoritative lane for `ENDPOINT`, `USER_STOP`, 30s cap, explicit finish. `USER_STOP` now freezes current PCM (`snapshot`) and `enqueuePending` after earlier accepted utterances; `stopRequested` flag delays generation bump until `queueDepth==0` in actor `finally`, then transitions `Idle`. `finalizeOnce` always `enqueuePending` (no direct `finalizePending` bypass). `StopEndpointRaceTest` 7 deterministic races prove FIFO no loss no duplication no new capture after Stop.

**Pending A isolation (P0/P1-4)**
- `failUtteranceScoped(token, reason)` introduced – logs, `maybeClearActiveStateForToken` only if token still owns capture, **no** `sessionGeneration++`, **no** `fieldGeneration++`, **no** global `utteranceAudio.clear()`, keeps `Listening`. `failSession` only for mic/field/service/active-engine corruption. Actor outer `catch` now `failUtteranceScoped`. Verified via `UtteranceIsolationTest` – A API timeout / refinement / stale / blank does not destroy B.

**Cancellation never → cloud (P0-5)**
- `TranscriptionCoordinator.LocalApiFallback` `catch CancellationException → throw`, `LocalTranscriptionCoordinator` LID/Fast/Canary `catch CancellationException → throw`. ApiPrimary `tryRemote` `catch CancellationException → throw`. `TranscriptionCoordinator.tryRemote` `withTimeoutOrNull` propagates. `CancellationNoFallbackTest` 6 cases: cancel local-first `remote.calls==0`, cancel API primary, WINDOW_HIDDEN/FIELD_LOST/SERVICE_DESTROYED/INPUT_RESTARTED → 0 remote upload 0 refinement 0 mutation.

**BYOK redirect block (P1-6)**
- `sharedHttpClient` and both `OpenAiCompatible*Provider` `followRedirects(false)` `followSslRedirects(false)` (pool preserved via `newBuilder()`), `ApiFailure 300..399 → Http(Redirect blocked)`, `EndpointValidator` centralizes `isValidHttpsUrl` (HTTPS only, no userinfo, valid host, debug localhost). `RedirectBlockingTest` 3 MockWebServer `307→B 0` cases prove auth/audio not forwarded.

**Diagnostic audio privacy (P1-7)**
- `ReplayHarness.replayDir = noBackupFilesDir/sprich_replay` (was `filesDir`), `backup_rules.xml`/`data_extraction_rules.xml` exclude `sprich_replay/spich_traces/diagnostics/benchmark` defense-in-depth, `deleteWavWithMeta` paired deletion, `clearAll` for `Clear local data` deletes WAV+meta+traces, `SettingsScreen` clear deletes harness.

**API-primary local-cold (P1-8)**
- `SprichIME.onCreate` now reads `prefs.transcriptionMode.first()` before any load; if `API_PRIMARY` → 0 `lidLoadAttempts`/`fastLoadAttempts`/`canaryLoadAttempts` until remote failure lazy-loads.

**Backpressure episode (item 15)**
- `handleAudioChunk` `suppressEpisode` flag – speech begins while `catchingUp` marks entire VAD episode suppressed counted once, tail never captured even if queue drains mid-speech, cleared on clean `SILENCE/LONG_SILENCE/UTTERANCE_END`.

### Verification (local, no device) — 2026-09-02 initial → 2026-09-02 ec493f7
```
./gradlew :app:testDebugUnitTest  → 260 → 286 tests (41→44 suites) failures=0 errors=0 skipped=0
./gradlew :app:lintDebug         → PASS
./gradlew :app:assembleDebug     → PASS (52M, jfk.wav, libsherpa)
./gradlew :app:assembleDebugAndroidTest → PASS
./gradlew :app:assembleRelease   → PASS (unsigned, lintVital PASS)
./scripts/verify-models.sh       → PASS (Canary runtime present, no bundled models)
./scripts/check-apk.sh            → PASS (speech network-free coordinator pooled allowed, local ASR network-free)
```
Device: `NOT MEASURED` (T807D `QueueActorStressDeviceTest` etc. not re-run; 15m thermal not re-run as local kernels unchanged). New regression suites: `CompositionAdversarialTest` 7, `StopEndpointRaceTest` 7, `UtteranceIsolationTest` 5, `CancellationNoFallbackTest` 6, `RedirectBlockingTest` 3 – all PASS.

### Architecture for Sol Pro
```
PRODUCTION_AUTO_ARCHITECTURE: Tiny LID + FastConformer
LOCAL_ASR_ARCHITECTURE_FROZEN: YES
API_STT_ARCHITECTURE_READY: YES
TRANSCRIPT_REFINEMENT_ARCHITECTURE_READY: YES
BYOK_SECURITY_READY: YES
P0_P1_STABILIZATION_COMPLETE: YES
```

### Remaining for Sol Pro (do not hide)
- Meta Muse adapter `BLOCKED` (no docs/creds) — mock architecture covers tests.
- No warm-up requests, no retry loops, streaming partial preview IME-local only.
- Deeper APK optimization (R8, symbol stripping) left intentionally.
- Physical-device network/thermal/continuous re-validation needed.
- Real release signing still `NO` — external keystore required.
- Provider/model benchmarking deferred (volatile IDs removed).

### Docs updated
- `docs/API_ARCHITECTURE.md` now truthful: bounded source reads, `newBuilder` pool sharing, `PreparedFinalAction`, fail-closed Keystore, real `utteranceId`, release unsigned.
- `docs/PRIVACY.md` distinguishes `Never` vs `Debug capture enabled`, documents decryptable `hasSecret` and migration.
- `docs/SPRINT_API_2026-09-03.md` etc. remain but no longer claim bounded/shared/command safety falsely.

### How to verify
```bash
git fetch origin; git log origin/main --oneline -5
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
./scripts/verify-models.sh; ./scripts/check-apk.sh app/build/outputs/apk/debug/app-debug.apk
grep -R "fallback:" app/src/main --include="*.kt" # only comment
grep -R "sttApiKey" app/src/main --include="*.kt" # only Preferences definitions + migrator
```

**Next step:** Hand to GPT-5.6 Sol Pro for whole-repository production-readiness audit (no further feature work, no model benchmarking, no Meta implementation, no store submission).
