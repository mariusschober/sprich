package com.sprich.app.models.manager

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

sealed class ModelStatus {
    object NotDownloaded : ModelStatus()
    data class Downloading(val progress: Float, val bytes: Long, val total: Long) : ModelStatus()
    object Verifying : ModelStatus()
    object Ready : ModelStatus()
    data class Failed(val error: String) : ModelStatus()
}

class ModelManager(private val context: Context) {

    private val manifest = BuiltinManifest.default()

    private val _canaryStatus = MutableStateFlow<ModelStatus>(if (isCanaryReady()) ModelStatus.Ready else ModelStatus.NotDownloaded)
    val canaryStatus: StateFlow<ModelStatus> = _canaryStatus

    private val _nemotronStatus = MutableStateFlow<ModelStatus>(if (isNemotronReady()) ModelStatus.Ready else ModelStatus.NotDownloaded)
    val nemotronStatus: StateFlow<ModelStatus> = _nemotronStatus

    private val _lidStatus = MutableStateFlow<ModelStatus>(if (isWhisperTinyReady()) ModelStatus.Ready else ModelStatus.NotDownloaded)
    val lidStatus: StateFlow<ModelStatus> = _lidStatus

    private val _fastConformerStatus = MutableStateFlow<ModelStatus>(if (isFastConformerReady()) ModelStatus.Ready else ModelStatus.NotDownloaded)
    val fastConformerStatus: StateFlow<ModelStatus> = _fastConformerStatus

    fun canaryDir(): File? = File(context.filesDir, "canary").let { if (it.exists() && isCanaryReady()) it else null }
    fun nemotronDir(): File? {
        // Prefer new 560 dir, fallback to legacy nemotron and 160
        val d560 = File(context.filesDir, "nemotron-560")
        if (d560.exists() && isNemotron560Ready()) return d560
        val d160 = File(context.filesDir, "nemotron-160")
        if (d160.exists() && isNemotron160Ready()) return d160
        val d = File(context.filesDir, "nemotron")
        if (d.exists() && isNemotronReady()) return d
        return null
    }
    fun nemotron560Dir(): File? = File(context.filesDir, "nemotron-560").let { if (it.exists() && isNemotron560Ready()) it else null }
    fun nemotron160Dir(): File? = File(context.filesDir, "nemotron-160").let { if (it.exists() && isNemotron160Ready()) it else null }
    fun lidDir(): File? = File(context.filesDir, "whisper-tiny").let { if (it.exists() && isWhisperTinyReady()) it else null }
    fun fastConformerDir(): File? = File(context.filesDir, "fastconformer").let { if (it.exists() && isFastConformerReady()) it else null }

    fun isCanaryReady(): Boolean {
        val dir = File(context.filesDir, "canary")
        return File(dir, "encoder.int8.onnx").let{ it.exists() && it.length() > 50_000_000} &&
               File(dir, "decoder.int8.onnx").let{ it.exists() && it.length() > 50_000_000} &&
               File(dir, "tokens.txt").exists()
    }
    fun isNemotronReady(): Boolean {
        return isNemotron560Ready() || isNemotron160Ready() || isNemotronLegacyReady()
    }

    fun isNemotronReadyForRelease(): Boolean = isNemotronReady()

    fun isWhisperTinyReady(): Boolean {
        val dir = File(context.filesDir, "whisper-tiny")
        return File(dir, "tiny-encoder.int8.onnx").let { it.exists() && it.length() > 5_000_000 } &&
               File(dir, "tiny-decoder.int8.onnx").let { it.exists() && it.length() > 50_000_000 } &&
               File(dir, "tiny-tokens.txt").exists()
    }

    fun isWhisperTinyReadyForRelease(): Boolean = isWhisperTinyReady()

    fun isFastConformerReady(): Boolean {
        val dir = File(context.filesDir, "fastconformer")
        return File(dir, "model.int8.onnx").let { it.exists() && it.length() > 50_000_000 } &&
               File(dir, "tokens.txt").exists()
    }

    fun isNemotron560Ready(): Boolean {
        val dir = File(context.filesDir, "nemotron-560")
        return File(dir, "encoder.int8.onnx").let { it.exists() && it.length() > 50_000_000 } &&
               File(dir, "decoder.int8.onnx").exists() &&
               File(dir, "joiner.int8.onnx").exists() &&
               File(dir, "tokens.txt").exists()
    }

    fun isNemotron160Ready(): Boolean {
        val dir = File(context.filesDir, "nemotron-160")
        return File(dir, "encoder.int8.onnx").let { it.exists() && it.length() > 50_000_000 } &&
               File(dir, "decoder.int8.onnx").exists() &&
               File(dir, "joiner.int8.onnx").exists() &&
               File(dir, "tokens.txt").exists()
    }

