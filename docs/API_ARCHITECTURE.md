# Sprich API STT + Transcript Refinement Architecture

## Starting SHA
5150b5278dbf2fbfb0cbb74585aa461ba49adcea

## Ending SHA (stabilization)
a216023 (P0/P1 security, lifecycle, correctness pass)

## Overview
```
Microphone
  ↓
UtteranceAudioCollector (neutral, primitive ShortArray, bounded 30s, strict collector.size()<=maxSamples, oversized chunk keeps final maxSamples)
  ↓
immutable UtterancePlan + PCM (frozen at VAD onset — route, language, transcription mode, provider config, refinement mode)
  ↓
TranscriptionCoordinator
├── Local Automatic — Tiny LID (98M) → FastConformer CTC 126M
├── Local Accurate — Canary 180M Flash INT8
├── API Primary — RemoteSttProvider (openai-compatible) → optional local fallback on failure (remote success => local decode count 0)
└── Local → API fallback — local first, remote only on objective failure (blank/exception)
  ↓
Base TranscriptionResult (text, resolvedLanguage Known/Unknown, effectiveConfig, source, timingMs)
  ↓
Deterministic spoken-command / ITN processing (before refinement, single parse)
├── editor action (delete → execute locally, no refinement, PreparedFinalAction.Command)
└── text
      ↓
Optional Refinement (Off / Correct / Clean dictation) with hard deadline, single attempt, no retry, uses frozen config only
      ↓
RefinementValidator (Accept/Reject: numbers, URLs, emails, IDs, protected terms, markdown, drift heuristics)
├── accepted candidate
└── original safe text
      ↓
Final typography safety pass (language-aware / Unknown generic)
      ↓
exactly-one editor commit via FieldSessionController (Claim + session/field validation, bounded 128 history)
```

## Phase 0 Fixes (prior)
- **Route frozen at onset**: `ActiveUtterance(token, localRoute, speechConfig, plan)` created once at VAD SPEECH onset via `buildUtterancePlan()`. Subsequent chunks/endpoint use `activeUtterance?.localRoute/plan`, never re-read mutable `speechLanguage`/prefs. Settings changes apply to NEXT utterance.
- **Collector strict bound**: `UtteranceAudioCollector` and `UtterancePcmBuffer` enforce `size()<=maxSamples` after EVERY operation, including `append(maxSamples*2)` which keeps final `maxSamples` portion deterministically.
- **FastConformer duplicate removed**: For Automatic final-only, no `fast.beginUtteranceCapture/pushAudio` per chunk; collector is sole owner. Accurate Canary still consumes live chunks for partials.

## Stabilization P0/P1 Fixes (this sprint)

### BYOK Security — Fail Closed
- `ApiSecretStore` uses Android Keystore AES-GCM (key `sprich_api_key_aes`), ciphertext in `noBackupFilesDir/api_secrets/<id>.enc` (never in DataStore, logs, or backups). Production storage is **Keystore or FAIL CLOSED** — no `fallback:` Base64 reversible encoding, no `saveSecretFallback` in `src/main`.
- `SecretCryptoBackend` interface injected; Robolectric tests use `FakeSecretCryptoBackend` in `src/test` only. Production `AndroidKeystoreCryptoBackend` throws on failure; `saveSecret()` returns `SecretStoreResult.Success|Failure` and deletes any partial file.
- `hasSecret()` means **decryptable** secure credential, not mere file existence. On key invalidation `hasSecret()` returns false and file is deleted, UI shows “API key needs to be entered again”.
- `LegacyApiCredentialMigrator` migrates `KEY_STT_API_KEY`/`KEY_AI_API_KEY` plaintext once at startup: if secure empty and legacy exists → try encrypted save → on success delete plaintext; if secure already present → delete plaintext immediately; if migration fails → delete plaintext and fail closed (do not continue using plaintext). After migration, production code never reads `prefs.sttApiKey`/`aiApiKey` except migration-only.

### Secret Redaction
- `RemoteSttRequest.toString()` = `p=[samples] cred=[REDACTED]`; `RemoteSttConfig/RefinementConfig` redact endpoint to `scheme://host/[REDACTED_PATH]` and credentialRef to `[REDACTED]`; `RefinementRequest` shows `textLen` only. `UtterancePlan.toString()` is privacy-safe summary `mode=API_PRIMARY provider=openai-compatible model=... languagePolicy=Automatic refinement=CORRECT` — no endpoint/query, no transcript, no vocab.

