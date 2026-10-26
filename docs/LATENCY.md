# Performance measurement

Performance acceptance uses the physical TCL T807D. Emulator timing, host benchmarks and synthetic state-machine tests cannot establish phone latency, thermals or battery life.

The acoustic harness plays known public sentences through the Mac speaker into the phone microphone. The production IME inserts into a separate app's real `EditText`. Each measurement is playback completion to the editor callback and includes endpoint delay, recognition, post-processing and insertion. Host/device monotonic clock calibration records USB timing uncertainty. The first three utterances are warm-up samples. This method is not a spontaneous-speech WER corpus.

A sustained run requires at least 200 utterances and 30 minutes, with zero duplicate, stale, cross-field or password mutations. Capture `dumpsys meminfo`, native heap, CPU, battery/charging conditions and `dumpsys thermalservice` before, during and after the run. Report distributions, growth and recovery; USB-charged thermal evidence is not an unplugged battery-life estimate.

Native fixture checks additionally report model inference time, process CPU time, PSS and native heap through the installed artifact's JNI. They establish native/runtime behavior and can compare the same device and fixture. Debug baseline comparisons must be labelled; they do not establish a release-to-release end-to-end latency improvement.

Implementation deliberately waits for a pause, serializes native work and preserves editor authority. It does not trade these safeguards for a shorter benchmark. Local speech classification uses the bundled Silero detector; energy-only classification retained for experimental remote mode is not a production acoustic acceptance result.

Current numbers, artifact hashes, device conditions and limitations belong in [release/REVIEW.md](../release/REVIEW.md), not permanent latency promises.

## Measured 2026-10-26 (release `1.0.0-rc20`, TCL T807D, Android 16)

Method: 30 runs of a 2 s public speech clip played from the Mac speaker into the TCL microphone
through the release QA build's production IME (Whisper Tiny LID + Canary) into the QA editor's
real `EditText`. Latency = `afplay` return (end of played speech) to the first non-empty editor
text, polled every 25 ms; polling contributes up to one interval. One warm-up capture discarded.
This is loudspeaker playback, not human speech, and not an endpoint-detector benchmark.

- **p50 = 2,427 ms, p95 = 2,489 ms** (28/30 insertions; two playback/acoustic misses).
- Components measured on the same build: Whisper LID p50 = 679 ms per utterance (200 frozen
  clips), Canary decode p50 = 1,651 ms for the frozen public corpus; the remainder is endpoint
  delay, typography and insertion.

Changes tried, one at a time, measured by the same harness:

| Change | p50 | p95 | Decision |
|---|---:|---:|---|
| Baseline (Canary threads = 2) | 2,427 ms | 2,489 ms | — |
| Canary decode threads = 4 | 2,432 ms | 2,563 ms | **reverted**: no ≥15% p50 improvement |
| Warm load at keyboard open | not separately changed | — | the production IME already loads the route's engines when its input view appears (startDictation), so there is no separate steady-state p50 win to keep |
| Parallel LID on the first 1.75 s of speech | not implemented | — | review stopped before the code change; measured LID p50 is 679 ms (28% of end-to-end p50), the maximum possible saving, but end-to-end change is NOT MEASURED |

Memory: Canary warm PSS measured at 565,900 KiB in the 2026-10-25 frozen run; process PSS and
native heap on the 16 KB emulator native check were 385,270 KiB / 432,605,952 B for Canary
(runtime evidence, not phone PSS).

These numbers are true for this harness; they do not promise a latency SLO on other devices or
with human speech.