    // Legacy GGUF nemotron still considered ready if present (for backward compat)
    fun isNemotronLegacyReady(): Boolean {
        val dir = File(context.filesDir, "nemotron")
        return File(dir, "model_q4_k.gguf").let { it.exists() && it.length() > 50_000_000 }
    }

    fun isFastReady(): Boolean {
        return try { context.assets.open("models/whisper-base-q5_1.bin").use{ it.available() > 0 } } catch (_:Exception){ false }
    }

    fun requireFastReadyForRelease(): Boolean {
        // Release check: must be real 50MB+ model, not placeholder. CI will verify.
        return try { context.assets.open("models/whisper-base-q5_1.bin").use{ it.available() > 50_000_000 } } catch (_:Exception){ false }
    }

    suspend fun deleteCanary() = withContext(Dispatchers.IO) {
        File(context.filesDir, "canary").deleteRecursively()
        _canaryStatus.value = ModelStatus.NotDownloaded
    }
    suspend fun deleteNemotron() = withContext(Dispatchers.IO){
        // Delete all nemotron variants (legacy + 160/560)
        File(context.filesDir, "nemotron").deleteRecursively()
        File(context.filesDir, "nemotron-160").deleteRecursively()
        File(context.filesDir, "nemotron-560").deleteRecursively()
        _nemotronStatus.value = ModelStatus.NotDownloaded
    }
    suspend fun deleteLid() = withContext(Dispatchers.IO){
        File(context.filesDir, "whisper-tiny").deleteRecursively()
        _lidStatus.value = ModelStatus.NotDownloaded
    }
    suspend fun deleteFastConformer() = withContext(Dispatchers.IO){
        File(context.filesDir, "fastconformer").deleteRecursively()
        _fastConformerStatus.value = ModelStatus.NotDownloaded
    }

    fun hasEnoughSpace(required: Long): Boolean {
        val s = StatFs(context.filesDir.path)
        return s.availableBytes > required
    }

    fun getManifest(): ModelManifest = manifest

    fun updateDownloadProgress(id: String, prog: Float, bytes: Long, total: Long){
        when(id){
            "accurate" -> _canaryStatus.value = ModelStatus.Downloading(prog, bytes, total)
            "lid" -> _lidStatus.value = ModelStatus.Downloading(prog, bytes, total)
            "fastconformer" -> _fastConformerStatus.value = ModelStatus.Downloading(prog, bytes, total)
            "nemotron-560", "nemotron-160", "streaming", "nemotron" -> _nemotronStatus.value = ModelStatus.Downloading(prog, bytes, total)
        }
    }
    fun setVerifying(id: String){
        when(id){
            "accurate"-> _canaryStatus.value=ModelStatus.Verifying
            "lid" -> _lidStatus.value=ModelStatus.Verifying
            "fastconformer" -> _fastConformerStatus.value=ModelStatus.Verifying
            "nemotron-560", "nemotron-160", "streaming", "nemotron" -> _nemotronStatus.value=ModelStatus.Verifying
        }
    }
    fun setReady(id: String){
        when(id){
            "accurate"-> _canaryStatus.value=ModelStatus.Ready
            "lid" -> _lidStatus.value=ModelStatus.Ready
            "fastconformer" -> _fastConformerStatus.value=ModelStatus.Ready
            "nemotron-560", "nemotron-160", "streaming", "nemotron" -> _nemotronStatus.value=ModelStatus.Ready
        }
    }
    fun setFailed(id: String, err: String){
        when(id){
            "accurate"-> _canaryStatus.value=ModelStatus.Failed(err)
            "lid" -> _lidStatus.value=ModelStatus.Failed(err)
            "fastconformer" -> _fastConformerStatus.value=ModelStatus.Failed(err)
            "nemotron-560", "nemotron-160", "streaming", "nemotron" -> _nemotronStatus.value=ModelStatus.Failed(err)
        }
    }

    suspend fun verifySha256(file: File, expected: String): Boolean = withContext(Dispatchers.IO){
        if (expected.isBlank()) return@withContext true // no pin yet
        try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { inp ->
                val buf = ByteArray(8192)
                var n: Int
                while (inp.read(buf).also { n = it } != -1) md.update(buf, 0, n)
            }
            val hex = md.digest().joinToString(""){ "%02x".format(it)}
            hex.equals(expected, ignoreCase = true)
        } catch (_:Exception){ false }
    }

    fun checkIntegrity(): Boolean {
        // Ensure no partial .tmp remains
        listOf(File(context.filesDir, "canary.tmp"), File(context.filesDir, "nemotron.tmp")).forEach{
            if (it.exists()) it.deleteRecursively()
        }
        return true
    }
}
