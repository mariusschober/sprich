package com.sprich.app.models.download

import android.content.Context
import com.sprich.app.models.manager.ModelEntry
import com.sprich.app.models.manager.ModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Model transfers have no credentials. Cloud clients never use this redirect policy. */
internal object ModelRedirectPolicy {
    private val hosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    fun allowed(url: HttpUrl): Boolean = url.isHttps && url.port == 443 && url.host in hosts && url.username.isEmpty() && url.password.isEmpty()
    fun next(from: HttpUrl, location: String, count: Int): HttpUrl {
        require(count < 4) { "Too many redirects" }
        return (from.resolve(location) ?: error("Invalid redirect")).also { require(allowed(it)) { "Untrusted model host" } }
    }
}

class DownloadManager internal constructor(context: Context, private val modelManager: ModelManager, private val httpClient: OkHttpClient) {
    constructor(context: Context, modelManager: ModelManager) : this(context, modelManager, client)
    private val context = context.applicationContext
    private fun key(id: String) = "${context.filesDir.absolutePath}:$id"
    fun cancel() { cancelModel("automatic"); modelManager.getManifest().models.forEach { cancelModel(it.id) } }
    fun cancelModel(id: String) = cancelFor(context, id)
    suspend fun downloadCanary(onProgress: ((Float) -> Unit)? = null) = download("accurate", onProgress)
    suspend fun downloadLid(onProgress: ((Float) -> Unit)? = null) = download("lid", onProgress)
    suspend fun downloadFastConformer(onProgress: ((Float) -> Unit)? = null) = download("fastconformer", onProgress)
    suspend fun downloadNemotron(onProgress: ((Float) -> Unit)? = null) = downloadNemotron560(onProgress)
    suspend fun downloadNemotron560(onProgress: ((Float) -> Unit)? = null) = download("nemotron-560", onProgress)
    suspend fun downloadNemotron160(onProgress: ((Float) -> Unit)? = null) = download("nemotron-160", onProgress)
    suspend fun downloadAutomatic() {
        val operation = synchronized(running) {
            running[key("automatic")] ?: Operation().also { op ->
                op.job = modelManager.scope.async(start = CoroutineStart.LAZY) {
                    try {
                        if (!modelManager.verifyInstalled("lid")) downloadLid()
                        currentCoroutineContext().ensureActive()
                        if (!modelManager.verifyInstalled("fastconformer")) downloadFastConformer()
                    } finally { synchronized(running) { if (running[key("automatic")] === op) running.remove(key("automatic")) } }
                }
                running[key("automatic")] = op
                op.job.start()
            }
        }
        operation.job.await()
    }

    private suspend fun download(id: String, onProgress: ((Float) -> Unit)?) {
        val operation = synchronized(running) {
            running[key(id)] ?: Operation().also { op ->
                op.job = modelManager.scope.async(start = CoroutineStart.LAZY) {
                    try { modelManager.lock(id).withLock { install(modelManager.getManifest().models.single { it.id == id }, op, onProgress) } }
                    finally { synchronized(running) { if (running[key(id)] === op) running.remove(key(id)) } }
                }
                running[key(id)] = op
                op.job.start()
            }
        }
        // The process owns the transfer; navigating away doesn't silently strand it.
        operation.job.await()
    }