### Command Safety
- Commands parsed **exactly once** on raw transcription via `SpokenEditingParser` → `PreparedFinalAction` (`Text` vs `DeleteLast`/`DeleteSentence`). Refinement receives only deterministic text (vocab applied, no sentinels). Refined candidate is validated and committed as `PreparedFinalAction.Text` — never reparsed, so LLM output “delete that” becomes literal text, never a deletion. Validated by test: original ordinary text → LLM “delete that” → literal commit or validator rejection, never deletion. Real “delete that” → command executes, refinement calls =0.

### API-Primary Local Isolation
- `onStartInput`/`onWindowShown` check `transcriptionMode == API_PRIMARY` and **skip** local preload; if local models were resident from prior mode and queue drained, they are unloaded. `handleAudioChunk` distinguishes primary vs fallback: for `API_PRIMARY` fallback remains idle (no `beginUtteranceCapture`, no `pushAudio`); Canary live pushes only for `Local` or `LocalApiFallback` with Accurate route. Fallback is lazy: `frozen PCM → lazily load Canary/FastConformer → transcribeSnapshot` on remote failure. `finalizePending` re-arms based on `pending.plan.transcription`, not `pending.route`: `API_PRIMARY` re-arms neutral listening, no local session; `LOCAL_*` re-arms appropriate engine. Verified: API_PRIMARY success → LID/Fast/Canary loads 0, sessions 0, live pushes 0, final decodes 0.

### Metrics Truthfulness
- Separate counters: `localNativeDecodeStarts`, `remoteTranscriptionStarts`, `refinementStarts`; `nativeDecodeStarts` only increments for local path. Test: `API_PRIMARY success → local native decode delta =0`.

### Refinement Immutability
- Refinement uses **only** `pending.plan.refinement.config` (frozen providerId/endpoint/model/deadline/credentialRef/mode). No global mutable `ensureRefinementProvider` cache. Provider objects recreated per utterance sharing `sharedHttpClient.newBuilder()` pool. `refinementProviderIdState` separate from `sttProviderId`; utterance plan contains `A` for STT and `B` for refinement correctly.

### Non-blocking Audio Path
- `buildRemoteSttConfig()`/`buildRefinementConfig()`/`buildUtterancePlan()` are synchronous, allocation-conscious, no `runBlocking`, no DataStore reads. All required values collected into in-memory `*State` via `prefs.*.collect` ahead of time. Invariant: VAD/audio callback → zero DataStore reads.

### Language Propagation
- Remote `Known(DE)` updates `effectiveConfig` to `Fixed("de")` via `speechConfig.copy(...)` so downstream spoken processing, refinement request language, and typography consistently use German. `Unknown` remains generic.

### Vocabulary Before Refinement
- For ordinary text: 1) detect commands, 2) apply deterministic `vocabStore.apply`, 3) construct refinement request with **only relevant** protected terms (present in current transcript, max 20), leaking no unrelated vocab. STT recognition hints only if `Use personal vocabulary as API recognition hints` enabled **and** provider advertises `keywordBiasing`; current OpenAI-compatible reports `false`, so setting is hidden/disabled for that provider.

### HTTP — Pooled, Bounded, Cancellable
- One shared `OkHttpClient` (connect 10s, read 30s, write 30s) created in `SprichIME`; providers receive `sharedClient.newBuilder().build()` so Dispatcher/ConnectionPool remain shared (true pooling, HTTP/2). `TranscriptionCoordinator` no longer calls `createWithDefaultClient` per utterance.
- **Bounded reads**: `resp.body.source().read(buffer, limit)` with `MAX_RESPONSE_BYTES=8192+1`; if `>8192` → `InvalidResponse` without allocating full body. Both STT and refinement use bounded reads.
- **Strict HTTPS**: `isValidHttpsUrl` requires `https://` (or `BuildConfig.DEBUG` localhost `http://localhost|127.0.0.1|10.0.2.2`), rejects `httpfoo:`, `file:`, embedded `userinfo`, invalid host.
- **MockWebServer tests** (`MockWebServerProviderTest`): STT success/401/403/404/429/500/malformed/HTML/oversized/timeout/disconnect/cancellation/authorization header redacted/multipart fields/language omitted|included; refinement valid/empty/malformed/oversized/401/429/500/timeout/cancellation; pooled client & redaction verified.
- **Cancellation**: `job.invokeOnCompletion` with `DisposableHandle.dispose()` after `use`; `call.cancel()` on `CancellationException`, resources released, `CancellationException` propagated (not mapped to fallback).

