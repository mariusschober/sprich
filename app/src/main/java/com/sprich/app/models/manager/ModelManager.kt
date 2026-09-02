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

    // Legacy aggregate (kept for backward compat, reflects any variant ready) — DO NOT use for variant-specific UI
    private val _nemotronStatus = MutableStateFlow<ModelStatus>(if (isNemotronReady()) ModelStatus.Ready else ModelStatus.NotDownloaded)
    val nemotronStatus: StateFlow<ModelStatus> = _nemotronStatus

    // Required independent variant states — downloading 560 must not mark 160 Ready, deleting one must not delete the other
    private val _nemotron560Status = MutableStateFlow<ModelStatus>(if (isNemotron560Ready()) ModelStatus.Ready else ModelStatus.NotDownloaded)
    val nemotron560Status: StateFlow<ModelStatus> = _nemotron560Status
    private val _nemotron160Status = MutableStateFlow<ModelStatus>(if (isNemotron160Ready()) ModelStatus.Ready else ModelStatus.NotDownloaded)
    val nemotron160Status: StateFlow<ModelStatus> = _nemotron160Status

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
        // Production invariant: successful SHA-pinned verification → extraction → required files verified → marker written atomically
        // Then readiness uses trusted install marker + required-file sanity. For legacy installs, migrate/verify once.
        val marker = File(dir, ".installed_ok")
        if (marker.exists()) {
            // Marker present → verify required files still exist and have plausible sizes
            return File(dir, "tiny-encoder.int8.onnx").let { it.exists() && it.length() > 10_000_000 } &&
                   File(dir, "tiny-decoder.int8.onnx").let { it.exists() && it.length() > 50_000_000 } &&
                   File(dir, "tiny-tokens.txt").let { it.exists() && it.length() > 0 }
        }
        // Legacy path without marker: still require production sizes (do not weaken to 5M for fixtures)
        return File(dir, "tiny-encoder.int8.onnx").let { it.exists() && it.length() > 10_000_000 } &&
               File(dir, "tiny-decoder.int8.onnx").let { it.exists() && it.length() > 50_000_000 } &&
               File(dir, "tiny-tokens.txt").let { it.exists() && it.length() > 0 }
    }

    fun markWhisperTinyInstalled() {
        try {
            val dir = File(context.filesDir, "whisper-tiny")
            if (!dir.exists()) dir.mkdirs()
            File(dir, ".installed_ok").writeText(System.currentTimeMillis().toString())
        } catch (_: Exception) {}
    }

    fun isWhisperTinyReadyForRelease(): Boolean = isWhisperTinyReady()

    fun isFastConformerReady(): Boolean {
        val dir = File(context.filesDir, "fastconformer")
        return File(dir, "model.int8.onnx").let { it.exists() && it.length() > 50_000_000 } &&
                File(dir, "tokens.txt").exists()
    }

    /** Single derived readiness for Automatic — both Tiny LID and FastConformer required. No Canary. */
    fun isAutomaticReady(): Boolean = isWhisperTinyReady() && isFastConformerReady()

    /** Flow-friendly getter for tests/UI. */
    fun isAutomaticReadyStatus(lid: ModelStatus, fast: ModelStatus): Boolean =
        lid is ModelStatus.Ready && fast is ModelStatus.Ready

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
        // Delete all nemotron variants (legacy + 160/560) — explicit "Delete all" action
        File(context.filesDir, "nemotron").deleteRecursively()
        File(context.filesDir, "nemotron-160").deleteRecursively()
        File(context.filesDir, "nemotron-560").deleteRecursively()
        _nemotronStatus.value = ModelStatus.NotDownloaded
        _nemotron160Status.value = ModelStatus.NotDownloaded
        _nemotron560Status.value = ModelStatus.NotDownloaded
    }
    suspend fun deleteNemotron560() = withContext(Dispatchers.IO){
        File(context.filesDir, "nemotron-560").deleteRecursively()
        _nemotron560Status.value = ModelStatus.NotDownloaded
        // Update aggregate to reflect remaining variants
        _nemotronStatus.value = if (isNemotronReady()) ModelStatus.Ready else ModelStatus.NotDownloaded
    }
    suspend fun deleteNemotron160() = withContext(Dispatchers.IO){
        File(context.filesDir, "nemotron-160").deleteRecursively()
        _nemotron160Status.value = ModelStatus.NotDownloaded
        _nemotronStatus.value = if (isNemotronReady()) ModelStatus.Ready else ModelStatus.NotDownloaded
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
            "nemotron-560" -> _nemotron560Status.value = ModelStatus.Downloading(prog, bytes, total)
            "nemotron-160" -> _nemotron160Status.value = ModelStatus.Downloading(prog, bytes, total)
            "streaming", "nemotron" -> _nemotronStatus.value = ModelStatus.Downloading(prog, bytes, total)
        }
        // Keep aggregate in sync only for legacy callers (not variant-specific)
        if (id.startsWith("nemotron")) _nemotronStatus.value = if (id=="nemotron-560") _nemotron560Status.value else if (id=="nemotron-160") _nemotron160Status.value else _nemotronStatus.value
    }
    fun setVerifying(id: String){
        when(id){
            "accurate"-> _canaryStatus.value=ModelStatus.Verifying
            "lid" -> _lidStatus.value=ModelStatus.Verifying
            "fastconformer" -> _fastConformerStatus.value=ModelStatus.Verifying
            "nemotron-560" -> _nemotron560Status.value=ModelStatus.Verifying
            "nemotron-160" -> _nemotron160Status.value=ModelStatus.Verifying
            "streaming", "nemotron" -> _nemotronStatus.value=ModelStatus.Verifying
        }
    }
    fun setReady(id: String){
        when(id){
            "accurate"-> _canaryStatus.value=ModelStatus.Ready
            "lid" -> _lidStatus.value=ModelStatus.Ready
            "fastconformer" -> _fastConformerStatus.value=ModelStatus.Ready
            "nemotron-560" -> _nemotron560Status.value=ModelStatus.Ready
            "nemotron-160" -> _nemotron160Status.value=ModelStatus.Ready
            "streaming", "nemotron" -> _nemotronStatus.value=ModelStatus.Ready
        }
        if (id=="nemotron-560" || id=="nemotron-160") _nemotronStatus.value = if (isNemotronReady()) ModelStatus.Ready else _nemotronStatus.value
    }
    fun setFailed(id: String, err: String){
        when(id){
            "accurate"-> _canaryStatus.value=ModelStatus.Failed(err)
            "lid" -> _lidStatus.value=ModelStatus.Failed(err)
            "fastconformer" -> _fastConformerStatus.value=ModelStatus.Failed(err)
            "nemotron-560" -> _nemotron560Status.value=ModelStatus.Failed(err)
            "nemotron-160" -> _nemotron160Status.value=ModelStatus.Failed(err)
            "streaming", "nemotron" -> _nemotronStatus.value=ModelStatus.Failed(err)
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
