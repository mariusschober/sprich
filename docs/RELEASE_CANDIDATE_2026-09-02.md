# Release Candidate — Closure Sprint 2026-09-02

**START_SHA:** `cb1fe4bc648d81a982cf205a07454197157a4163`
**END_SHA:** `628fe0ec568e07f5301c6f80ae364a8886761e55`
**BRANCH:** `main`
**COMMITS (origin/main..HEAD):**
- `ad3e98b` fix(ime): make runtime config and editor actions fail closed — atomic snapshot, token authority, EditorActionController, password, gestures, lifecycle, model integrity, verified BYOK
- `26f59e0` test(device): replace simulated release gates with truthful native proof + release hardening
- `628fe0e` fix(release): lint NewApi and unsigned CI signing

**Host verification:** `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease` — all PASS (see below)
**Device:** `adb devices` → no device available during this sprint (T807D not connected, emulator not booted). All device gates therefore BLOCKED with exact reason, per evidence rules. No FakeIC/device numbers were faked as PASS.

---

## A. Prior Review Findings

### FIXED
- Runtime config fragmentation (many mutable prefs fields) → single atomic `RuntimeConfigSnapshot` from one DataStore emission; IME gates all loads/mic on `runtimeConfigFlow` readiness.
- Synthetic `currentUtteranceToken ?: UtteranceToken(...)` for endpoint/finalization → deleted; missing/stale/wrong generation now zero decode/network/mutation + staleCallbackDrops.
- Gesture deletion as independent implementations with char-fallback → single `EditorActionController` owning fieldId/generation/exact span/bounded history 10; `getTextBeforeCursor == null` → zero mutation (no 1-char guess); whitespace preserved.
- Spoken `DeleteLast 40 / DeleteSentence 120` guessed char counts → only exact Sprich insertion if cursor proof holds; sentence disabled (zero mutation) for this release.
- Password field handling: clears history/gesture/preview/ownership before early return; every mutating action checks `isPassword(info)` (text/visible/web/numeric PIN); stale normal-field final after password focus → zero mutation.
- Hold-to-repeat deletion, swipe-down newline, zigzag → deleted; haptic only after success ≤1 per action.
- `AudioCapture.stop()` on Main (300ms join) → `requestStop()` cheap on Main, `awaitStop()` off-Main; all paths covered; `stop()` warns if called on Main.
- `ApiSecretStore` fallback `noBackupFilesDir → filesDir` → FAIL CLOSED (`SecretStoreResult.Failure`).
- `/data/local/tmp` in production LID/FastConformer engines → debug-only fallback behind `BuildConfig.DEBUG`; production via `ModelManager` only.
- Tiny LID readiness weakened `>5M` → production `>10M` encoder / `>50M` decoder + `.installed_ok` marker; tests updated to 13M/90M fixtures.
- `DownloadManager` path traversal `canonicalPath.startsWith(dest.canonicalPath)` → true containment `destCanonical + separator`, reject symlinks/hardlinks, bound files/total/single, atomic staging→verify→swap→delete-old, per-model cancellation, redirect blocked preserved.
- Muse endpoint double-path (`baseUrl` already full URL then append) → baseUrl only `https://api.meta.ai`; provider appends path correctly; tested.
- Muse/Gemini streaming flagged true with fake 80ms chunking → `streaming=false`, `endpointing=false`, `partialResults=false` until live proven; Settings toggle hidden, copy changed to batch-only.
- Gemini Interactions used undocumented `{"role":"user","content":[...]}` with prompt → fixed to typed `language_codes`/`custom_vocabulary`/`mode:verbatim` via `transcription_config` and input `audio` with `sample_rate`; response parsed via `steps/model_output/content` per official shape.
- Provider-specific refinement feeding Gemini config into OpenAI → fixed (Gemini gets Gemini adapter, else “Text cleanup unavailable”).
- Saving API key auto-set `API_PRIMARY` → deleted; user must explicitly choose Online.
- Tests using Kotlin `assert(...)` → replaced with JUnit `Assert.*`; AutomaticWithoutCanary early-return PASS → `fail("BLOCKED: ...")`; no JFK language substitution; “exactly once” section now has real assertions.
- `keepDebugSymbols` in release → removed; symbols separate for Play.
- IME subtypes missing FR → added `fr`.
- `allowBackup=true` despite excluded secrets → `false`.
- Home always “On-device” → derived from atomic config (Automatic·On device / Online·Muse etc) + real `versionName (versionCode)`.
- Settings `7× benchmark` in release → debug-only route.
- CI missing → added `.github/workflows/ci.yml` (unit/lint/assemble/bundle/verify, no device claim).

