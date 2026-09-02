# Production speech files

The manifest in `app/src/main/java/com/sprich/app/models/manager/ModelManifest.kt` is the executable source for archive URLs, sizes, hashes and required files. Downloads extract only required model files; upstream test WAVs are not installed.

| Choice | Role / upstream | Archive bytes | License |
| --- | --- | ---: | --- |
| Automatic | OpenAI Whisper Tiny INT8, language identification | 116,204,861 | MIT model; Apache-2.0 sherpa conversion/runtime |
| Automatic | NeMo FastConformer CTC EN/DE/ES/FR export 14288 INT8 | 102,875,642 | **NVIDIA NGC Terms of Use for the model**; Apache-2.0 conversion code |
| Accurate | NVIDIA Canary 180M Flash INT8, explicit language | 153,692,328 | CC-BY-4.0 model; Apache-2.0 sherpa conversion/runtime |
| Bundled detector | Silero VAD, 16 kHz, single CPU thread | 643,854 uncompressed | MIT, Silero Team |

Automatic downloads both language identification and recognition files in one action (219,080,503 bytes total). It never needs Canary. Accurate must receive EN, DE, ES or FR explicitly and uses the same source/target language. No speculative decode runs in the production IME.

## Integrity

| File/archive | SHA-256 |
| --- | --- |
| Whisper Tiny archive | `c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1` |
| FastConformer archive | `ea7434ecff117272a70b8a60b70cfc2f04b9b07553aa0ecb91065b69c7b91ec5` |
| Canary archive | `7a38ed8b13f014ad632b09ff8d22e0c6f1359dd046af9235d281dfae841b9ab9` |
| `vad/silero_vad.onnx` | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` |

Installation verifies the pinned archive, bounded extraction, required files and a durable receipt recording each installed file's hash/size. Process startup verifies receipts and installed bytes on IO. Replacement keeps the prior directory until promotion succeeds; interrupted downloads restart from zero.

## Attribution and distribution review

FastConformer derives from NVIDIA's [`stt_multilingual_fastconformer_hybrid_large_pc_blend_eu`](https://catalog.ngc.nvidia.com/orgs/nvidia/teams/nemo/models/stt_multilingual_fastconformer_hybrid_large_pc_blend_eu), v1.21.0. Earlier repository text incorrectly called this model Apache-2.0. The publisher must review NGC terms for use and distribution of the converted model before public distribution. Apache licensing of conversion code does not change model rights.

Canary model attribution: NVIDIA, [canary-180m-flash](https://huggingface.co/nvidia/canary-180m-flash), converted/quantized to ONNX by the sherpa-onnx project. Whisper: OpenAI, [Whisper](https://github.com/openai/whisper), converted/quantized by sherpa-onnx. Silero: Silero Team, [silero-vad](https://github.com/snakers4/silero-vad), distributed via sherpa-onnx's ASR release assets. These are modified/exported model forms, not original training checkpoints. Full upstream license texts and notices are in `licenses/THIRD_PARTY_NOTICES.txt` and the app.

Native versions and source provenance: [native/README.md](../native/README.md). Nemotron and replay fixtures are development experiments excluded from production model choices and APKs.