    private suspend fun response(url: HttpUrl, op: Operation): Response {
        require(ModelRedirectPolicy.allowed(url)) { "Untrusted model host" }
        var current = url
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val call = httpClient.newCall(Request.Builder().url(current).build())
            op.call = call
            if (op.job.isCancelled) call.cancel()
            val response = call.execute()
            if (response.code !in 300..399) return response
            val location = response.header("Location")
            response.close()
            current = ModelRedirectPolicy.next(current, location ?: error("Missing redirect"), redirects++)
        }
    }

    private suspend fun install(e: ModelEntry, op: Operation, onProgress: ((Float) -> Unit)?) {
        val dest = modelManager.directory(e.id)
        val staging = File(dest.path + ".staging")
        val old = File(dest.path + ".old")
        val archive = File(context.cacheDir, "model-${e.id}.tar.bz2")
        try {
            check(Regex("[a-f0-9]{64}").matches(e.sha256)) { "Missing model checksum" }
            check(modelManager.hasEnoughSpace(e.requiredFreeBytes)) { "Not enough storage. Free some space and try again." }
            if (!dest.exists() && old.exists()) check(old.renameTo(dest)) { "Model recovery required" }
            check(!staging.exists() || staging.deleteRecursively())
            archive.delete() // Restart cleanly; never append an unverified partial response.
            modelManager.updateDownloadProgress(e.id, 0f, 0, e.sizeBytes)
            response(e.sourceUrl.toHttpUrl(), op).use { response ->
                check(response.code == 200) { "Download unavailable. Try again later." }
                val body = response.body ?: error("Empty download")
                check(body.contentLength() < 0 || body.contentLength() == e.sizeBytes) { "Unexpected download size" }
                body.byteStream().use { input ->
                    archive.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytes = 0L
                        var lastProgress = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            bytes += n
                            check(bytes <= e.sizeBytes) { "Download too large" }
                            output.write(buffer, 0, n)
                            if (bytes - lastProgress >= 256 * 1024 || bytes == e.sizeBytes) {
                                val progress = bytes.toFloat() / e.sizeBytes
                                modelManager.updateDownloadProgress(e.id, progress, bytes, e.sizeBytes)
                                onProgress?.invoke(progress)
                                lastProgress = bytes
                            }
                        }
                        check(bytes == e.sizeBytes) { "Download incomplete" }
                        output.fd.sync()
                    }
                }
            }
            modelManager.setVerifying(e.id)
            check(modelManager.verifySha256(archive, e.sha256)) { "Download could not be verified. Try again." }
            check(staging.mkdirs())
            extract(archive, staging, e)
            modelManager.writeReceipt(staging, e)
            currentCoroutineContext().ensureActive()
            // Same-filesystem renames only. Never fall back to copying over a working install.
            // Recovery on the next process start handles death between either rename.
            check(!old.exists() || old.deleteRecursively())
            if (dest.exists()) check(dest.renameTo(old)) { "Could not preserve installed model" }
            if (!staging.renameTo(dest)) {
                if (old.exists()) check(old.renameTo(dest)) { "Model recovery required" }
                error("Could not install model")
            }
            modelManager.installed(e.id)
            old.deleteRecursively()
        } catch (cancelled: CancellationException) {
            modelManager.resetStatus(e.id)
            throw cancelled
        } catch (failure: Exception) {
            // OkHttp cancellation wakes a blocking read with IOException, not necessarily
            // CancellationException. A user's Cancel must not become a download error.
            if (!currentCoroutineContext().isActive) {
                modelManager.resetStatus(e.id)
                throw CancellationException("Model download cancelled").also { it.initCause(failure) }
            }
            val reason = when {
                failure.message?.contains("storage") == true || failure.message?.contains("ENOSPC") == true -> com.sprich.app.R.string.download_storage
                failure is IOException -> com.sprich.app.R.string.download_failed
                else -> com.sprich.app.R.string.download_invalid
            }
            modelManager.setFailed(e.id, context.getString(reason))
            throw failure
        } finally {
            op.call?.cancel()
            op.call = null
            archive.delete()
            staging.deleteRecursively()
        }
    }

    private suspend fun extract(archive: File, dest: File, e: ModelEntry) {
        val written = mutableSetOf<String>()
        var count = 0
        var total = 0L
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tar.nextTarEntry ?: break
                check(++count <= 1024 && entry.size >= 0 && entry.size <= e.requiredFreeBytes) { "Invalid archive" }
                total += entry.size
                check(total <= e.requiredFreeBytes) { "Archive too large" }
                check(!entry.isLink && !entry.isSymbolicLink && (entry.isFile || entry.isDirectory)) { "Unsafe archive entry" }
                val parts = entry.name.split('/')
                check(!entry.name.startsWith('/') && '\\' !in entry.name && parts.none { it == ".." }) { "Unsafe archive path" }
                if (!entry.isFile) continue
                val normalized = parts.filter { it.isNotEmpty() && it != "." }
                val name = normalized.lastOrNull() ?: continue
                if (name !in e.files) continue
                check(normalized.size <= 2 && written.add(name)) { "Ambiguous archive entry" }
                File(dest, name).outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = tar.read(buffer)
                        if (n < 0) break
                        bytes += n
                        check(bytes <= entry.size)
                        output.write(buffer, 0, n)
                    }
                    check(bytes == entry.size)
                    output.fd.sync()
                }
            }
        }
        check(written == e.files.toSet()) { "Incomplete model archive" }
    }

    companion object {
        private class Operation { lateinit var job: Deferred<Unit>; @Volatile var call: Call? = null }
        private val running = mutableMapOf<String, Operation>()
        private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        internal fun cancelFor(context: Context, id: String) {
            synchronized(running) { running["${context.applicationContext.filesDir.absolutePath}:$id"]?.let { it.job.cancel(); it.call?.cancel() } }
        }
    }
}
