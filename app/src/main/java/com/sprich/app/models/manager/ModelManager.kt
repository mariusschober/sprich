package com.sprich.app.models.manager

import android.content.Context
import android.os.StatFs
import com.sprich.app.models.download.DownloadManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

sealed class ModelStatus {
    data object NotDownloaded : ModelStatus()
    data class Downloading(val progress: Float, val bytes: Long, val total: Long) : ModelStatus()
    data object Verifying : ModelStatus()
    data object Ready : ModelStatus()
    data class Failed(val error: String) : ModelStatus()
}

@Serializable internal data class InstalledFile(val size: Long, val sha256: String)
@Serializable internal data class InstallReceipt(val archiveSha256: String, val files: Map<String, InstalledFile>)

/** Process-wide state and serialization, shared by Settings, the IME and native loaders. */
class ModelManager(context: Context, private val manifest: ModelManifest = BuiltinManifest.default()) {
    private val context = context.applicationContext
    private val state = states.computeIfAbsent(this.context.filesDir.absolutePath) { State() }
    internal val scope get() = state.scope
    internal fun lock(id: String) = state.locks.computeIfAbsent(id) { Mutex() }
    private fun status(id: String) = state.statuses.computeIfAbsent(id) { MutableStateFlow<ModelStatus>(ModelStatus.NotDownloaded) }
    val canaryStatus: StateFlow<ModelStatus> get() = status("accurate")
    val lidStatus: StateFlow<ModelStatus> get() = status("lid")
    val fastConformerStatus: StateFlow<ModelStatus> get() = status("fastconformer")
    val nemotron560Status: StateFlow<ModelStatus> get() = status("nemotron-560")
    val nemotron160Status: StateFlow<ModelStatus> get() = status("nemotron-160")
    val nemotronStatus: StateFlow<ModelStatus> get() = status("nemotron-560")

    init { if (state.started.compareAndSet(false, true)) scope.launch { refresh() } }

    fun getManifest() = manifest
    internal fun directory(id: String) = File(context.filesDir, when (id) { "accurate" -> "canary"; "lid" -> "whisper-tiny"; else -> id })
    private fun entry(id: String) = manifest.models.single { it.id == id }
    private fun fingerprint(dir: File, entry: ModelEntry): List<Long> =
        (entry.files + RECEIPT).flatMap { name -> File(dir, name).let { listOf(it.length(), it.lastModified()) } }

    private fun ready(id: String): Boolean {
        val e = entry(id)
        val trusted = state.verified[id] ?: return false
        return trusted.archiveSha256 == e.sha256 && trusted.metadata == fingerprint(directory(id), e)
    }
    fun isCanaryReady() = ready("accurate")
    fun isWhisperTinyReady() = ready("lid")
    fun isWhisperTinyReadyForRelease() = isWhisperTinyReady()
    fun isFastConformerReady() = ready("fastconformer")
    fun isAutomaticReady() = isWhisperTinyReady() && isFastConformerReady()
    fun isAutomaticReadyStatus(lid: ModelStatus, fast: ModelStatus) = lid is ModelStatus.Ready && fast is ModelStatus.Ready
    fun isNemotron560Ready() = ready("nemotron-560")
    fun isNemotron160Ready() = ready("nemotron-160")
    fun isNemotronReady() = isNemotron560Ready() || isNemotron160Ready()
    fun isNemotronReadyForRelease() = isNemotronReady()
    fun canaryDir(): File? = directory("accurate").takeIf { isCanaryReady() }
    fun lidDir(): File? = directory("lid").takeIf { isWhisperTinyReady() }
    fun fastConformerDir(): File? = directory("fastconformer").takeIf { isFastConformerReady() }
    fun nemotron560Dir(): File? = directory("nemotron-560").takeIf { isNemotron560Ready() }
    fun nemotron160Dir(): File? = directory("nemotron-160").takeIf { isNemotron160Ready() }
    fun nemotronDir(): File? = nemotron560Dir() ?: nemotron160Dir()

    /** Always run on IO, under the same lock used by installation/deletion/native construction. */
    internal suspend fun verifyDirectory(dir: File, e: ModelEntry): Boolean {
        if (!Regex("[a-fA-F0-9]{64}").matches(e.sha256)) return false
        val receipt = try {
            val file = File(dir, RECEIPT)
            if (!file.isFile || file.length() > 16_384) return false
            Json.decodeFromString<InstallReceipt>(file.readText())
        } catch (_: Exception) { return false }
        if (receipt.archiveSha256 != e.sha256 || receipt.files.keys != e.files.toSet()) return false
        for (name in e.files) {
            currentCoroutineContext().ensureActive()
            val f = File(dir, name)
            val expected = receipt.files.getValue(name)
            if (!f.isFile || expected.size <= 0 || f.length() != expected.size || !verifySha256(f, expected.sha256)) return false
        }
        return true
    }

