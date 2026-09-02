# Performance measurement

Performance acceptance uses the physical TCL T807D. Emulator timing, host benchmarks and synthetic state-machine tests cannot establish phone latency, thermals or battery life.

The acoustic harness plays known public sentences through the Mac speaker into the phone microphone. The production IME inserts into a separate app's real `EditText`. Each measurement is playback completion to the editor callback and includes endpoint delay, recognition, post-processing and insertion. Host/device monotonic clock calibration records USB timing uncertainty. The first three utterances are warm-up samples. This method is not a spontaneous-speech WER corpus.

A sustained run requires at least 200 utterances and 30 minutes, with zero duplicate, stale, cross-field or password mutations. Capture `dumpsys meminfo`, native heap, CPU, battery/charging conditions and `dumpsys thermalservice` before, during and after the run. Report distributions, growth and recovery; USB-charged thermal evidence is not an unplugged battery-life estimate.

Native fixture checks additionally report model inference time, process CPU time, PSS and native heap through the installed artifact's JNI. They establish native/runtime behavior and can compare the same device and fixture. Debug baseline comparisons must be labelled; they do not establish a release-to-release end-to-end latency improvement.

Implementation deliberately waits for a pause, serializes native work and preserves editor authority. It does not trade these safeguards for a shorter benchmark. Local speech classification uses the bundled Silero detector; energy-only classification retained for experimental remote mode is not a production acoustic acceptance result.

Current numbers, artifact hashes, device conditions and limitations belong in [release/REVIEW.md](../release/REVIEW.md), not permanent latency promises.
