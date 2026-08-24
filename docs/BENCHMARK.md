# BENCHMARK

Hidden developer screen: tap version 7× on Home → Benchmark.

## Metrics

- deviceModel, androidVersion, SoC, RAM, engine, quantization, backend, threads
- modelLoadTime (cold/warm)
- audio→VAD latency
- speechOnset→firstHypothesis / firstVisibleText
- endpoint→final, textCommitted
- RTF = processingMs / audioMs (sustained, not burst)
- peak RSS (native heap + mapped model)
- avg CPU, GPU/backend, temperature, throttling flag, dropped chunks, transcript revisions, stable-prefix revisions
- p50/p90/p95 over runs

## Methodology

- Same synthetic audio for all engines: 5s clips EN/DE/ES, quiet/street/car, male/female, fast/slow, hesitations. Stored in `testAssets` not APK.
- Real device required for thermal/power claims; emulator only validates pipeline. Mark physical gate.
- Marks use `SystemClock.elapsedRealtimeNanos()` monotonic.
- Bench runs don't need mic: bundled deterministic PCM fed directly to engine.

## Adaptive recommendation

On first launch, run 10s synthetic on `Dispatchers.Default` (no mic):
- if Canary RTF <0.7 → recommend Accurate
- if Nemotron RTF <0.5 with 160ms chunks → recommend Streaming
- else Fast

Never auto-download; recommendation only.

## Export

`Export JSON (local only)` writes `files/benchmark/export.json`:
```json
[{"engine":"whisper-base-q5_1","rtf_avg":0.22,"firstPartialMs":320,"backend":"cpu","threads":4}]
```
No transmission.

## Report (measured on CI emulator, placeholder)

Because CI is macOS emulator without physical budget phone, values are synthetic mock from `WhisperEngine` mock. Real measurements need `Pixel 4a`/`Galaxy A32` class device.

```
Device: emulator arm64
Engine: Whisper Base Q5_1 mock
RTF: 0.2 (mock, real target <0.5)
First partial: 320ms mock (target <450 stretch 300)
Final: 420ms mock (target <500)
Peak RSS: 180 MB mock
Backend: CPU 4 threads
Thermal: normal (emulator)
```

Run `adb shell cmd package compile -r bg-dexopt` before sustained tests. For thermals: 30s/5m/15m/30m continuous dictation, track RTF drift >1.1 fails.
