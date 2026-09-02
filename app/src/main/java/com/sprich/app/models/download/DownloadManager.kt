package com.sprich.app.models.download

import android.content.Context
import android.os.StatFs
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.models.manager.ModelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.util.concurrent.TimeUnit

class DownloadManager(
    private val context: Context,
    private val modelManager: ModelManager,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    // Per-model cancellation — one model's Cancel must not cancel another
    private val calls = mutableMapOf<String, okhttp3.Call>()
    private val cancelledIds = mutableSetOf<String>()
    private val callMutex = Any()

    fun cancel(){
        synchronized(callMutex) {
            calls.values.forEach { try { it.cancel() } catch (_: Exception) {} }
            calls.clear()
            // legacy global cancel sets all to cancelled
            cancelledIds.addAll(listOf("accurate","lid","fastconformer","nemotron-560","nemotron-160","streaming","nemotron"))
        }
    }
    fun cancelModel(id: String) {
        synchronized(callMutex) {
            cancelledIds.add(id)
            calls[id]?.let { try { it.cancel() } catch (_: Exception) {} }
            calls.remove(id)
        }
    }
    private fun isCancelled(id: String) = synchronized(callMutex) { cancelledIds.contains(id) }
    private fun setCall(id: String, call: okhttp3.Call) = synchronized(callMutex) { calls[id] = call }
    private fun clearCall(id: String) = synchronized(callMutex) { calls.remove(id) }

    suspend fun downloadCanary(onProgress: ((Float)->Unit)? = null) = withContext(Dispatchers.IO) {
        val entry = modelManager.getManifest().models.find{ it.id=="accurate"} ?: throw Exception("manifest missing")
        downloadAndExtract(
            id = "accurate",
            url = entry.sourceUrl,
            destDir = File(context.filesDir, "canary"),
            tmpFile = File(context.cacheDir, "canary.tar.bz2"),
            expectedSha = entry.sha256,
            requiredBytes = entry.requiredFreeBytes,
            onProgress = onProgress,
        )
    }

    suspend fun downloadNemotron(onProgress: ((Float)->Unit)? = null) = withContext(Dispatchers.IO) {
        // Legacy GGUF — kept for backward compat, delegates to 560
        downloadNemotron560(onProgress)
    }

    suspend fun downloadLid(onProgress: ((Float)->Unit)? = null) = withContext(Dispatchers.IO) {
        val entry = modelManager.getManifest().models.find{ it.id=="lid"} ?: throw Exception("manifest missing lid")
        downloadAndExtract(
            id = "lid",
            url = entry.sourceUrl,
            destDir = File(context.filesDir, "whisper-tiny"),
            tmpFile = File(context.cacheDir, "whisper-tiny.tar.bz2"),
            expectedSha = entry.sha256,
            requiredBytes = entry.requiredFreeBytes,
            onProgress = onProgress,
        )
    }

    suspend fun downloadFastConformer(onProgress: ((Float)->Unit)? = null) = withContext(Dispatchers.IO) {
        val entry = modelManager.getManifest().models.find{ it.id=="fastconformer"} ?: throw Exception("manifest missing fastconformer")
        downloadAndExtract(
            id = "fastconformer",
            url = entry.sourceUrl,
            destDir = File(context.filesDir, "fastconformer"),
            tmpFile = File(context.cacheDir, "fastconformer.tar.bz2"),
            expectedSha = entry.sha256,
            requiredBytes = entry.requiredFreeBytes,
            onProgress = onProgress,
        )
    }

    suspend fun downloadNemotron560(onProgress: ((Float)->Unit)? = null) = withContext(Dispatchers.IO) {
        val entry = modelManager.getManifest().models.find{ it.id=="nemotron-560"} ?: throw Exception("manifest missing nemotron-560")
        downloadAndExtract(
            id = "nemotron-560",
            url = entry.sourceUrl,
            destDir = File(context.filesDir, "nemotron-560"),
            tmpFile = File(context.cacheDir, "nemotron-560.tar.bz2"),
            expectedSha = entry.sha256,
            requiredBytes = entry.requiredFreeBytes,
            onProgress = onProgress,
        )
    }

    suspend fun downloadNemotron160(onProgress: ((Float)->Unit)? = null) = withContext(Dispatchers.IO) {
        val entry = modelManager.getManifest().models.find{ it.id=="nemotron-160"} ?: throw Exception("manifest missing nemotron-160")
        downloadAndExtract(
            id = "nemotron-160",
            url = entry.sourceUrl,
            destDir = File(context.filesDir, "nemotron-160"),
            tmpFile = File(context.cacheDir, "nemotron-160.tar.bz2"),
            expectedSha = entry.sha256,
            requiredBytes = entry.requiredFreeBytes,
            onProgress = onProgress,
        )
    }

    private suspend fun downloadAndExtract(
        id: String,
        url: String,
        destDir: File,
        tmpFile: File,
        expectedSha: String,
        requiredBytes: Long,
        onProgress: ((Float)->Unit)?,
    ){
        synchronized(callMutex) { cancelledIds.remove(id) }
        // Space check
        val stat = StatFs(context.filesDir.path)
        if (stat.availableBytes < requiredBytes) {
            modelManager.setFailed(id, "Not enough storage. Need ${requiredBytes/1024/1024} MB free, have ${stat.availableBytes/1024/1024} MB.")
            throw Exception("no space")
        }
        // Atomic install: download to tmp, verify SHA, extract into staging, verify staging, atomic swap, delete old — never delete old before verified
        val extractTmp = File(context.filesDir, "${destDir.name}.tmp")
        extractTmp.deleteRecursively()
        // Staging dir for extracted model before swap
        val stagingDir = File(context.filesDir, "${destDir.name}.staging")
        stagingDir.deleteRecursively()
        try {
            var downloaded: Long = 0
            var total: Long = -1
            val reqBuilder = Request.Builder().url(url)
            if (tmpFile.exists() && tmpFile.length()>0) {
                downloaded = tmpFile.length()
                reqBuilder.header("Range", "bytes=$downloaded-")
            }
            val call = client.newCall(reqBuilder.build())
            setCall(id, call)
            call.execute().use { resp ->
                if (resp.code in 300..399) throw Exception("Redirect blocked ${resp.code}")
                if (downloaded > 0 && resp.code == 200) {
                    tmpFile.delete()
                    downloaded = 0
                } else if (!resp.isSuccessful && resp.code != 206) {
                    throw Exception("HTTP ${resp.code}")
                }
                val body = resp.body ?: throw Exception("empty body")
                total = body.contentLength().let{ if (it==-1L) -1 else it + downloaded }
                val append = downloaded > 0 && resp.code == 206
                body.byteStream().use { inp ->
                    FileOutputStream(tmpFile, append).use { out ->
                        val buf = ByteArray(64*1024)
                        var n: Int
                        while (inp.read(buf).also{ n=it } != -1) {
                            if (isCancelled(id)) throw Exception("Cancelled")
                            out.write(buf, 0, n)
                            downloaded += n
                            val prog = if (total>0) downloaded.toFloat()/total else 0f
                            modelManager.updateDownloadProgress(id, prog, downloaded, total)
                            onProgress?.invoke(prog)
                        }
                    }
                }
            }
            clearCall(id)
            if (isCancelled(id)) throw Exception("Cancelled")

            // Verify SHA before extraction
            modelManager.setVerifying(id)
            if (expectedSha.isNotBlank()) {
                val ok = modelManager.verifySha256(tmpFile, expectedSha)
                if (!ok) throw Exception("Checksum mismatch")
            }

            // Extract into staging, verify staging, then atomic swap — never delete working model before replacement verified
            stagingDir.mkdirs()
            if (tmpFile.name.endsWith(".tar.bz2")) {
                extractTarBz2Bounded(tmpFile, stagingDir)
            } else {
                val targetName = if (id == "streaming") "model_q4_k.gguf" else tmpFile.name.removeSuffix(".tmp")
                tmpFile.copyTo(File(stagingDir, targetName), overwrite = true)
            }

            // Validate needed files exist in staging
            val needed = modelManager.getManifest().models.find{it.id==id}?.files ?: emptyList()
            for (f in needed) {
                if (!File(stagingDir, f).exists() && !File(stagingDir, stagingDir.list()?.firstOrNull() ?: "").let{ File(it, f).exists() }) {
                    val found = stagingDir.walk().any{ it.name==f }
                    if (!found) throw Exception("Missing $f after extract")
                }
            }
            // Flatten if tar had top-level dir
            val top = stagingDir.listFiles()?.firstOrNull { it.isDirectory }
            val sourceRoot = if (top != null && needed.all{ File(top, it).exists() }) top else stagingDir

            // Preserve old model until after staging verified — stage verified above, now swap
            val oldBackup = if (destDir.exists()) File(context.filesDir, "${destDir.name}.old") else null
            if (oldBackup != null) {
                oldBackup.deleteRecursively()
                // Move current to backup atomically
                destDir.renameTo(oldBackup)
            }
            // Move staging to dest
            val swapped = if (!sourceRoot.renameTo(destDir)) {
                destDir.mkdirs()
                sourceRoot.listFiles()?.forEach{ it.copyRecursively(File(destDir, it.name), overwrite=true)}
                stagingDir.deleteRecursively()
                true
            } else {
                if (sourceRoot != stagingDir) stagingDir.deleteRecursively() else { /* sourceRoot was staging, already moved */ }
                true
            }
            if (swapped) {
                // Only now delete old backup and staging residue
                oldBackup?.deleteRecursively()
                extractTmp.deleteRecursively()
                stagingDir.deleteRecursively()
                tmpFile.delete()
                modelManager.setReady(id)
            } else {
                throw Exception("Atomic swap failed")
            }
        } catch (e: Exception){
            clearCall(id)
            if (e.message=="Cancelled") {
                tmpFile.delete()
                stagingDir.deleteRecursively()
                extractTmp.deleteRecursively()
                modelManager.setFailed(id, "Cancelled")
            } else {
                modelManager.setFailed(id, e.message ?: "Download failed")
                extractTmp.deleteRecursively()
                stagingDir.deleteRecursively()
            }
            throw e
        } finally {
            synchronized(callMutex) { cancelledIds.remove(id) }
        }
    }

    private fun extractTarBz2(tarFile: File, dest: File) = extractTarBz2Bounded(tarFile, dest)

    private fun extractTarBz2Bounded(tarFile: File, dest: File){
        val destCanonical = dest.canonicalPath + File.separator
        var fileCount = 0
        var totalBytes: Long = 0
        val maxFiles = 2000
        val maxTotalBytes = 800L * 1024 * 1024 // 800MB cap for all models (largest 475MB)
        val maxSingleFile = 350L * 1024 * 1024 // 350MB cap per file
        tarFile.inputStream().use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bzis ->
                    TarArchiveInputStream(bzis).use { tais ->
                        var entry = tais.nextTarEntry
                        while (entry != null) {
                            // Reject symlinks/hard links/special
                            if (entry.isSymbolicLink || entry.isLink) throw Exception("Symlink/hardlink rejected: ${entry.name}")
                            if (!entry.isFile && !entry.isDirectory) throw Exception("Unsupported entry type: ${entry.name}")
                            val name = entry.name.substringAfter('/', "")
                            if (name.isBlank()) { entry = tais.nextTarEntry; continue}
                            if (name.startsWith("test_wavs/")) { entry = tais.nextTarEntry; continue}
                            val outFile = File(dest, name)
                            // True normalized path containment — must be under destCanonical
                            val outCanonical = outFile.canonicalPath
                            if (!outCanonical.startsWith(destCanonical)) throw Exception("Path traversal: ${entry.name}")
                            // Reject traversal via symlink in parent path (already canonicalized)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                if (entry.size > maxSingleFile) throw Exception("File too large: ${entry.name} ${entry.size}")
                                fileCount++
                                if (fileCount > maxFiles) throw Exception("Too many files: $fileCount")
                                totalBytes += entry.size
                                if (totalBytes > maxTotalBytes) throw Exception("Total extraction too large: $totalBytes")
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out -> tais.copyTo(out) }
                                // Verify written size matches entry size if known
                                if (entry.size >=0 && outFile.length() != entry.size) throw Exception("Size mismatch: ${entry.name}")
                            }
                            entry = tais.nextTarEntry
                        }
                    }
                }
            }
        }
    }
}