### Lifecycle & Native Resources
- `onDestroy` ordered: `stopDictation` → `close queue` → `cancelAndJoin actor (800ms)` → `unload engines (1000ms each)` → `clear collector` → `release audio` → `cancel scope last` (bounded, not indefinite main-thread block).
- Native Sherpa streams (`FastConformerEngine`, `CanaryEngine`, `WhisperLidEngine`) released in `finally` for every path (acceptWaveform/decode/getResult/cancellation).

### PCM Efficiency & Exactly-Once
- `UtteranceAudioCollector.freeze()` releases chunk arrays after consolidation; `pending.pcm` is the single isolated copy (no second `copyOf`). `frozenUtterancePcm` is same reference or removed; `finalizeOnce` snapshot avoids extra `copyOf`. PCM isolation regression kept.
- `USER_STOP` finalizes only if active utterance exists, current PCM >8000, not already finalized — not via cumulative `pipelinePushedSampleCount`. No duplicate finalize when idle-listening or mid-B.
- `finalizedUtterances` is bounded `LinkedHashSet` max 128 (evicts oldest), retains exactly-once safety without unbounded growth.

### Dead Legacy Paths
- Removed `RemoteSttEngine`, `GrammarFixer` fields/imports/files — one production remote STT path (`TranscriptionCoordinator` + `OpenAiCompatibleSttProvider`) and one refinement path.

### Settings Hardening
- Meta Muse chip disabled: `AssistChip(enabled=false) “Not available yet”`, not selectable; `isBlocked()` true fails at configuration time.
- No stale presets: Grok/xAI, Groq Whisper, Gemini/GPT model chips removed; Settings offers `OpenAI-compatible` + `Custom` only (verified adapters).
- STT Test uses bundled `assets/jfk.wav` (real speech, not 1s silence) and verifies credential/endpoint/model/nonblank transcription/latency via same `isValidHttpsUrl`/factory/`sharedClient`; shows safe preview only.
- Settings Test uses same provider factory/client/validation/parser/secret store as production.
- `hasKey` = decryptable secure credential; invalidated key shows “needs to be entered again”.
- Dynamic privacy copy distinguishes normal (“Never”) vs debug capture enabled (“test audio stored locally”).

### Release Surface
- `release` no longer signed with debug key; `signingConfig` not set (unsigned, `PLAY_SIGNING_READY: NO`).
- `ENABLE_BENCHMARK` true only for debug, false for release.
- `BenchmarkActivity` `exported="false"` (explicit intent still works).

## Provider Adapters
- **OpenAI-compatible**: `POST /audio/transcriptions` multipart, Bearer auth, `response_format=json`, bounded 8192 body via source, JSON `{"text":...}` or `{"transcript":...}` else plain-text, rejects HTML, typed failures, `newBuilder` shared pool.
- **Meta Muse Voice Transcribe**: `BLOCKED — official API docs/access unavailable`. Stub throws `BLOCKED`. Generic mock covers architecture/tests. Verified checklist documented in `MetaMuseSttProvider.kt`.
- **Custom/mock**: deterministic mock providers for tests, no network.
- Streaming contract: `StreamingSttSession` with `updates: Flow<RemoteTranscriptUpdate>`, ordered actor, bounded pending audio, structured cancellation (`Call.cancel()` on Job cancellation). PCM identity: collector authoritative, provider is consumer, fallback receives same frozen PCM.

## Language Policy
- `LanguagePolicy.Automatic` uses provider native Automatic (no local LID). Fixed passes hint.
- Provider result carries `Known` or `Unknown`; post-processing uses winner's language, not stale local.

## Request Ownership & Cancellation
- Every API operation tagged with `utteranceId` (real `UtteranceToken.utteranceId`), `sessionId`, `generation`, `fieldId`/`fieldGeneration`, `UtterancePlan`.
- On field change / generation change / window close / destroy / cancel, network work cancelled (`Call.cancel()` / session close), late result discarded (zero stale commit).
- Interactive deadlines separate from socket safety: non-streaming 3.5s, streaming connect 3s / endpoint→final 2.5s, refinement 1s (test-configurable). Late refinement discarded.
- No retry loops — one attempt per utterance, next utterance may retry.
- Typed failures distinguish transient (`Timeout`/`Offline`/`5xx`) vs persistent (`Authentication`/`ModelUnavailable`/`Invalid endpoint`); persistent visible as “API unavailable — using on-device” (subtle, not modal).