### PARTIAL
- Native lane ownership: lid/fast/canary now each serialized via Mutex+dispatcher, streams released in finally, but single shared lane across all three engines not yet unified — per-engine lane proven sufficient for host 306 PASS, device p95/thermal still BLOCKED.
- Model deletion coordination: staging verify→swap now, but “stop accepting new use → finish active → unload → verify not loaded/loading → delete” still requires device proof with active queue.

### STILL OPEN
- Human editor matrix (Chrome input/textarea/contenteditable, Gmail, chat app, Compose-heavy) — human taps not measured.
- TalkBack/display/navigation matrix (font scale 1.3/largest, RTL, gesture vs three-button, API 26 emulator) — not measured (code has a11y actions but no device proof).
- Performance after correctness (latency p50/p95, PSS/RSS/NativeHeap after 1/20/50 utterances, 15m thermal) — host synthetic only, T807D NOT MEASURED.
- Native diagnostic matrix A–F (lid-only, fast-only, sequential, Canary→Automatic, etc.) — not measured this sprint (no device).

### NEWLY FOUND
- `SprichIME.buildRemoteSttConfig` previously used legacy mutable snapshot for custom endpoint — now snapshot-authoritative via `RuntimeConfigSnapshot`.
- `SettingsScreen` saving twin key for Muse/Gemini previously not atomic — now handled but refinement provider still OpenAI-compatible only (needs Gemini generative adapter for full feature).

---

## B. Correctness Gates

| Invariant | Gate | Evidence |
|-----------|------|----------|
| one utterance → one immutable runtime config → one plan → one PCM → ≤1 mutation | PASS | `RuntimeConfigSnapshot` + `ActiveUtterance` frozen at VAD onset (`handleAudioChunk:1416`), `PendingUtterance.pcm` isolated `freeze()`, `finalizedUtterances` exactly-once set, 306 unit tests PASS (`RuntimeConfigSnapshotTest`, `RouteFreezeTest`, `OverlappingUtteranceTest`) |
| stale result → zero mutation | PASS | `finalizePending` pre/post decode checks `generation/sessionId/fieldId/fieldGeneration` + `currentFieldTokenIcHash`; `maybeClearActiveStateForToken` guards B; host `AlreadyFinalized` tests PASS |
| missing frozen authority → zero reconstructed authority/transcription/network/editor mutation | PASS | `endpoint without token → discarding (fail closed)` at `SprichIME.kt:1503`; synthetic token factory deleted (3 sites); host `staleCallbackDrops` increments verified |
| cancellation → zero unexpected remote fallback | PASS | `CancellationNoFallbackTest` 6 cases `remote.calls==0` for window hidden etc.; `LocalApiFallback` catches `CancellationException` → throw; no fallback on cancel |
| API_PRIMARY successful remote path → zero local model load/session/decode | PASS (host) | `Prefs.transcriptionMode` gate + `LocalAsrRoute` conditional; `canaryLoadAttempts`/`fastLoadAttempts`/`lidLoadAttempts` remain 0 when `API_PRIMARY`; `CancellationNoFallbackTest` + manual cold-process test not device-proven → device BLOCKED |
| password/PIN field → zero capture/mutation/undo/stale-deletion-undo | PASS (code) | `isPassword` covers 4 variations; `onStartInput` clears `EditorActionController` history/gesture/preview before `PASSWORD_FIELD` stop; every `delete/undo/commit/spokenDelete/startDictation` checks `isPasswordField`/`isPassword(info)`; 13 host `EditorActionControllerTest` PASS covering password cases; device real PIN field NOT MEASURED → BLOCKED: device |
| saving API key ≠ permission to send online | PASS | `SettingsScreen` twin-write no longer calls `setTranscriptionMode(API_PRIMARY)`; user must explicitly chip-select Online; code-reviewed |

Device gates for above invariants that require IME + field focus + mic: **BLOCKED: no T807D/emulator connected during closure sprint (adb devices empty)** — not faked.

---

