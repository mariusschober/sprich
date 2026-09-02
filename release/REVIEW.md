# Independent release review — 2 September 2026

Current distribution target: **Zapstore beta**. The publisher deferred Play and F-Droid work and asked to close the broad testing campaign.

**Public distribution: BLOCKED: no permanent Android signing key or Nostr publisher identity has been designated; human model/runtime redistribution review remains incomplete.** Metadata, screenshots and a local APK workflow are prepared. Nothing has been published to Zapstore or uploaded to Blossom.

## Provenance

- START_SHA: `671f88e80d6ecb2588941acf673c5312cb278be1`, freshly fetched and inspected.
- Core implementation commit: `b7e2c81044b92b897a8ac6400105c7e9e83beb00`.
- Landscape correction: `74d0e0e`.
- The final packaging commit, exact END_SHA, clean-source build, APK hashes, certificate and final installation/native checks are recorded in the local `release-output/artifact-manifest.json` and `release-output/qualification.json`. These generated files are outside Git to avoid a self-referential build hash.
- The campaign used non-debuggable, R8-shrunk release builds signed with the local Android **QA/debug certificate**. “Release build” does not mean production signing.
- Candidate 6: 1.0.0-rc6 (81), APK SHA-256 `c272b1b7c6830d2ce93ada8b8523ab52a67de553d59e042c94df1b5f79f18a84`.
- Candidate 8: 1.0.0-rc8 (83), APK SHA-256 `36d33ff46515c188f77dd8f27fa2bf94301139df1dcc2051b2068e609491b19b`.
- Public aggregate evidence: [device-summary.json](evidence/device-summary.json), [real-editor results](evidence/tcl-editor-result.json), [Chrome results](evidence/s23-chrome-result.json). Digests of private raw evidence are retained in [raw-evidence-digests.txt](evidence/raw-evidence-digests.txt). Personal notifications and incidental speech are not published.

The candidate hashes above identify the actual builds exercised during the campaign. Later packaging/source changes are not retroactively attributed to those tests. The final artifact's narrow checks do not repeat or replace the earlier sustained run.

## 1. Most important problems found

1. Shared recorder shutdown could interfere with a successor capture; negative audio-read results could continue looping. Native cleanup could also cross an IME ownership boundary.
2. Mutable preferences and vocabulary could disagree with a captured utterance plan. Missing plans were reconstructed instead of discarding work without authority.
3. Editor history lacked usable selection/context authority. Manual cursor changes, ambiguous mutation return values and duplicate tap handling created opportunities for unintended edits.
4. Fresh model downloads rejected GitHub's real HTTPS redirect. Readiness markers and replacement behavior did not establish a fully verified, recoverable installation.
5. Shared legacy credentials were not bound to the chosen provider/endpoint. Partial clearing and late work could retain or recreate data. Diagnostics exposed more text than needed.
6. A physical acoustic run exposed merged utterances under the old energy detector: 45 cues produced 42 mutations. Existing green tests had not established this behavior.
7. The large native dependency bundled unused speech synthesis and its license surface. Old setup/benchmark material and permissive gates overstated current release evidence.
8. The final landscape screenshot exposed Android's unnecessary full-screen extracted editor covering the original app.

## 2. Changes and reasons

Capture now owns its recorder, worker and retirement. Audio errors stop safely; blocking capture/native operations stay off Main. Native work is serialized within an IME owner.

Utterances carry immutable config, plan and PCM authority through transcription, written-text processing, vocabulary and spoken commands. Missing authority discards work; cancellation cannot select a fallback.

Editor mutations use current selection and exact surrounding context. Manual movement cancels pending dictation and history; successful Sprich edits recognize their own callbacks. Ambiguous mutation results consume authority rather than retrying. Password/PIN fields cannot record or mutate.

Downloads have one process-wide owner, per-model coordination, restricted unauthenticated GitHub redirects, archive hashes, bounded extraction, install receipts and atomic staging/rollback. Interrupted work restarts cleanly. Existing installations survive failed replacement.

Credentials use encrypted atomic storage and provider/endpoint binding. Legacy unbound keys require re-entry. Clearing data uses Android's application-data clear operation to terminate pending work as well as erase files. Every production cloud provider is centrally disabled because live production-path evidence was unavailable.

A small bundled Silero detector replaced energy-only endpoint decisions after the physical failure. It uses the existing runtime with one worker thread. Capture haptics that could become microphone noise were removed. The compact voice bar now stays with the real editor in landscape.

