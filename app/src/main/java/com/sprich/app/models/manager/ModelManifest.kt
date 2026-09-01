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
                sizeBytes = 116204861L, // archive, extracted int8 98M (12M enc +86M dec) + tokens 798K
                sha256 = "", // TODO pin after verified download (archive 116M)
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
                sizeBytes = 102875642L, // archive, extracted 126M model.int8.onnx + 23K tokens
                sha256 = "",
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
                sizeBytes = 475271763L, // archive, extracted ~500-600M (encoder+decoder+joiner+tokens+data)
                sha256 = "",
                files = listOf("encoder.int8.onnx","decoder.int8.onnx","joiner.int8.onnx","tokens.txt"),
                requiredFreeBytes = 900L * 1024 * 1024,
                languages = listOf("en","de","es","fr","ja","hi","auto"), // 40 locales, `auto` strips tag
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
                sizeBytes = 475273363L,
                sha256 = "",
                files = listOf("encoder.int8.onnx","decoder.int8.onnx","joiner.int8.onnx","tokens.txt"),
                requiredFreeBytes = 900L * 1024 * 1024,
                languages = listOf("en","de","es","fr","auto"),
                license = "OpenMDW-1.1 (NVIDIA) + Apache-2.0 (sherpa)",
                quantization = "INT8"
            ),
        )
    )
}