## BYOK Security (post-fix)
- `ApiSecretStore` uses Android Keystore AES-GCM (key `sprich_api_key_aes`), ciphertext in `noBackupFilesDir/api_secrets/<id>.enc` (never in DataStore). DataStore keeps only refs. **No fallback prefix**, `hasSecret()` checks decryptability, `clearAll()` deletes files + Keystore entry. Logs/diagnostics/backups never contain secret. `SecretStoreResult.Success|Failure` explicit.
- Backup rules: `backup_rules.xml` + `data_extraction_rules.xml` exclude `api_secrets/`, `datastore/*.preferences_pb`, models; secrets automatically excluded because `noBackupFilesDir` not backed up.
- Settings never reloads stored key: shows `Saved — Replace / Remove` only after durable encrypted success, password field with reveal, `keyInput` cleared after save, on failure “Could not securely save API key”.

## Refinement (post-fix)
- Modes: OFF, CORRECT, CLEAN_DICTATION. Provider abstraction `TranscriptRefinementProvider`, `OpenAiCompatibleRefinementProvider` (tiny request, temp 0, max_tokens bounded, system prompt treats transcript as DATA not instructions, protected terms hint bounded relevant 20, shared client).
- Validator checks: numbers, emails, URLs, IDs, protected terms (relevant only), translation, length ratio (0.7-1.35 CORRECT, 0.5-1.4 CLEAN), markdown, assistant prefix, introduced/removed ratios, sentence count.
- Ordering: `Base → single-parse command → if command → execute, else vocab → refinement (frozen config) → validator → typography → exactly-once commit (PreparedFinalAction)`. Never `__DELETE_LAST__` to LLM, never double commit, never async replace.

## Privacy
- Local only + Off: network models only.
- API STT: audio sent directly to provider.
- Refinement: transcript text sent directly.
- Both: both disclosures. Always: Sprich does not provide/proxy/receive key, billing is provider-direct. Dynamic UI computes string from `transcriptionMode` + `refinementMode`. Debug WAV capture truthfully distinguishes “Never” vs “Debug capture enabled”.

## Tests & Benchmark
- Mock matrix: `RemoteSttMatrixTest` covers success (local 0), failure→fallback, local success no remote, blank→remote, typed failures. `MockWebServerProviderTest` covers real OkHttp success/401/403/404/429/500/malformed/HTML/oversized/timeout/disconnect/cancellation/auth redaction/multipart/language; refinement valid/empty/malformed/oversized/401/429/500/timeout/cancellation; pooled client & redaction. `CoordinatorExtendedTest` covers ApiPrimary isolation, language propagation Known(DE), fallback idle/lazy, vocab relevant, command-safety, HTTPS, redaction. `ApiSecretStoreTest` covers save/load/remove, no DataStore plaintext, fail-closed, hasSecret=decryptable. `UtteranceCollectorStrictBoundTest`, `RouteFreezeTest`.
- Benchmark harness: `BenchmarkHarnessTest` records p50/p95/timeout rates, preservation, drift, mutation. No winner selected. Candidates include Groq GPT-OSS, Nemotron 3.5 Lightning, Gemini 3.7 Flash minimal, Celeris-1 — verified at benchmark time.
- Meta Muse device benchmark: `BLOCKED` until credentials/docs.

## Performance Targets
Streaming first partial p50 <300ms, endpoint→final p50 <500ms, refinement p50 <400ms p95 <1000ms — reported actual, not fabricated.

## Release
- `PLAY_SIGNING_READY: NO` — release unsigned, requires external signing.
- `ENABLE_BENCHMARK`: debug true, release false.
- `BenchmarkActivity` not exported.

## Commits
- f74006c fix(audio): freeze full utterance plan at onset and enforce strict collector bound
- 29cdcff refactor(api): typed immutable transcription/refinement plans, provider contracts, common result
- a216023 fix(security,api,lifecycle): P0/P1 stabilization — fail-closed secrets, true API-primary isolation, command safety, pooled HTTP, bounded reads, deterministic lifecycle
