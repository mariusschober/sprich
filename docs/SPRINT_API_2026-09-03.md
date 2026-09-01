# Sprint API 2026-09-03 — Production API STT + Ultra-Low-Latency Refinement

## Starting SHA
`5150b5278dbf2fbfb0cbb74585aa461ba49adcea`

## Ending SHA
`779552d0cc76ed4851bdb542c5678db772c3107a` + docs update

## Goals (from mission)
Turn experimental `RemoteSttEngine` / `GrammarFixer` into two reliable independent production features (Feature A API transcription, Feature B transcript refinement) with typed plans, provider abstraction, secure BYOK, deterministic validator, and strict failure isolation (“a broken API should be almost invisible”).

## What changed
### Phase 0 — Shared-pipeline closure
- `UtteranceAudioCollector.kt:53-66` / `UtterancePcmBuffer.kt:44-57` strict bound `size()<=maxSamples` including oversized `append(maxSamples*2)` (keeps final tail).
- `SprichIME.kt:121-128` `ActiveUtterance(plan)` frozen at `VAD SPEECH` onset (`buildUtterancePlan()`), route/language/mode/provider config never re-read from mutable prefs. Next utterance uses new settings.
- Removed duplicate FastConformer live PCM buffer for Automatic (final-only via collector), Canary remains consumer for Accurate partials.

### Transcription
- `TranscriptionMode.kt` ON_DEVICE / API_PRIMARY / LOCAL_API_FALLBACK replaces raw strings `local/fallback/remote`.
- `UtterancePlan.kt` + `TranscriptionPlan` sealed + `TranscriptionResult.kt` (source IDs `local-fast/local-canary/api-openai-compatible/api-meta-muse/api-mock`, no secrets).
- `LanguagePolicy.kt` Automatic / Fixed, `RemoteSttConfig` immutable snapshot.
- `TranscriptionCoordinator.kt` routes per plan: API_PRIMARY success → local decode 0, failure → lazy local fallback; LOCAL_API_FALLBACK only on blank/exception.
- `RemoteSttProvider.kt` interface + `StreamingSttSession` contract, `RemoteSttCapabilities`, typed `ApiFailure.kt` (Cancelled/Offline/Timeout/Authentication/RateLimited/ModelUnavailable/ProviderUnavailable/InvalidResponse/Http/Network), `DeadlinePolicy.kt` (product 3.5s/3s/2.5s vs socket 10s/30s).
- `OpenAiCompatibleSttProvider.kt` (remote-only) strict parsing, bounded 8KB, `Call.cancel()` on Job cancellation.
- `MetaMuseSttProvider.kt` BLOCKED stub with verification checklist, no guessed endpoint.

### Refinement
- `RefinementMode.kt` OFF / CORRECT / CLEAN_DICTATION, `RefinementConfig.kt`, `TranscriptRefinementProvider.kt`.
- `ai/OpenAiCompatibleRefinementProvider.kt` tiny request (temp 0, max_tokens `len/3+120` capped 512, transcript as `""" DATA """` + bounded protected terms ≤20).
- `RefinementValidator.kt` Accept/Reject (numbers, URLs, emails, IDs, protected terms, markdown, assistant prefix, drift ratios, length ratios, translation).
- Ordering in `SprichIME.finalizePending:1470-1535`: base → `SpokenEditingParser` (deterministic, before LLM, sentinels never to LLM) → if delete → no LLM → else refinement with `withTimeoutOrNull(deadline)` → validator → typography → exactly-once commit.

### Security
- `ApiSecretStore.kt` Keystore AES-GCM `sprich_api_key_aes` in `noBackupFilesDir/api_secrets/<id>.enc`, DataStore keeps `credentialRef` only, `clearAll()` deletes files+Keystore, fallback `fallback:` base64 on Robolectric.
- `backup_rules.xml` / `data_extraction_rules.xml` exclude `api_secrets/`, `datastore/*.preferences_pb`, models; secrets automatically excluded because `noBackupFilesDir` not backed up.
- `SettingsScreen.kt` `TranscriptionSection` + `RefinementSection` show `Saved — Replace / Remove`, password field with reveal, `keyInput` cleared after save, never reloads plaintext. Test buttons show `Connected · ms` or typed failure.

### Privacy & UX
- `DynamicPrivacySection` computes disclosure from `transcriptionMode` + `refinementMode` (local-only vs API vs both), always states `Sprich does not provide, proxy or receive your API key` + billing.
- `handleAudioChunk` skips capture in password fields (inherited), no `runBlocking` secrets in logs, transcript not logged unless `debugTranscriptTrace`.

### Tests & Harness
- `TranscriptionPlanTest`, `RouteFreezeTest`, `UtteranceCollectorStrictBoundTest`, `ApiSecretStoreTest`, `RemoteSttMatrixTest` (API primary success local 0, failure→fallback, local success no remote, blank→remote, typed failures), `RefinementValidatorTest`, `RefinementCorpusTest` (18 EN/DE), `RefinementPromptInjectionTest`, `BenchmarkHarnessTest` (p50/p95, no winner selected).

## Verification
- `./gradlew :app:testDebugUnitTest` PASS (225 tests)
- `./gradlew :app:lintDebug` PASS
- `./gradlew :app:assembleDebug` PASS
- `./gradlew :app:assembleDebugAndroidTest` PASS
- `./gradlew :app:assembleRelease` PASS
- `./scripts/verify-models.sh` PASS (Canary runtime present, no bundled models)
- `./scripts/check-apk.sh` PASS after moving network to `speech/remote` only

## Remaining
- Meta Muse adapter BLOCKED pending official docs/creds (`META_MUSE_DEVICE_TEST: BLOCKED`)
- Streaming partial preview not yet composed (IME-local preview only)
- No retry loops, no warm-up requests
- Real device WER/latency not yet measured

```
API_STT_ARCHITECTURE_READY: YES
TRANSCRIPT_REFINEMENT_ARCHITECTURE_READY: YES
BYOK_SECURITY_READY: YES
LOCAL_ASR_ARCHITECTURE_FROZEN: YES
```