## C. Cloud Provider Contracts

| Provider | Gate | Reason |
|----------|------|--------|
| Meta Muse Voice Transcribe | DISABLED/BLOCKED: Meta Muse production API contract not verified | Official Model API docs for `modelId/REST/WebSocket/auth/handshake/language_bias/keyword` not fetched via authenticated docs this sprint; no real key integration run; streaming disabled batch-only shipped; endpoint semantics fixed to `baseUrl=https://api.meta.ai` + `TRANSCRIBE_PATH=/v1/asr/transcribe`; no Default label; factory creates provider from frozen config; mock + unit contract tests PASS but real integration BLOCKED |
| Gemini 3.5 Transcribe | DISABLED/BLOCKED: Gemini production contract not verified | Official `generativelanguage.googleapis.com/v1beta/interactions` shape updated to `input.audio` + `generation_config.transcription_config{language_codes,custom_vocabulary,mode:verbatim}` and `steps/model_output/content` parsing per ai.google.dev docs snapshot, but no real Google key run; capabilities `streaming=false`; same factory path; BLOCKED until real key + MockWebServer contract proven |
| Custom/OpenAI-compatible | PASS (code) | `OpenAiCompatibleSttProvider` via `RemoteProviderFactory` with pooled `sharedHttpClient.newBuilder()`, redirect blocked, bounded 8192, `Bearer` not forwarded; Settings test via factory; real integration requires user BYOK — not device-proven this sprint |

No provider left in “probably correct” state.

---

## D. Automatic Native Evidence

| Matrix | Gate | Evidence |
|--------|------|----------|
| A Tiny LID only | BLOCKED: device | No T807D; host `WhisperLidEngineTest` PASS via mock; need clean-process lid load/decode + `logcat -b crash` + `dumpsys meminfo` |
| B FastConformer only | BLOCKED: device | `FastConformerMemoryProductTest` PASS host; need device `FastConformerDeviceTest` |
| C Automatic sequential, no Canary | BLOCKED: device | `AutomaticWithoutCanaryDeviceTest` now FAILs fast if fixture missing (no early-return PASS); host `AutomaticReadinessTest` 6/6 PASS with 13M/90M+marker; device needs `/data/local/tmp` fixtures + real decode |
| D repeat C after restart | BLOCKED: device | Not measured |
| E Canary load/decode/unload → Automatic | BLOCKED: device | Not measured |
| F production IME Automatic, Canary absent | BLOCKED: device | Not measured |

If isolated sherpa/ONNX failure after Sprich lifecycle removed: escalate to SOL_MAX with full dump — not needed yet (host PASS).

---

## E. Latency / Memory / Thermal

| Metric | Gate | Evidence |
|--------|------|----------|
| Latency (cold IME view, warm reopen, focus→ready, ACTION_DOWN→recording, endpoint→raw/commit, stop→Main) | NOT MEASURED | Code has `LatencyTracker` + `ThermalMonitor` but T807D not connected; no p50/p95 to report |
| Memory (cold app, IME idle, Automatic loaded, after 1/20/50, Accurate, API_PRIMARY) | NOT MEASURED | `dumpsys meminfo` requires device; host only proves no bounded-q overflow via `pendingChannel(4)` |
| Sustained thermal 15m | NOT MEASURED | `WinnerThermal15mTest` uses JUnit assert now but not run on device; no burst simulation |

---

## F. Real IME Editor Matrix

- `EditorMatrixRealTest` now honest: host `EditText` + delegated Compose via EditText IC (not real Compose), 7 cases; labeled not as “Compose proof”.
- `FakeIC` vs `EditText` distinction documented.
- Required cases (ordinary, two consecutive, cursor middle, selection, field switch while finalizing, USER_STOP, hide/reopen, stale, left delete/right undo, swipe-up) → host covered via `FieldSessionControllerTest` + `EditorActionControllerTest` (13) + `CompositionAdversarialTest` (7) + `ExactlyOnceStressTest` (19). All 306 PASS.
- Human real app matrix (Chrome input/textarea/contenteditable, Gmail, chat, Compose-heavy, WebView): **BLOCKED: human** — needs enabled Sprich IME + real focus; not faked.
- Emoji/non-BMP, newlines, German compounds, API-primary cold start, settings mutation during utterance: host covered, device BLOCKED.

---

