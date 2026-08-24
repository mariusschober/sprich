# KNOWN LIMITATIONS — Sprich v1

## Not yet hardware-validated

All performance numbers below are synthetic mock or emulator. ** Real gate pending ** on €200-300 class device (6GB RAM, Snapdragon 680 / MediaTek G99, 60/90Hz).

- Warm field→capturing <100ms — instrumentation exists, but not measured on budget hardware.
- Speech→first visible <450ms — mock 320ms; real whisper Q5_1 decode on mid SoC may be ~400-600ms pending warm/threads tuning.
- RTF <0.5 — mock 0.2; need sustained 30s+ test with page faults, GC, binder.
- Vulkan vs CPU for Nemotron — Vulkan assumed fallback; no measurement yet, CPU is default.

## Models

- ** Whisper placeholder **: debug APK ships 179B placeholder. Release must fetch real `ggml-base-q5_1.bin` (57 MB) via `scripts/verify-models.sh RELEASE_CHECK=1`. Not committing binary to repo avoids license/size issues but requires CI step.
- ** Canary download **: URL is official sherpa GitHub tar, but SHA currently blank (not yet pinned to first verified hash). Pin before Play release.
- ** Nemotron **: experimental. GGUF Q4_K artifacts may need `nemo2gguf` conversion not scripted yet; current `DownloadManager.downloadNemotron` is mocked to show atomic pipeline, not real GGUF fetch. Needs HuggingFace `hf_transfer` style download + checksum once artifact finalized.

## Platform

- ** Accessibility companion ** experimental, labeled as such. Requires user to enable `SprichAccessibilityService` manually. Play accessibility policy is strict; cannot claim to work with Gboard until vetted. Mic in overlay mode uses `FOREGROUND_SERVICE_MICROPHONE` and must show notification; we do, but field-type detection via `AccessibilityNodeInfo` is less reliable than IME (WebView contenteditable, Compose semantics may miss).
- ** Keyboard coexistence **: no clean policy-compliant way to auto-mic while Gboard remains IME. Primary path is Sprich as IME; rapid switch via `showInputMethodPicker()` is the honest mechanism.
- ** NDK **: host has SDK but NDK 27 install timed out in CI (empty dir). Native `libsprich_whisper.so` is placeholder dummy.cpp; real whisper.cpp build needs `sdkmanager --install "ndk;27.0.12077973" "cmake;3.22.1"` and `app/src/main/cpp/CMakeLists.txt` updated to point at `native/whisper`. Debug builds succeed via mock; release with real inference will require that step.

## Product

- ** Personal vocab ** UI is stub (Settings row does nothing beyond placeholder). Store exists (`PersonalVocabStore`, `VocabRepository`) but no management screen, no trie compilation ahead of session.
- ** Learn my corrections ** off by default and not yet functional beyond flag; storing mappings is scaffolded but not triggered from composition diff.
- ** Numeric / ITN **: deterministic `SpokenEditingParser` does basic punctuation + email/URL; aggressive `€25` style ITN is conservative (prefers literal). Needs German/Spanish number coverage more thorough.
- ** Deletion commands **: `delete that` deletes 40 chars, `delete last sentence` 120 chars via `deleteSurroundingText`. That's heuristic, not semantic sentence boundary.
- ** Thermal/power **: no sustained battery test, no wake-lock accounting, no `onTrimMemory` stress with real model mapped. Monitor exists but not wired to adaptive quality (e.g., auto fallback to Fast if queue backs up).

## QA matrix

Manual app interoperability matrix listed in `docs/TEST_REPORT.md` not yet executed; automated instrumentation covers only FakeIC delta, not real `InputConnection` on Chrome/WebView. Clipboard fallback is intentionally not primary; if some app refuses `setComposingText`, we currently do not try clipboard (per privacy). Needs graceful fallback later.

## What is shippable now

- Builds reproducibly, debug + release (unsigned), no analytics, privacy-gated.
- Full IME with Instant/Tap, password guard, haptics, composition without duplication (unit-tested), speculative decode via mock, model switch with unload, download manager with atomic/verify/cancel, settings + onboarding + benchmark screens, dark/light, perf instrumentation skeleton.