## 3. Simplification and removal

Removed incomplete download resume, missing-plan reconstruction, competing configuration mirrors, redundant audio copies and duplicate tap invocation. Removed inert privacy/thermal helpers and misleading replay comparisons.

Developer recording, fixtures, benchmarks and experimental provider controls are outside production artifacts. Removed obsolete Whisper build/setup instructions. Removed unused TTS, diarization and C API components from the native runtime.

The ASR AAR is about 9.5 MiB versus the previous 49,097,942-byte AAR. Four native libraries remain. The bundled detector is 643,854 bytes. These are package measurements, not a battery-life claim.

## 4. Device and editor stress evidence

| Scenario | Build/device | Result |
|---|---|---|
| 200 consecutive EN/DE acoustic cues, 33 min 37 sec | Candidate 6, TCL T807D, Android 16 | **PASS**: 200 actual editor mutations, no extra/duplicate, cross-field, deletion or delayed extra mutation |
| Strict expected-word check in that same run | Same build and run | **FAIL**: 194/200 cues matched; six English cues did not |
| 100 rapid lifecycle/control cycles | Candidate 6, S23 Ultra, Android 16 | **PASS**: observed microphone starts/stops, field/hide/password/PIN/cursor/background changes; zero unexpected editor events |
| Automatic French and Spanish | Candidate 8, TCL | **PASS**: physical microphone, expected text, one editor insertion per cue |
| Accurate English, Spanish and French | Candidate 8, S23 | **PASS**: physical microphone and actual editor |
| Controlled one-cue Accurate German | Candidate 8, S23 | **NOT MEASURED**: the user confirmed additional live speech during this sample |
| Chrome input, textarea, contenteditable and password | Candidate 8, S23 | **PASS**: real DOM events, one insertion in each normal field, no password audio/mutation |
| WebView multiline | Candidate 8, TCL | **PASS**: actual WebView input event |
| Unicode word deletion/undo around a skin-tone ZWJ emoji | Candidate 8, TCL | **PASS**: complete emoji preserved and exact text restored |
| Manual cursor movement invalidates undo | Candidate 8, TCL | **PASS**: zero subsequent mutation |
| Speech followed immediately by field change | Candidate 8, TCL | **PASS**: microphone stopped, zero editor events |
| Configured mail composer | Physical devices | **BLOCKED: no configured mail composer was available** |

The long-run strict failures were at cues 54, 78, 110, 136, 148 and 196. They include misspellings of “notebook” and an extra fragment. They were not reclassified as passing. The user-confirmed live speech applies to the separate German S23 sample, not these six TCL cases.

Earlier failed setup/control runs and the phone-lock interruption remain in raw evidence. The completed run used an awake QA host with real Android EditText and the production IME. Fake InputConnections and direct native fixtures are not counted as physical-editor proof.

## 5. UI, UX and accessibility

Reworked Home/setup around Automatic and Accurate, truthful offline readiness, one Automatic download action and actionable permission/download errors. Production copy is in resources. Added a visible keyboard-switch control, state-correct accessibility labels/actions, conservative gesture cancellation, a minimum touch height and readable system-navigation colors. Removed technical production setup surfaces and weak/inert controls.

**PASS, bounded scope:** physical light/dark review, 200% font and larger display settings, app RTL layout, TalkBack service with touch exploration enabled, microphone start/stop and opening the system keyboard picker. Three-button controls were visibly usable in the final pre-fix S23 capture.

The landscape capture produced a concrete fix: disable the framework's extracted full-screen editor for this compact IME. Final artifact verification records the post-fix screenshot separately.

**NOT MEASURED:** exhaustive TalkBack custom-action menu/speech-output audit, every assistive technology or OEM display variant. Screenshots are evidence for the captured state only. Modified S23 display/accessibility/navigation settings are restored as part of the final device handoff.

Five clean release screenshots, the actual app icon, optional promotional artwork and calm product copy are prepared for Zapstore. The carousel includes the real keyboard, not just setup screens. [Asset provenance](store/README.md).

## 6. Security, privacy and red-team results

**PASS:** targeted regressions for missing/stale authority, ambiguous editor mutation, cursor cancellation, Unicode boundaries, secret binding and storage revision invalidation. No production provider can bypass central availability. Saving credentials does not enable online transcription/refinement.

**PASS:** 200 physical utterances with Wi-Fi/mobile data disabled, using locally installed models. Release logs sampled during that run contained no controlled transcript terms. This is not a whole-device packet-capture claim.