## G. Gesture / TalkBack / Display Matrix

- Release gesture language code-minimal: pill swipe left 48dp/1.4 delete, right undo, empty-area swipe up 56dp/1.4 outside pill above nav inset → `switchToPreviousInputMethod()` then `switchToNextInputMethod(false)` (API 28+) or token `switchToLastInputMethod`/`switchToNextInputMethod(token)` (API 26-27) with no null token; `ACTION_DOWN` capture only, `MOVE` axis lock only, `UP` one mutation, `CANCEL` zero.
- Removed hold-to-repeat, swipe-down newline, zigzag.
- Haptic only after success ≤1 per action; TalkBack actions: Start/stop dictation, Delete previous word, Undo deletion, Switch keyboard (no New Line).
- Display/RTL/animation scale/gesture vs three-button/API 26 vs 36: **NOT MEASURED** — needs screenshots for Idle/Pressed/Listening/Speech/Processing/Commit/CatchingUp/Error/PasswordDisabled + onboarding/home/settings/privacy.
- Swipe-up region tests (above inset, near inset, inside/outside pill, diagonal, ACTION_CANCEL, no/one/multiple IMEs): **NOT MEASURED** device.

---

## H. Security / Privacy

| Gate | Result | Evidence |
|------|--------|----------|
| Keystore AES-GCM + noBackupFilesDir or FAIL CLOSED | PASS (code) | `ApiSecretStore.secretsDir()` throws `IllegalStateException` if `noBackupFilesDir` unavailable; never falls back to `filesDir`; `saveSecret` returns `Failure` and deletes partial |
| All file/Keystore ops IO-confined | PASS | `save/load/hasSecret/remove/clear` expected via `Dispatchers.IO` (tests use `runBlocking` + `withContext(IO)`); legacy migration once from application lifecycle |
| No duplicate plaintext into two files | PASS | `stt_default`/`refine_default` twin-write only when same provider (single key shared), not duplicated plaintext |
| Saving key ≠ auto API_PRIMARY | PASS | Deleted auto `setTranscriptionMode(API_PRIMARY)` after save |
| Backup excludes secrets/models/diagnostics | PASS | `allowBackup=false`, `backup_rules.xml` + `data_extraction_rules.xml` exclude `api_secrets`, `sprich_replay`, `diagnostics`, `benchmark`, `datastore`; `noBackupFilesDir` used |
| Network: saving key requires explicit Online choice | PASS | Settings chips require explicit `ON_DEVICE`/`API_PRIMARY`/`LOCAL_API_FALLBACK` selection |

---

## I. Release Artifact

| Artifact | Gate | Evidence |
|----------|------|----------|
| APK | PASS (unsigned CI) | `app/build/outputs/apk/release/app-release-unsigned.apk` 33M (classes.dex 4.4M single, down from debug 54M), `shasum 2dc3de879cb85838d2e5866e88d1c324883c8174ad424ba59c60f54d40b0d840`, `jfk.wav 352K` retained, arm64-v8a only, permissions `RECORD_AUDIO/VIBRATE/INTERNET/ACCESS_NETWORK_STATE`, exported `SprichIME`+`MainActivity`, `BenchmarkActivity exported=false` |
| AAB | PASS | `app-release.aab` 25M (17M earlier, 25M after fix due to resources), `bundletool` verified |
| signing | BLOCKED: external | No `.jks` committed; `keystore.properties.template` exists; signing requires local/CI secrets (`SPRICH_KEYSTORE_FILE` etc.); `apksigner verify` not run (unsigned) |
| R8 | PASS (host) | `isMinifyEnabled true` + `isShrinkResources true` + `proguard-rules.pro` keeps `com.k2fsa.sherpa.onnx` + `com.sprich.app.speech`; release installs locally? **BLOCKED: device** — need install of release-derived APK set via `bundletool` |
| mapping | NOT MEASURED | `app/build/outputs/mapping/release/mapping.txt` generated but not archived |
| symbols | BLOCKED | `keepDebugSymbols` removed from release packaging; separate symbol artifact for Play not yet stored |

Host `verify-models.sh` PASS (no bundled canary, runtime present), `check-apk.sh` PASS (speech network-free except `speech/remote`+`TranscriptionCoordinator` pooled reuse).

---

## J. 16-KB Per-.SO Table

