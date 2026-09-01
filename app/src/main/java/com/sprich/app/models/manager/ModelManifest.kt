package com.sprich.app.models.manager

import kotlinx.serialization.Serializable

@Serializable
data class ModelManifest(
    val models: List<ModelEntry>,
    val version: Int = 1,
)

@Serializable
data class ModelEntry(
    val id: String, // fast, accurate, streaming
    val displayName: String,
    val technicalName: String,
    val engine: String, // whisper, canary, nemotron
    val version: String,
    val sourceUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val files: List<String>,
    val requiredFreeBytes: Long,
    val languages: List<String>,
    val license: String,
    val quantization: String,
    val minAppVersion: String? = null,
)

object BuiltinManifest {
    fun default(): ModelManifest = ModelManifest(
        models = listOf(
            ModelEntry(
                id = "accurate",
                displayName = "Accurate",
                technicalName = "Canary 180M Flash INT8",
                engine = "canary",
                version = "1.0.0",
                sourceUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8.tar.bz2",
                sizeBytes = 153692328L, // archive 153M, extracted 207618048 (198M)
                sha256 = "7a38ed8b13f014ad632b09ff8d22e0c6f1359dd046af9235d281dfae841b9ab9",
                files = listOf("encoder.int8.onnx","decoder.int8.onnx","tokens.txt"),
                requiredFreeBytes = 450L * 1024 * 1024,
                languages = listOf("en","de","es","fr"),
                license = "CC-BY-4.0 (nvidia/canary-180m-flash) + Apache-2.0 (sherpa)",
                quantization = "INT8"
            ),
            ModelEntry(
                id = "lid",
                displayName = "Whisper Tiny LID",
                technicalName = "Whisper Tiny spoken-language-ID INT8",
                engine = "whisper-tiny-lid",
                version = "1.0.0",
                sourceUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
                sizeBytes = 116204861L, // MEASURED archive 116,204,861 bytes, extracted 98M (12M enc +86M dec +798K tokens), 5 files inc test_wavs
                sha256 = "c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1", // MEASURED 2026-09-01 via /tmp/sprich_sha
                files = listOf("tiny-encoder.int8.onnx","tiny-decoder.int8.onnx","tiny-tokens.txt"),
                requiredFreeBytes = 250L * 1024 * 1024,
                languages = listOf("en","de","es","fr"),
                license = "MIT (OpenAI Whisper) + Apache-2.0 (sherpa)",
                quantization = "INT8"
            ),
            ModelEntry(
                id = "fastconformer",
                displayName = "FastConformer Multilingual",
                technicalName = "NeMo FastConformer CTC EN-DE-ES-FR 14288 INT8",
                engine = "nemo-ctc",
                version = "1.0.0",
                sourceUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-fast-conformer-ctc-en-de-es-fr-14288-int8.tar.bz2",
                sizeBytes = 102875642L, // MEASURED archive 102,875,642 bytes, extracted 126M model.int8.onnx (126MB) + 23K tokens, 6 files inc test_wavs
                sha256 = "ea7434ecff117272a70b8a60b70cfc2f04b9b07553aa0ecb91065b69c7b91ec5", // MEASURED 2026-09-01
                files = listOf("model.int8.onnx","tokens.txt"),
                requiredFreeBytes = 300L * 1024 * 1024,
                languages = listOf("en","de","es","fr"),
                license = "Apache-2.0 (NVIDIA NeMo) + Apache-2.0 (sherpa)",
                quantization = "INT8"
            ),
            ModelEntry(
                id = "nemotron-560",
                displayName = "Nemotron 3.5 Streaming 560ms",
                technicalName = "Nemotron 3.5 ASR Streaming 0.6B 560ms INT8",
                engine = "nemotron",
                version = "2026-06-11",
                sourceUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11.tar.bz2",
                sizeBytes = 475271763L, // archive 475M, extracted ~600M (encoder+decoder+joiner+tokens)
                sha256 = "c6bf5e0df765f9d5b43bc9e0536d4b4b3e7d40bdf5ecf13e45f134c51c05ae3a", // MEASURED via /tmp/nemotron-560.tar.bz2 2026-09-01
                files = listOf("encoder.int8.onnx","decoder.int8.onnx","joiner.int8.onnx","tokens.txt"),
                requiredFreeBytes = 900L * 1024 * 1024,
                languages = listOf("en","de","es","fr","ja","hi","auto"), // 40 locales, `auto` strips tag per sherpa 1.13.4+
                license = "OpenMDW-1.1 (NVIDIA) + Apache-2.0 (sherpa)",
                quantization = "INT8"
            ),
            ModelEntry(
                id = "nemotron-160",
                displayName = "Nemotron 3.5 Streaming 160ms",
                technicalName = "Nemotron 3.5 ASR Streaming 0.6B 160ms INT8",
                engine = "nemotron",
                version = "2026-06-11",
                sourceUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-160ms-int8-2026-06-11.tar.bz2",
                sizeBytes = 475273363L, // MEASURED archive 475,273,363 bytes, extracted ~510M (encoder/decoder/joiner ~500M + tokens + README)
                sha256 = "a81909a1780d84cff16d73c15e13e67d9d81d8839faf14870d507d8499f7a61a", // MEASURED 2026-09-01
                files = listOf("encoder.int8.onnx","decoder.int8.onnx","joiner.int8.onnx","tokens.txt"),
                requiredFreeBytes = 900L * 1024 * 1024,
                languages = listOf("en","de","es","fr","auto"),
                license = "OpenMDW-1.1 (NVIDIA) + Apache-2.0 (sherpa)",
                quantization = "INT8"
            ),
        )
    )
}
