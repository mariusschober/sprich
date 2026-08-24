# MODELS — sourcing, verification, licensing

## Fast — Whisper Base multilingual Q5_1 (bundled, 57 MB)

**Runtime**: whisper.cpp via NDK, `q5_1` GGML.

**Sourcing**:
```bash
# 1. Get base multilingual 74M
curl -L https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin -o ggml-base.bin
# 2. Quantize with whisper.cpp quantize binary
./build/bin/quantize ggml-base.bin ggml-base-q5_1.bin q5_1
# 3. Verify size ~57 MB
ls -lh ggml-base-q5_1.bin
sha256sum ggml-base-q5_1.bin > sha.txt
cp ggml-base-q5_1.bin app/src/main/assets/models/whisper-base-q5_1.bin
```

**Verification**: `scripts/verify-models.sh` checks `assets/models/whisper-base-q5_1.bin` exists and SHA matches `docs/sha/whisper-base-q5_1.sha256`. Release build fails if placeholder (<1MB) or missing.

**License**: whisper.cpp MIT, base model from OpenAI Whisper — check `https://github.com/openai/whisper/blob/main/model-card.md` (MIT-ish). Include attribution in Settings → Licenses. Do not redistribute modified weights without license review.

**Languages v1**: EN/DE/ES via multilingual model, not `base.en`. `language = auto` uses whisper's detection; fixed language via prompt is faster.

## Accurate — Canary 180M Flash INT8 (optional, 198 MB download)

** Tar**: `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8.tar.bz2`
**Contents**: `encoder.int8.onnx`, `decoder.int8.onnx`, `tokens.txt` (+ `test_wavs/` skipped)
**Manifest**: `BuiltinManifest` pins URL, requiredFree 450 MB, quantization INT8.
**Download**: OkHttp with `Range` resume, progress, `StatFs` check, `BZip2CompressorInputStream` + `TarArchiveInputStream`, atomic `canary.tmp` → `files/canary`, path-traversal guard, SHA if pinned (currently blank until first verified mirror — update `sha256` field after pinning).
** Extraction **: commons-compress 1.26.2, only needed files, flat.
** Delete **: `ModelManager.deleteCanary()` closes handles, unmaps, `deleteRecursively`, transactional status.

** License **: Model CC-BY-4.0 (NVIDIA), sherpa-onnx Apache-2.0. Attribution required. Download from upstream GitHub release directly, not private mirror, to respect redistribution.

## Streaming — Nemotron 3.5 ASR 0.6B Q4_K (~620 MB download, experimental)

** Source **: `https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b` — GGUF Q4_K needs conversion via `nemo2gguf` or community quant.
** Runtime target **: `NeMo-Speech.cpp` native C/C++ cache-aware RNNT. Chunk 160ms default, profile 80/160/240/320 for RTF vs scheduling overhead.
** Backend **: CPU default, Vulkan optional if `vkEnumerateInstanceVersion` succeeds and not in deny-list; measure RTF, if Vulkan > CPU fallback cleanly.
** Quantization **: Q4_K GGUF, memory-map, zero-copy.

** Status experimental ** until sustained benchmarks on €300 device show RTF <0.5 and no thermal runaway (15-min test). If throttles to >0.9, recommend Fast.

** License **: CC-BY-4.0 (check model card). Same sourcing honesty as Canary.

## Manifest architecture

`ModelManifest` fields: id, displayName, technicalName, engine, version, sourceUrl, sizeBytes, sha256, files, requiredFreeBytes, languages, license, quantization, minAppVersion.

Manifest is versioned, bundled; for v1 no remote config (security). Future update via app release, not silent remote.

## Artifact verification (CI)

```bash
# app/build.gradle enforces:
# - APK must contain assets/models/whisper-base-q5_1.bin with size >50 MB for release
# - APK must NOT contain files/canary/* or files/nemotron/* (they live in app-private files, not assets)
# - If unzip -l app-release.apk | grep -q canary → fail
```

See `scripts/check-apk.sh`.

## Acquisition reproducibility

`scripts/build-native.sh` documents steps to:
- fetch whisper.cpp commit pin (e.g., `master` SHA)
- build `quantize`, `whisper` with NDK
- run `verify-models.sh`
- build `libsprich_whisper.so` with CMake + NDK 27.0.12077973

Do not commit 57 MB binary to git LFS without license check; placeholder is fine for CI, real model required for Play release artifact.