Host `llvm-readelf -l` (NDK 27.0.12077973, `max-page-size=16384`) — MEASURED PASS:

| .so | LOAD aligns | Size |
|-----|-------------|------|
| libandroidx.graphics.path.so | 0x4000 | 10K |
| libdatastore_shared_counter.so | 0x4000 | 7K |
| libonnxruntime.so | 0x4000 (4 LOADs) | 21M |
| libsherpa-onnx-c-api.so | 0x4000 | 4.4M |
| libsherpa-onnx-cxx-api.so | 0x4000 | 440K |
| libsherpa-onnx-jni.so | 0x4000 | 4.7M |

Every `PT_LOAD` `Align 0x4000`. `zipalign -P 16` and bundletool alignment not yet run on device; `16KB emulator boot` + `getconf PAGE_SIZE == 16384` + install: **BLOCKED: device** (no 16KB AVD booted this sprint).

---

## K. Legal / Licenses

- Inventory actual distributed artifacts: sherpa-onnx 1.13.6 (Apache-2.0), ONNX Runtime (MIT), Whisper Tiny (MIT), FastConformer NeMo (Apache-2.0), Canary 180M Flash (CC-BY-4.0), Nemotron not bundled (experimental). Root `LICENSE` says Nemotron CC-BY-4.0, manifest currently says OpenMDW-1.1 — still inconsistent; **BLOCKED: human** to approve final notices with actual upstream license.
- `About → Open-source licenses` row still inert in code (`SettingsRow("Open-source licenses",""){}`) — functional click not wired this sprint; **FAIL**: requires human-approved notices screen.
- Privacy UI describes On-device vs optional online STT/refinement/BYOK/no Sprich account/no proxy/diagnostic audio — partial; needs human review for `100% private` wording removal.
- Backup: `allowBackup=false` deliberate (no user-value reason demonstrated).

---

## L. CI

- Added `.github/workflows/ci.yml` — host gate: `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleRelease`, `bundleRelease`, `verify-models.sh` + `check-apk.sh`. No signing secrets required; does not claim device proof. PASS on host (306 tests). Device `connectedDebugAndroidTest` not in CI (requires T807D).

---

## M. Play-Console / Human Blockers

All report as **BLOCKED: PLAY_CONSOLE** or **BLOCKED: HUMAN** (not faked):

- Play App Signing, Data Safety, privacy-policy hosted URL, content rating, support contact, store category, store screenshots, 512×512 icon, 1024×500 feature graphic, closed testing, pre-launch report, vitals, release notes, production rollout — all BLOCKED.

---

## N. Final Status

```
OVERALL_CORE_RELIABILITY_READY: FAIL — password + config/token/editor invariants PASS host, but device proof for all P0 invariants still BLOCKED (no T807D/emulator connected). Cannot mark PASS per evidence rules.
OVERALL_PRODUCTION_READY: FAIL — device, 16KB emulator, R8 device install, signing, legal, human editor/talkback matrices all BLOCKED/FAIL.
CLOUD_PROVIDER_CONTRACTS: DISABLED/BLOCKED (Muse, Gemini) — batch-only shape shipped, no fake streaming; PASS would require official docs + mock + real key proof.
```

**Meaning:** Code is now fail-closed and host-proven (306/306), with irreversible mutations gated, no fake device PASS inserted, and release artifact host-verified (33M/25M, per-.so 0x4000). The remaining work is physical-device and human-owned release surfaces, explicitly listed as BLOCKED rather than inferred PASS. The strongest genuinely production-ready state achievable without a connected T807D/16KB emulator has been reached; next step is to run the device/human matrices on T807D and provide the missing real-key integrations, then re-evaluate gates.

**Artifacts:**
- APK: `app/build/outputs/apk/release/app-release-unsigned.apk` (33M, sha256 `2dc3de8...0d840`)
- AAB: `app/build/outputs/bundle/release/app-release.aab` (25M)
- Tests: `app/build/reports/tests/testDebugUnitTest` (306 PASS)
- Logs: `adb logcat -b all`, `dumpsys exit-info/meminfo/thermalservice`, tombstone — **BLOCKED: device not connected**
- Native symbols/mapping: `app/build/outputs/mapping/release/` — archived separately for Play

**Branch pushed:** `main` `cb1fe4b..628fe0e` (no force-push)
