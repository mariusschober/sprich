package com.sprich.app.storage

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.UUID

sealed interface SecretStoreResult {
    data object Success : SecretStoreResult
    data class Failure(val reason: String) : SecretStoreResult
}

@Serializable private data class BoundSecret(val provider: String, val endpoint: String, val key: String)

/** AES-GCM ciphertext only, in noBackupFilesDir. AtomicFile preserves an existing key on failed replacement. */
open class ApiSecretStore(private val context: Context, private val crypto: SecretCryptoBackend = AndroidKeystoreCryptoBackend()) {
    private fun directory(): File = File(checkNotNull(context.noBackupFilesDir), "api_secrets").also {
        check(it.isDirectory || it.mkdirs()) { "Secure storage unavailable" }
    }
    private fun file(id: String): AtomicFile {
        require(secretIdPattern.matches(id) && id != "." && id != "..")
        return AtomicFile(File(directory(), "$id.enc"))
    }
    private fun unreadableMarker(id: String): File = File(directory(), "$id.unreadable")
    private fun clearUnreadableMarker(id: String) {
        runCatching { unreadableMarker(id).delete() }
    }

    /** Low-level storage API. Production callers use provider-bound references below, on IO. */
    open fun saveSecret(id: String, plaintext: String): SecretStoreResult = synchronized(lock) {
        if (plaintext.isBlank()) return@synchronized try { removeSecret(id); SecretStoreResult.Success }
        catch (_: Exception) { SecretStoreResult.Failure("Could not remove key") }
        try {
            require(plaintext.toByteArray().size <= 16_384)
            val encrypted = crypto.encrypt(plaintext.toByteArray(Charsets.UTF_8))
            val destination = file(id)
            val output = destination.startWrite()
            try { output.write(encrypted); destination.finishWrite(output) }
            catch (e: Exception) { destination.failWrite(output); throw e }
            clearUnreadableMarker(id)
            SecretStoreResult.Success
        } catch (_: Exception) { SecretStoreResult.Failure("Secure storage unavailable. Try again.") }
    }

    open fun loadSecret(id: String): String? = when (val result = readSecret(id)) {
        is SecretRead.Ok -> result.value
        else -> null
    }

    private sealed interface SecretRead {
        data class Ok(val value: String) : SecretRead
        data object Missing : SecretRead
        data object Unavailable : SecretRead
        data object Invalidated : SecretRead
    }

    private fun readSecret(id: String): SecretRead = synchronized(lock) {
        try {
            val source = file(id)
            val backup = File(source.baseFile.path + ".bak")
            if (!source.baseFile.exists() && !backup.exists()) return@synchronized SecretRead.Missing
            if (source.baseFile.length() > 32_768 || backup.length() > 32_768) {
                source.delete()
                return@synchronized SecretRead.Missing
            }
            source.openRead().use { input ->
                val bytes = ByteArray(32_769)
                var size = 0
                while (size < bytes.size) {
                    val count = input.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size > 32_768) {
                    source.delete()
                    return@synchronized SecretRead.Missing
                }
                val value = String(crypto.decrypt(bytes.copyOf(size)), Charsets.UTF_8)
                clearUnreadableMarker(id)
                SecretRead.Ok(value)
            }
        } catch (e: Exception) {
            // A corrupt file or permanently invalidated Keystore key cannot recover. A generic
            // Keystore/IO failure may be transient, so keep the ciphertext for a later retry.
            if (isPermanentInvalidation(e)) {
                runCatching { file(id).delete() }
                clearUnreadableMarker(id)
                SecretRead.Invalidated
            } else {
                SecretRead.Unavailable
            }
        }
    }

    private fun isPermanentInvalidation(error: Throwable): Boolean =
        error is android.security.keystore.KeyPermanentlyInvalidatedException ||
            error is IllegalArgumentException ||
            (error.cause != null && isPermanentInvalidation(error.cause!!))