    internal suspend fun writeReceipt(dir: File, e: ModelEntry) {
        val files = e.files.associateWith { name ->
            val f = File(dir, name)
            check(f.isFile && f.length() > 0) { "Incomplete model" }
            InstalledFile(f.length(), sha256(f))
        }
        File(dir, RECEIPT).outputStream().use { out ->
            out.write(Json.encodeToString(InstallReceipt(e.sha256, files)).toByteArray())
            out.fd.sync()
        }
    }

    internal fun installed(id: String) {
        state.verified[id] = VerifiedInstall(entry(id).sha256, fingerprint(directory(id), entry(id)))
        status(id).value = ModelStatus.Ready
    }

    suspend fun verifyInstalled(id: String): Boolean = withContext(Dispatchers.IO) {
        lock(id).withLock { verifyInstalledLocked(id) }
    }
    internal suspend fun verifyInstalledLocked(id: String): Boolean {
        if (ready(id)) return true
        val ok = verifyDirectory(directory(id), entry(id))
        if (ok) installed(id) else { state.verified.remove(id); status(id).value = ModelStatus.NotDownloaded }
        return ok
    }

    /** Recover either side of an interrupted atomic replacement; unverified legacy files are never ready. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        for (e in manifest.models) lock(e.id).withLock {
            val dest = directory(e.id)
            val old = File(dest.path + ".old")
            if (old.exists()) {
                if (verifyDirectory(dest, e)) old.deleteRecursively()
                else if (verifyDirectory(old, e)) {
                    check(!dest.exists() || dest.deleteRecursively())
                    check(old.renameTo(dest))
                }
            }
            File(dest.path + ".staging").deleteRecursively()
            File(dest.path + ".tmp").deleteRecursively()
            verifyInstalledLocked(e.id)
        }
    }

    /** Native construction holds this lock so files cannot be swapped/deleted midway through loading. */
    suspend fun <T> withInstalled(id: String, block: (File) -> T): T = withContext(Dispatchers.IO) {
        lock(id).withLock {
            check(verifyInstalledLocked(id)) { "Model needs to be downloaded" }
            block(directory(id))
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        if (id == "lid" || id == "fastconformer") DownloadManager.cancelFor(context, "automatic")
        DownloadManager.cancelFor(context, id)
        lock(id).withLock {
            state.verified.remove(id)
            val dest = directory(id)
            listOf(dest, File(dest.path + ".old"), File(dest.path + ".staging"), File(dest.path + ".tmp")).forEach {
                check(!it.exists() || it.deleteRecursively()) { "Could not remove model" }
            }
            File(context.cacheDir, "model-$id.tar.bz2").delete()
            status(id).value = ModelStatus.NotDownloaded
        }
    }
    suspend fun deleteCanary() = delete("accurate")
    suspend fun deleteLid() = delete("lid")
    suspend fun deleteFastConformer() = delete("fastconformer")
    suspend fun deleteNemotron560() = delete("nemotron-560")
    suspend fun deleteNemotron160() = delete("nemotron-160")
    suspend fun deleteNemotron() { deleteNemotron560(); deleteNemotron160(); withContext(Dispatchers.IO) { File(context.filesDir, "nemotron").deleteRecursively() } }
    suspend fun deleteAll() { manifest.models.forEach { delete(it.id) } }
    fun hasEnoughSpace(required: Long) = StatFs(context.filesDir.path).availableBytes >= required
    fun updateDownloadProgress(id: String, prog: Float, bytes: Long, total: Long) { status(id).value = ModelStatus.Downloading(prog.coerceIn(0f, 1f), bytes, total) }
    fun setVerifying(id: String) { status(id).value = ModelStatus.Verifying }
    // A UI or diagnostic caller cannot manufacture readiness.
    fun setReady(id: String) { status(id).value = if (ready(id)) ModelStatus.Ready else ModelStatus.NotDownloaded }
    fun setFailed(id: String, err: String) { status(id).value = if (ready(id)) ModelStatus.Ready else ModelStatus.Failed(err) }
    fun resetStatus(id: String) = setReady(id)

    suspend fun verifySha256(file: File, expected: String): Boolean = withContext(Dispatchers.IO) {
        if (!Regex("[a-fA-F0-9]{64}").matches(expected)) return@withContext false
        try { sha256(file).equals(expected, ignoreCase = true) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    companion object {
        internal const val RECEIPT = ".install-receipt.json"
        private data class VerifiedInstall(val archiveSha256: String, val metadata: List<Long>)
        private class State {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val started = AtomicBoolean(false)
            val locks = ConcurrentHashMap<String, Mutex>()
            val statuses = ConcurrentHashMap<String, MutableStateFlow<ModelStatus>>()
            val verified = ConcurrentHashMap<String, VerifiedInstall>()
        }
        private val states = ConcurrentHashMap<String, State>()
        internal suspend fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
