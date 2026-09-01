# Sprich API STT + Transcript Refinement Architecture

## Starting SHA
5150b5278dbf2fbfb0cbb74585aa461ba49adcea

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
Deterministic spoken-command / ITN processing (before refinement, no sentinels to LLM)
├── editor action (delete → execute locally, no refinement)
└── text
      ↓
Optional Refinement (Off / Correct / Clean dictation) with hard deadline, single attempt, no retry
      ↓
RefinementValidator (Accept/Reject: numbers, URLs, emails, IDs, protected terms, markdown, drift heuristics)
├── accepted candidate
└── original safe text
      ↓
Final typography safety pass (language-aware / Unknown generic)
      ↓
exactly-one editor commit via FieldSessionController (Claim + session/field validation)
```

## Phase 0 Fixes
- **Route frozen at onset**: `ActiveUtterance(token, localRoute, speechConfig, plan)` created once at VAD SPEECH onset via `buildUtterancePlan()`. Subsequent chunks/endpoint use `activeUtterance?.localRoute/plan`, never re-read mutable `speechLanguage`/prefs. Settings changes apply to NEXT utterance.
- **Collector strict bound**: `UtteranceAudioCollector` and `UtterancePcmBuffer` enforce `size()<=maxSamples` after EVERY operation, including `append(maxSamples*2)` which keeps final `maxSamples` portion deterministically.
- **FastConformer duplicate removed**: For Automatic final-only, no `fast.beginUtteranceCapture/pushAudio` per chunk; collector is sole owner. Accurate Canary still consumes live chunks for partials.

## API Transcription Architecture
- `TranscriptionMode` ON_DEVICE / API_PRIMARY / LOCAL_API_FALLBACK replaces raw strings.
- `UtterancePlan(transcription, refinement, speechConfig)` immutable per utterance, stored in `ActiveUtterance` then `PendingUtterance`.
- `TranscriptionResult` common above local/remote, no secrets, no fake confidence, `ResolvedUtteranceLanguage` preserved from winner.
- `RemoteSttProvider` interface + `RemoteSttCapabilities`, `RemoteSttConfig` immutable snapshot (providerId, endpoint, model, languagePolicy, deadlineMs, credentialRef).
- `TranscriptionCoordinator` routes per plan: API_PRIMARY success => local decode count 0; failure => lazy fallback; LOCAL_API_FALLBACK only on blank/exception.

## Provider Adapters
- **OpenAI-compatible**: `POST /audio/transcriptions` multipart, Bearer auth, `response_format=json`, bounded 8KB body, JSON `{"text":...}` or `{"transcript":...}` else plain-text, rejects HTML, typed failures.
- **Meta Muse Voice Transcribe**: `BLOCKED — official API docs/access unavailable`. Stub implements contract throws `BLOCKED`. Generic mock covers architecture/tests. Verified checklist documented in `MetaMuseSttProvider.kt` (model ID, auth, streaming transport, audio format, lifecycle, partial/final, endpointing, language metadata, bias schema, error schema, cancellation).
- **Custom/mock**: deterministic mock providers for tests, no network.
- Streaming contract: `StreamingSttSession` with `updates: Flow<RemoteTranscriptUpdate>`, ordered actor, bounded pending audio, structured cancellation (`Call.cancel()` on Job cancellation). PCM identity: collector authoritative, provider is consumer, fallback receives same frozen PCM.

## Language Policy
- `LanguagePolicy.Automatic` uses provider native Automatic (no local LID). Fixed passes hint.
- Provider result carries `Known` or `Unknown`; post-processing uses winner's language, not stale local.

## Request Ownership & Cancellation
- Every API operation tagged with `utteranceId, sessionId, generation, fieldId, fieldGeneration, UtterancePlan`.
- On field change / generation change / window close / destroy / cancel, network work cancelled (`Call.cancel()` / session close), late result discarded (zero stale commit).
- Interactive deadlines separate from socket safety: non-streaming 3.5s, streaming connect 3s / endpoint→final 2.5s, refinement 1s (test-configurable). Late refinement discarded.
- No retry loops — one attempt per utterance, next utterance may retry.

## BYOK Security
- `ApiSecretStore` uses Android Keystore AES-GCM (key `sprich_api_key_aes`), ciphertext in `noBackupFilesDir/api_secrets/<id>.enc` (never in DataStore). DataStore keeps only refs. Fallback to base64 `fallback:` prefix on Robolectric where Keystore unavailable. `hasSecret()` checks existence, `clearAll()` deletes files + Keystore entry. Logs/diagnostics/backups never contain secret.
- Backup rules: `backup_rules.xml` + `data_extraction_rules.xml` exclude `api_secrets/`, `datastore/*.preferences_pb`, models; secrets automatically excluded because `noBackupFilesDir` not backed up.
- Settings never reloads stored key: shows `Saved — Replace / Remove`, password field with reveal, `keyInput` cleared after save, Compose state never holds plaintext after save.

## Refinement
- Modes: OFF (no LLM), CORRECT (punct, caps, grammar, obvious substitution, preserve meaning), CLEAN_DICTATION (+ fillers/false starts).
- Provider abstraction: `TranscriptRefinementProvider`, `OpenAiCompatibleRefinementProvider` (tiny request, temp 0, max_tokens bounded to input length+120 capped 512, system prompt treats transcript as DATA not instructions, protected terms hint bounded 20).
- Validator checks: numbers, emails, URLs, IDs, protected terms, translation, length ratio (0.7-1.35 CORRECT, 0.5-1.4 CLEAN), markdown, assistant prefix, introduced/removed content word ratios, sentence count.
- Ordering: `Base → deterministic spoken-command/ITN → if command → execute, else refinement (deadline) → validator → typography → exactly-once commit`. Never `__DELETE_LAST__` to LLM, never double commit, never async replace.

## Privacy
- Local only + Off: network models only.
- API STT: audio sent directly to provider.
- Refinement: transcript text sent directly.
- Both: both disclosures. Always: Sprich does not provide/proxy/receive key, billing is provider-direct. Dynamic UI computes string from `transcriptionMode` + `refinementMode`.

## Tests & Benchmark
- Mock matrix: `RemoteSttMatrixTest` covers success (local 0), failure→fallback, local success no remote, blank→remote, typed failures. `RefinementValidatorTest` + `RefinementCorpusTest` EN/DE (punct, numbers, URLs, emails, repetition, fillers, injection). `ApiSecretStoreTest`, `UtteranceCollectorStrictBoundTest`, `RouteFreezeTest`.
- Benchmark harness: `BenchmarkHarnessTest` records p50/p95/timeout rates, preservation, drift, mutation. No winner selected. Candidates include Groq GPT-OSS, Nemotron 3.5 Lightning, Gemini 3.7 Flash minimal, Celeris-1 — verified at benchmark time.
- Meta Muse device benchmark: `BLOCKED` until credentials/docs.

## Performance Targets
Streaming first partial p50 <300ms, endpoint→final p50 <500ms, refinement p50 <400ms p95 <1000ms — reported actual, not fabricated.

## Remaining Limitations
- Meta Muse adapter blocked until official docs/creds.
- Streaming partial preview not yet composed into editor (IME-local preview only).
- No automatic retry; no warm-up requests.

## Commits
- f74006c fix(audio): freeze full utterance plan at onset and enforce strict collector bound
- 29cdcff refactor(api): typed immutable transcription/refinement plans, provider contracts, common result