    fun removeSecret(id: String) = synchronized(lock) {
        file(id).delete()
        clearUnreadableMarker(id)
    }
    fun clearAll() = synchronized(lock) {
        val dir = directory()
        check(dir.deleteRecursively()) { "Could not clear keys" }
        (crypto as? AndroidKeystoreCryptoBackend)?.deleteKey()
    }
    enum class SecretHealth { MISSING, AVAILABLE, UNAVAILABLE }
    fun secretHealth(id: String): SecretHealth = when (readSecret(id)) {
        is SecretRead.Ok -> SecretHealth.AVAILABLE
        SecretRead.Missing, SecretRead.Invalidated -> SecretHealth.MISSING
        SecretRead.Unavailable -> SecretHealth.UNAVAILABLE
    }
    fun hasSecret(id: String): Boolean = !loadSecret(id).isNullOrBlank()
    fun hasDecryptableSecret(id: String) = secretHealth(id) == SecretHealth.AVAILABLE

    /**
     * Best-effort removal of abandoned bound secrets that can no longer be decrypted.
     * A transient read failure only records a marker and keeps the ciphertext; a later failed
     * prune removes it. Permanent invalidation removes it immediately. Decryptable files are
     * retained because an in-flight utterance plan may still use them, and non-bound legacy ids
     * are never touched.
     */
    fun pruneUnreferenced(referenced: Set<String>) = synchronized(lock) {
        val dir = directory()
        val candidates = dir.listFiles().orEmpty().asSequence()
            .filter { it.isFile }
            .mapNotNull { candidate ->
                when {
                    candidate.name.endsWith(".enc.bak") -> candidate.name.removeSuffix(".enc.bak")
                    candidate.name.endsWith(".enc") -> candidate.name.removeSuffix(".enc")
                    else -> null
                }
            }
            .filter { it.startsWith("bound_") && it !in referenced && secretIdPattern.matches(it) }
            .distinct()
            .toList()
        candidates.forEach { id ->
            when (readSecret(id)) {
                is SecretRead.Ok -> clearUnreadableMarker(id)
                SecretRead.Missing -> clearUnreadableMarker(id)
                SecretRead.Invalidated -> deleteUnreadableFiles(dir, id)
                SecretRead.Unavailable -> {
                    val marker = unreadableMarker(id)
                    if (marker.exists()) {
                        deleteUnreadableFiles(dir, id)
                    } else {
                        // First failure: remember it without ever storing key material.
                        runCatching { marker.writeText(System.currentTimeMillis().toString()) }
                    }
                }
            }
        }
    }

    private fun deleteUnreadableFiles(dir: File, id: String) {
        runCatching { file(id).delete() }
        // AtomicFile keeps its backup sibling if an earlier write was interrupted.
        runCatching { File(dir, "$id.enc.bak").delete() }
        clearUnreadableMarker(id)
    }

    suspend fun saveBoundSecret(provider: String, endpoint: String, key: String): String? {
        val normalized = normalizeEndpoint(endpoint) ?: return null
        if (provider.isBlank() || key.isBlank() || key.length > 4096 || key.any { it.isISOControl() }) return null
        // A new reference per save freezes the credential revision in every utterance plan.
        val ref = "bound_${UUID.randomUUID()}"
        return try {
            withContext(Dispatchers.IO) {
                val result = saveSecret(ref, Json.encodeToString(BoundSecret(provider, normalized, key.trim())))
                ref.takeIf { result is SecretStoreResult.Success }
            }
        } catch (e: CancellationException) {
            // withContext may cancel after the atomic write, before delivering its reference.
            withContext(NonCancellable + Dispatchers.IO) { removeSecret(ref) }
            throw e
        }
    }
    suspend fun loadBoundSecret(ref: String, provider: String, endpoint: String): String? = withContext(Dispatchers.IO) {
        if (!ref.startsWith("bound_")) return@withContext null // Unbound legacy keys require re-entry.
        val normalized = normalizeEndpoint(endpoint) ?: return@withContext null
        val bound = try { Json.decodeFromString<BoundSecret>(loadSecret(ref) ?: return@withContext null) } catch (_: Exception) { return@withContext null }
        bound.key.takeIf { bound.provider == provider && bound.endpoint == normalized && it.isNotBlank() }
    }
    companion object {
        private val lock = Any()
        private val secretIdPattern = Regex("[A-Za-z0-9._-]{1,120}")
        private fun normalizeEndpoint(endpoint: String): String? {
            val url = endpoint.toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || url.query != null) return null
            return url.toString().trimEnd('/')
        }
    }
}
