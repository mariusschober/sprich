# Device Evidence — S23 (SM-S918B) 2026-09-06

Branch `agent/sprich-integrity-sprint`, commits through `287f6c3`.
Method note: `:app:connectedDebugAndroidTest` uninstalls the app (wipes
`/data/data`, incl. provisioned models — verified by marker probe), so all
device gates below ran via direct
`adb -s R3CW404GVBL shell am instrument` after one `adb install` of each APK.
No Gradle lifecycle between provision and test.

## Device
- Model: SM-S918B (S23 Ultra), qcom SoC, Android 16 (SDK 36), PDA S918BXXSAFZG1
- Battery 100% (status 5), temp 40.9 °C at benchmark time; Wi-Fi on, screen on
- Second device T807D appeared mid-session; all commands pinned with `-s R3CW404GVBL`

## App
- Package `com.sprich.app.debug`, versionCode 99, versionName 1.0.0 (debug-signed SDK key; release stays unsigned per decision)
- Unsigned release APK (v98): `app-release-unsigned.apk`, 29,412,457 bytes,
  SHA-256 `7e5e1d8df4e9bf22acce4337e2bfe52777c77b6750be8d0e9763f794464a2f98`
- Host gates on the same source: `:app:testDebugUnitTest` 539/539,
  `:app:lintRelease`, `:app:assembleRelease`, `:app:bundleRelease`,
  `:app:writeReleaseDependencyInventory`, `check-apk.sh`, `verify-models.sh`,
  `verify-locales.py` — all PASS (two sprint fixes required: sign-stickiness
  in `CriticalTokens`, named zero-mutation constant for the locale gate)

## Models (all public GitHub sherpa-onnx artifacts, provisioned on-device through the production DownloadManager flow)
- accurate (Canary 180M Flash INT8): download+sha256+extract+receipt verified on device
- lid (whisper-tiny, 116 MB archive), fastconformer (14288 INT8, 103 MB archive): same
- No NGC credentials needed for any of these three packs

## Fixtures (`/data/local/tmp`, natural archive speech)
- en-english.wav 31,808 B `bdf1efef…`, de-german.wav 204,008 B `acc1c8c6…`,
  es-spanish.wav 78,380 B `bed36cae…`, fr-french.wav 114,564 B `ec244a7c…`
  (from `sherpa-onnx-nemo-fast-conformer-ctc-*-int8.tar.bz2` test_wavs; same
  set as the 2026-09-02 T807D run)
- A mid-session detour synthesized DE/ES/FR via macOS TTS and staged them, then
  REPLACED them with these naturals once found; no result below rests on synth audio.
  (On-device Google TTS `synthesizeToFile` silently wrote nothing — background
  voice-pack download presumably blocked; documented, not retried.)

## Device gates (direct instrument, S23)

| Class | Tests | Verdict |
|---|---|---|
| PipelineCorrectnessDeviceTest | 12 | OK (real Canary engine) |
| FreshInstallDeviceTest | 1 | OK — deletes all, re-downloads canary+lid+fast for real, relaunch survival, delete/re-download cycle (repaired: was copy+setReady simulation, could only pass vacuously) |
| LidDeviceTest | 7 | OK — EN/DE/ES/FR incl. alternation 8/8, whisper/noise/short/rapid all correct |
| WhisperNativeInstrumentedTest | 3 | OK |
| FastConformerDeviceTest | 1 | OK — JFK non-blank |
| ContinuousWinnerDictationTest | 1 | OK — 10 EN + 10 DE through winner |
| DevicePerUtteranceIsolationTest | 1 | OK |
| QueueActorStressDeviceTest | 3 | OK |
| EditorMatrixRealTest | 6 | OK |
| ImeDeviceValidationTest | 2 | OK |
| AudioDeviceValidationTest | 2 | OK |
| AutomaticWithoutCanaryDeviceTest | 2 | OK — Auto works with canary deleted (lid+fast), exactly-once isolation; accurateExplicit transcribes natural DE len=93 (repaired: verify-from-disk + teardown restore; removed receipt-less copy-restore that destroyed valid installs) |
| RealNetworkDownloadTest | 1 | OK (278 s production download cycle) |
| BenchmarkOnDeviceTest | 2 | OK |
| WerCerBenchmarkDeviceTest | 1 | OK |
| ComparativeBenchmarkDeviceTest | 1 | OK |
| FastConformerMemoryProductTest | 1 | OK (burst done; PSS/RSS + thermal NOT MEASURED — no DUMP permission, meminfo parse empty) |
| WinnerThermal15mTest | 1 | OK — 15 min sustained (mid-run sample: utt=1000 @ 485 s, avg 284 ms, min 153, max 838, no degradation trend; final numbers lost to logcat rotation) |

Combined record run: 47/47 in one process (all non-destructive classes).

## Benchmarks (S23, battery 100%, 40.9 °C)
- Canary JFK 11 s: load 1890 ms, cold 1556 ms (RTF 0.14), warm ≈1404–1468 ms (RTF ≈0.13)
- FastConformer JFK: load 1329 ms, first 423 ms (RTF 0.038), warm ≈399–418 ms — ~3.6× faster than Canary
- WER (raw output, local only; es/fr references are 3-word stubs so es/fr >1 are reference artifacts — actual outputs are correct full sentences):
  jfk C 0.09 / B 0.05, en C 0.33 / B 0.0, de C 0.6 / B 0.5, es 2.33, fr C 5.0 / B 3.5
- LID correct on all four fixture languages; blank rate 0.0 everywhere; no hallucinations flagged
- 60-utterance burst: 3939 ms total; whisper/short-utterance probes non-blank

## UX evidence (`docs/implementation/evidence-s23/`)
- `onboarding-1-de-dark.png` + `.xml`: DE setup step 1/5 renders correctly (dark)
- `onboarding-1-de-light.png`: same state, light theme via system toggle
- A11y flag: action labels expose clickable:false on the text node (parent handles taps) — TalkBack focus order still needs the manual hand-check

## Privacy scrub (runbook §6/§11)
- Production logs carry utterance metadata only (token IDs, counts, language tags); the single transcript-content log found interpolates `finalText.length`, not content
- Debug test-code logs fixture transcripts at INFO (internal-testing only; scrub-before-attaching rule applies)
- No secrets/keys in Warn+ logcat (one `Key=null` is GMS-internal noise)

## Still BLOCKED / deferred (exact unblock)
- NemotronBenchmark: models absent by design (2× 475 MB). Unblock:
  `adb -s R3CW404GVBL shell am instrument -w -e class com.sprich.app.NemotronBenchmarkDeviceTest …`
  after provisioning both packs (add `downloadNemotron560/160` to a scratch provision run first; ~25 min total, 59 GB free confirmed).
- 1000-utterance acoustic corpus (E01 full): no natural multi-speaker corpus on hand; fixtures above cover 4 languages × 1 speaker.
- E2E voice dictation (speak → committed text): needs a human voice at the S23 mic.
- Signed release install: no signing identity (staying unsigned per decision); update/rollback path untested.
- TalkBack/200%-font/RTL/landscape matrix: manual hand-check on device.
- Wispr comparison: no credentials or harness in-repo.
