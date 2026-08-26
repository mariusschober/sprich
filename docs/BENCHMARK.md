# BENCHMARK

Open the hidden developer screen by tapping the version seven times on Home.

## What it runs

The benchmark loads the same process-wide Fast engine used by the IME and transcribes the bundled public-domain `jfk.wav` fixture. It does not use a sine wave, mock transcript, or alternate runtime.

Reported values:

- model load time;
- audio duration;
- native inference time;
- real-time factor (`inference time / audio duration`);
- CPU backend and approximate Java heap use.

The optional JSON export is written only to `files/benchmark/export.json`.

## Interpretation

- An emulator run proves that the asset, JNI library, model initialization, inference, UTF-8 result path, and engine lifecycle work together.
- Emulator RTF is not comparable to a physical arm64 phone, especially when ARM is software-emulated.
- A performance claim requires repeated warm runs plus 5- and 15-minute sustained runs on the named device, with thermal state and RTF drift recorded.
- Do not run the benchmark while an IME dictation session is active; both intentionally share one native context.

## Physical-device protocol

1. Reboot the phone and wait for idle thermal state.
2. Run one cold benchmark and five warm benchmarks.
3. Record median and worst RTF, load time, Android version, SoC, RAM, and thermal state.
4. Run continuous samples for 5 and 15 minutes; fail if crashes occur or RTF degrades beyond the agreed threshold.
5. Separately test real microphone dictation. A fixture benchmark does not validate AudioRecord, VAD, or InputConnection behavior.
