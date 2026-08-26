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

    fun canaryDir(): File? = File(context.filesDir, "canary").let { if (it.exists() && isCanaryReady()) it else null }
    fun nemotronDir(): File? = File(context.filesDir, "nemotron").let { if (it.exists() && isNemotronReady()) it else null }

    fun isCanaryReady(): Boolean {
        val dir = File(context.filesDir, "canary")
        return File(dir, "encoder.int8.onnx").let{ it.exists() && it.length() > 50_000_000} &&
               File(dir, "decoder.int8.onnx").let{ it.exists() && it.length() > 50_000_000} &&
               File(dir, "tokens.txt").exists()
    }
    fun isNemotronReady(): Boolean {
        val dir = File(context.filesDir, "nemotron")
        // Release-grade gate: real GGUF >50M, placeholder 30B fails
        return File(dir, "model_q4_k.gguf").let { it.exists() && it.length() > 50_000_000 }
    }

    fun isNemotronReadyForRelease(): Boolean = isNemotronReady()

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
        File(context.filesDir, "nemotron").deleteRecursively()
        _nemotronStatus.value = ModelStatus.NotDownloaded
    }

    fun hasEnoughSpace(required: Long): Boolean {
        val s = StatFs(context.filesDir.path)
        return s.availableBytes > required
    }

    fun getManifest(): ModelManifest = manifest

    fun updateDownloadProgress(id: String, prog: Float, bytes: Long, total: Long){
        when(id){
            "accurate" -> _canaryStatus.value = ModelStatus.Downloading(prog, bytes, total)
            "streaming" -> _nemotronStatus.value = ModelStatus.Downloading(prog, bytes, total)
        }
    }
    fun setVerifying(id: String){
        when(id){ "accurate"-> _canaryStatus.value=ModelStatus.Verifying; "streaming"-> _nemotronStatus.value=ModelStatus.Verifying}
    }
    fun setReady(id: String){
        when(id){ "accurate"-> _canaryStatus.value=ModelStatus.Ready; "streaming"-> _nemotronStatus.value=ModelStatus.Ready}
    }
    fun setFailed(id: String, err: String){
        when(id){ "accurate"-> _canaryStatus.value=ModelStatus.Failed(err); "streaming"-> _nemotronStatus.value=ModelStatus.Failed(err)}
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
