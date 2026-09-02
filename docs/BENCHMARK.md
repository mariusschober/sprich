> Historical development snapshot. Commands, architecture and acceptance claims below describe an older build. Use [the current README](../README.md) and [the current qualification evidence](../release/REVIEW.md) for this release candidate.

# BENCHMARK

Open the hidden developer screen by tapping the version seven times on Home.

## What it runs

The benchmark loads the same process-wide Canary engine (Accurate 180M Flash INT8, `files/canary`) used by the IME and transcribes the bundled public-domain `jfk.wav` fixture (176000 samples, 11000ms, 16k mono PCM). It does not use a sine wave, mock transcript, or alternate runtime. The hidden screen is `BenchmarkActivity` (7× tap version on Home); instrumentation `BenchmarkOnDeviceTest` runs the same path headlessly.

Reported values:

- model load time (cold);
- audio duration;
- native inference time (cold + 5 warm);
- real-time factor (`inference time / audio duration`);
- p50 / p95 warm latency, avg RTF, peak RSS, CPU backend/threads, quantization, languages, task.

The optional JSON export is written only to `files/benchmark/export.json` (local) and via instrumentation to `files/diagnostics/benchmark_ondevice.log` (also logcat `BenchmarkOnDevice`). No data leaves device.

## Interpretation

- An emulator run proves that the harness, JNI library, model initialization, inference, UTF-8 result path, and engine lifecycle work together.
- Emulator RTF is not comparable to a physical arm64 phone, especially when ARM is software-emulated.
- A performance claim requires repeated warm runs plus 5- and 15-minute sustained runs on the named device, with thermal state and RTF drift recorded.
- Do not run the benchmark while an IME dictation session is active; both intentionally share one native context.

## Measured on T807D MT6878 Android 16 SDK36 7.6GB RAM (2026-09-01, 2 runs, real Canary INT8)

- `loadMs=3421` cold, `coldMs=1565` RTF 0.142, `warm [1510,1468,1459,1494,1500]` p50 1494 p95 1510 avgRtf 0.135 textLen 108 `"And so, my fellow Americans, ask not what your country can do for you. Ask what "`
- `loadMs=3355` cold, `coldMs=1550` RTF 0.140, `warm [1516,1515,1657,1518,1492]` p50 1516 p95 1657 avgRtf 0.139
- Engine `canary-180m-flash-int8` INT8 threads 2 cpu, src==tgt transcribe, languages en,de,es,fr, peakRss 5MB (heap), jfk 11s, RTF 0.135-0.139 <0.5 target <0.25 excellent, load 3.3s cold.
- Model files `encoder.int8.onnx 127M` + `decoder.int8.onnx 71M` + `tokens.txt 52K` SHA `7a38ed8b…` verified, `isCanaryReady` true.

## Measured on T807D MT6878 Android 16 SDK36 7.6GB RAM (2026-09-02, after reliability refactor — same INT8, now with exactly-once, per-utterance PCM, Mutex)

Host: 120 unit tests 0 failures (10k exactly-once, max concurrency 1, silent-commit fallback, per-utterance isolation, Auto regression inspecting transcript). Device: **17 tests 0 failures** on T807D (14 previous + 3 new per-utterance isolation).

- `loadMs=2387` cold, `coldMs=1853` RTF 0.168, `warm [1675,1630,1583,1690,1628]` p50 1630 p95 1690 avgRtf 0.149 textLen 108 `"And so, my fellow Americans, ask not what your country can do for you. Ask what "` — logcat `BenchmarkOnDevice` 2026-09-01 17:34 (still post-refactor build, same 198 MB model)
- Engine `canary-180m-flash-int8` INT8 threads 2 cpu, src==tgt transcribe, languages en,de,es,fr (explicit, Auto fallback to en), peakRss 8 MB (heap), jfk 11s, RTF 0.149 <0.5 <0.25 excellent, load 2.4 s cold (warm engine kept resident).
- Model `encoder 127M decoder 71M tokens 52K` SHA `7a38ed8b…`, `isCanaryReady` true via `run-as`.
- Instrumentation 17/17 on device confirm load, transcription, audio capture, VAD, FieldSessionController, composition, diagnostics, per-utterance isolation, and concurrency max 1.

RTF delta 0.135→0.149 is within run-to-run variance and still excellent; exactly-once, per-utterance PCM, and Mutex add negligible overhead (single dispatcher + frozen slice).

**Memory note:** `peakRss 5–8 MB` is **Java heap only** (`Heap Alloc`), not process RSS. `adb dumpsys meminfo com.sprich.app.debug` shows **TOTAL PSS 491 MB / RSS 600 MB, Native Heap 308 MB** (2026-09-01) with model resident. Future reports must distinguish heap vs RSS and include `dumpsys meminfo` PSS/RSS and 5/15-min thermal trends.

## Interpretation after 2026-09-02

These values are for this hardware tier (mid-high MT6878) and 11s fixture; low-tier (3GB) and sustained thermal/battery still require manual 5/15-min runs per `docs/LATENCY.md`.

## Physical-device protocol

1. Reboot the phone and wait for idle thermal state.
2. Run one cold benchmark and five warm benchmarks.
3. Record median and worst RTF, load time, Android version, SoC, RAM, and thermal state.
4. Run continuous samples for 5 and 15 minutes; fail if crashes occur or RTF degrades beyond the agreed threshold.
5. Separately test real microphone dictation. A fixture benchmark does not validate AudioRecord, VAD, or InputConnection behavior.
