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
                id = "fast",
                displayName = "Fast",
                technicalName = "Whisper Base multilingual Q5_1",
                engine = "whisper",
                version = "1.0.0",
                sourceUrl = "bundled",
                sizeBytes = 59707625L,
                sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
                files = listOf("models/whisper-base-q5_1.bin"),
                requiredFreeBytes = 80L * 1024 * 1024,
                languages = listOf("en","de","es"),
                license = "MIT (whisper.cpp) + CC-BY-4.0 (Whisper) — see licenses",
                quantization = "Q5_1"
            ),
            ModelEntry(
                id = "accurate",
                displayName = "Accurate",
                technicalName = "Canary 180M Flash INT8",
                engine = "canary",
                version = "1.0.0",
                sourceUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8.tar.bz2",
                sizeBytes = 198L * 1024 * 1024, // 127M encoder + 71M decoder + tokens, actual on-device
                sha256 = "7a38ed8b13f014ad632b09ff8d22e0c6f1359dd046af9235d281dfae841b9ab9",
                files = listOf("encoder.int8.onnx","decoder.int8.onnx","tokens.txt"),
                requiredFreeBytes = 450L * 1024 * 1024,
                languages = listOf("en","de","es","fr"),
                license = "CC-BY-4.0 (model) + Apache-2.0 (sherpa)",
                quantization = "INT8"
            ),
            ModelEntry(
                id = "streaming",
                displayName = "Instant Streaming",
                technicalName = "Nemotron 3.5 ASR 0.6B Q4_K",
                engine = "nemotron",
                version = "1.0.0",
                sourceUrl = "https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b/resolve/main/model_q4_k.gguf",
                sizeBytes = 630L * 1024 * 1024,
                sha256 = "",
                files = listOf("model_q4_k.gguf"),
                requiredFreeBytes = 950L * 1024 * 1024,
                languages = listOf("en","de","es"),
                license = "CC-BY-4.0",
                quantization = "Q4_K"
            ),
        )
    )
}