**PASS:** disposable-emulator corruption, low-storage failure, interrupted installation rollback and release-UI download cancellation. A held active download plus saved vocabulary/cache/preferences was followed by Clear Sprich data: process terminated, files were removed, onboarding returned and old work did not recreate data.

**PASS:** backup and device-transfer exclusion rules cover all applicable storage domains; manifest disables backup and cleartext transport. APK contents exclude QA editors, test audio and developer recording controls.

**NOT MEASURED:** live cloud-provider success, compromised/modified Android, or a complete external penetration test. Mock providers demonstrate failure handling only. Human license approval is not a security test.

## 7. Performance, memory and thermal evidence

The completed TCL run used the same physical phone and real microphone/editor path. Excluding three warm-up cues, 197 samples measured playback end to editor callback, including endpointing:

| Metric | Measured value |
|---|---:|
| Median | 556.48 ms |
| p95 | 1,074.56 ms |
| p99 | 1,111.03 ms |
| Maximum | 2,044.28 ms |
| Maximum measured host/device clock uncertainty | 72.48 ms |
| Warm PSS range | 505,944–515,767 KiB (about 494–504 MiB) |
| Warm native heap, first → last sample | 511,135 → 511,752 KiB |
| PSS after hiding IME | 502,979 KiB |
| Sampled Android thermal status | 0 in every sample |
| Battery temperature range | 35–37 °C |

**PASS** for completion without persistent growth or thermal escalation in this run. Android rolling CPU windows ranged from 28% to 57%; these are not integrated CPU or energy measurements.

**NOT MEASURED:** unplugged battery life, rigorous same-device before/after latency distributions, integrated CPU/power profiling and long spontaneous/representative accuracy testing. The phone was USB powered. No host benchmark is presented as device performance.

## 8. Release artifact and 16 KB evidence

**PASS:** candidate 8 host gate, 323 unit tests with zero failures/errors/skips, release lint, R8 APK/AAB builds, dependency/input verification and actual package verification. The original baseline had 306 passing tests. Required CI failures are no longer suppressed.

**PASS:** packaged ARM64 ELF LOAD alignment of at least 0x4000, 16 KB ZIP alignment, RELRO and non-executable stacks for all four libraries. **PASS:** candidate 6 native VAD/LID/FastConformer/Canary execution through installed, R8-shrunk device-specific APKs on API 26 and the API 36 ARM64 16 KB emulator. The latter reported PAGE_SIZE=16384 with compatibility fallback disabled. Native fixtures are not physical microphone evidence.

The final runner explicitly asserts the expected page size; the final package's check outputs are retained alongside its artifact manifest. The release includes separate R8 mapping, native symbol metadata and the available unstripped JNI symbols. Complete upstream symbols are not available for every prebuilt.

**PASS:** installed `zsp 0.4.17` parsed the same listing configuration using candidate 8's QA APK and reported `com.sprich.app`; icon/screenshots were inspected. **BLOCKED: production signing/certificate-to-Nostr linking is not configured.** AAB/Play-generated APK work is deferred under the new Zapstore scope.

## 9. Remaining publisher and external steps

- **BLOCKED:** permanent Android app signing key creation/backup was deferred.
- **BLOCKED:** the publisher's Nostr public identity/signer has not been supplied; repository verification and certificate linking cannot be completed.
- **BLOCKED:** human review of actual runtime/model redistribution terms, particularly NVIDIA NGC FastConformer, is incomplete.
- **NOT MEASURED:** Zapstore relay acceptance, uploaded asset availability, delivered-APK installation and updates signed by the permanent key. No publication approval has been given.
- Play account administration, Data Safety, Play signing/upload and F-Droid source-chain/inclusion work are deferred by the publisher and do not govern the current Zapstore preparation.

The exact next steps and commands are in [SUBMISSION.md](SUBMISSION.md); first-time signing is explained in [SIGNING.md](SIGNING.md).

## 10. Final verdict

**Zapstore preparation: PASS** for listing copy, actual UI assets and configuration acceptance with the QA artifact.

**Public distribution: BLOCKED** by the specific signing, identity and human redistribution-review steps above. An unconditional production-readiness PASS is not supported: the six strict text-fixture failures and explicitly unmeasured cases remain visible.

The repository now has stronger capture/editor/download ownership, a materially smaller native runtime, production cloud isolation, clearer UI, mandatory gates and physical acceptance evidence. The handoff is a prepared beta release candidate with explicit limits. Another broad stress campaign is not scheduled.
