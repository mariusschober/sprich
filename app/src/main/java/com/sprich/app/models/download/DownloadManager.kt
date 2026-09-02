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

    @Volatile private var currentCall: okhttp3.Call? = null
    @Volatile private var cancelled = false

    fun cancel(){
        cancelled = true
        currentCall?.cancel()
    }

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
        cancelled = false
        // Space check
        val stat = StatFs(context.filesDir.path)
        if (stat.availableBytes < requiredBytes) {
            modelManager.setFailed(id, "Not enough storage. Need ${requiredBytes/1024/1024} MB free, have ${stat.availableBytes/1024/1024} MB.")
            throw Exception("no space")
        }
        // Atomic install: download to tmp, verify, extract to .tmp dir, rename — keep tmpFile for resume
        val extractTmp = File(context.filesDir, "${destDir.name}.tmp")
        extractTmp.deleteRecursively()
        try {
            var downloaded: Long = 0
            var total: Long = -1
            val reqBuilder = Request.Builder().url(url)
            if (tmpFile.exists() && tmpFile.length()>0) {
                downloaded = tmpFile.length()
                reqBuilder.header("Range", "bytes=$downloaded-")
            }
            val call = client.newCall(reqBuilder.build())
            currentCall = call
            call.execute().use { resp ->
                // Block redirects for model download (integrity via SHA, but avoid following attacker redirects)
                if (resp.code in 300..399) throw Exception("Redirect blocked ${resp.code}")
                // If we asked for Range but server answered 200, it doesn't support resume — restart
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
                            if (cancelled) throw Exception("Cancelled")
                            out.write(buf, 0, n)
                            downloaded += n
                            val prog = if (total>0) downloaded.toFloat()/total else 0f
                            modelManager.updateDownloadProgress(id, prog, downloaded, total)
                            onProgress?.invoke(prog)
                        }
                    }
                }
            }
            currentCall = null
            if (cancelled) throw Exception("Cancelled")

            // Verify sha if pinned
            modelManager.setVerifying(id)
            if (expectedSha.isNotBlank()) {
                val ok = modelManager.verifySha256(tmpFile, expectedSha)
                if (!ok) throw Exception("Checksum mismatch")
            }

            extractTmp.mkdirs()
            if (tmpFile.name.endsWith(".tar.bz2")) {
                extractTarBz2(tmpFile, extractTmp)
            } else {
                // Plain GGUF (Nemotron) — map tmp name to expected model file
                val targetName = if (id == "streaming") "model_q4_k.gguf" else tmpFile.name.removeSuffix(".tmp")
                tmpFile.copyTo(File(extractTmp, targetName), overwrite = true)
            }

            // Validate needed files exist
            val needed = modelManager.getManifest().models.find{it.id==id}?.files ?: emptyList()
            for (f in needed) {
                if (!File(extractTmp, f).exists() && !File(extractTmp, extractTmp.list()?.firstOrNull() ?: "").let{ File(it, f).exists() }) {
                    // Check if nested dir contains it (tar top-level)
                    val found = extractTmp.walk().any{ it.name==f }
                    if (!found) throw Exception("Missing $f after extract")
                }
            }
            // Flatten if tar had top-level dir
            val top = extractTmp.listFiles()?.firstOrNull { it.isDirectory }
            val sourceRoot = if (top != null && needed.all{ File(top, it).exists() }) top else extractTmp

            destDir.deleteRecursively()
            // Atomic rename: move sourceRoot to destDir
            if (!sourceRoot.renameTo(destDir)) {
                // fallback copy
                destDir.mkdirs()
                sourceRoot.listFiles()?.forEach{ it.copyRecursively(File(destDir, it.name), overwrite=true)}
                extractTmp.deleteRecursively()
            } else {
                if (sourceRoot != extractTmp) extractTmp.deleteRecursively()
            }
            tmpFile.delete()
            modelManager.setReady(id)
        } catch (e: Exception){
            currentCall=null
            if (e.message=="Cancelled") {
                tmpFile.delete()
                modelManager.setFailed(id, "Cancelled")
            } else {
                // keep tmpFile for resume on network failure
                modelManager.setFailed(id, e.message ?: "Download failed")
                extractTmp.deleteRecursively()
            }
            throw e
        }
    }

    private fun extractTarBz2(tarFile: File, dest: File){
        tarFile.inputStream().use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bzis ->
                    TarArchiveInputStream(bzis).use { tais ->
                        var entry = tais.nextTarEntry
                        while (entry != null) {
                            val name = entry.name.substringAfter('/', "")
                            if (name.isBlank()) { entry = tais.nextTarEntry; continue}
                            // Only needed files or keep all but skip test_wavs
                            if (name.startsWith("test_wavs/")) { entry = tais.nextTarEntry; continue}
                            val outFile = File(dest, name)
                            // path traversal guard
                            if (!outFile.canonicalPath.startsWith(dest.canonicalPath)) throw Exception("Path traversal")
                            if (entry.isDirectory) outFile.mkdirs() else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out -> tais.copyTo(out) }
                            }
                            entry = tais.nextTarEntry
                        }
                    }
                }
            }
        }
    }
}
