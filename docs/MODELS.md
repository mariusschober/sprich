# Production speech files

The manifest in `app/src/main/java/com/sprich/app/models/manager/ModelManifest.kt` is the executable source for archive URLs, sizes, hashes and required files. Downloads extract only required model files; upstream test WAVs are not installed.

| Choice | Role / upstream | Archive bytes | License |
| --- | --- | ---: | --- |
| Automatic | OpenAI Whisper Tiny INT8, language identification | 116,204,861 | MIT model; Apache-2.0 sherpa conversion/runtime |
| Automatic | NVIDIA Canary 180M Flash INT8, recognition in the detected language | 153,692,328 | CC-BY-4.0 model; Apache-2.0 sherpa conversion/runtime |
| Accurate | NVIDIA Canary 180M Flash INT8, explicit language | *(same archive as Automatic)* | CC-BY-4.0 model; Apache-2.0 sherpa conversion/runtime |
| Bundled detector | Silero VAD, 16 kHz, single CPU thread | 643,854 uncompressed | MIT, Silero Team |

Automatic downloads language identification (Whisper Tiny, 116 MB) and the Canary recognizer (154 MB) in one action: 270,016,189 bytes total. It detects the language per utterance and decodes with Canary in that language; if detection fails or is unsupported the utterance is not inserted (fail-closed, no guessed language). Accurate uses the same Canary files with an explicitly selected EN/DE/ES/FR and therefore needs no additional download once Automatic is installed. No speculative decode runs in the production IME.

The 1.0 configuration deliberately uses no NVIDIA NGC-licensed FastConformer model. Its license terms bar redistribution, so the previous Automatic recognizer was removed from the production manifest and download flow on 2026-10-25; the executable manifest is the source of truth for what can be downloaded.

## Integrity

| File/archive | SHA-256 |
| --- | --- |
| Whisper Tiny archive | `c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1` |
| Canary archive | `7a38ed8b13f014ad632b09ff8d22e0c6f1359dd046af9235d281dfae841b9ab9` |
| `vad/silero_vad.onnx` | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` |

Installation verifies the pinned archive, bounded extraction, required files and a durable receipt recording each installed file's hash/size. Process startup verifies receipts and installed bytes on IO. Replacement keeps the prior directory until promotion succeeds; interrupted downloads restart from zero.

## Attribution and distribution review

NVIDIA NGC-licensed FastConformer is not part of the shipped configuration: its terms prohibit public reproduction or distribution absent a separate NVIDIA agreement, so the model was removed from the production manifest and download flow rather than shipped under unverified rights. The preserved terms text and the exclusion record are in `licenses/provenance/` and `licenses/README.md`; no NGC-licensed bytes are downloaded or redistributed by Sprich.

Canary model attribution: NVIDIA, [canary-180m-flash](https://huggingface.co/nvidia/canary-180m-flash), converted/quantized to ONNX by the sherpa-onnx project. Whisper: OpenAI, [Whisper](https://github.com/openai/whisper), converted/quantized by sherpa-onnx. Silero: Silero Team, [silero-vad](https://github.com/snakers4/silero-vad), distributed via sherpa-onnx's ASR release assets. These are modified/exported model forms, not original training checkpoints. Full upstream license texts and notices are in `licenses/THIRD_PARTY_NOTICES.txt` and the app.

Native versions and source provenance: [native/README.md](../native/README.md). Nemotron and replay fixtures are development experiments excluded from production model choices and APKs.
