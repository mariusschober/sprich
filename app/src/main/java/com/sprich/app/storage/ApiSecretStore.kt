package com.sprich.app.storage

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
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
        require(Regex("[A-Za-z0-9._-]{1,120}").matches(id) && id != "." && id != "..")
        return AtomicFile(File(directory(), "$id.enc"))
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
            SecretStoreResult.Success
        } catch (_: Exception) { SecretStoreResult.Failure("Secure storage unavailable. Try again.") }
    }

    open fun loadSecret(id: String): String? = synchronized(lock) {
        try {
            val source = file(id)
            val backup = File(source.baseFile.path + ".bak")
            if (!source.baseFile.exists() && !backup.exists()) return@synchronized null
            if (source.baseFile.length() > 32_768 || backup.length() > 32_768) { source.delete(); return@synchronized null }
            source.openRead().use { input ->
                val bytes = ByteArray(32_769)
                var size = 0
                while (size < bytes.size) {
                    val count = input.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size > 32_768) { source.delete(); return@synchronized null }
                String(crypto.decrypt(bytes.copyOf(size)), Charsets.UTF_8)
            }
        } catch (_: Exception) { runCatching { file(id).delete() }; null }
    }
    fun removeSecret(id: String) = synchronized(lock) { file(id).delete() }
    fun clearAll() = synchronized(lock) {
        val dir = directory()
        check(dir.deleteRecursively()) { "Could not clear keys" }
        (crypto as? AndroidKeystoreCryptoBackend)?.deleteKey()
    }
    fun hasSecret(id: String): Boolean = !loadSecret(id).isNullOrBlank()
    fun hasDecryptableSecret(id: String) = hasSecret(id)

    suspend fun saveBoundSecret(provider: String, endpoint: String, key: String): String? = withContext(Dispatchers.IO) {
        val normalized = normalizeEndpoint(endpoint) ?: return@withContext null
        if (provider.isBlank() || key.isBlank()) return@withContext null
        // A new reference per save freezes the credential revision in every utterance plan.
        val ref = "bound_${UUID.randomUUID()}"
        val result = saveSecret(ref, Json.encodeToString(BoundSecret(provider, normalized, key.trim())))
        ref.takeIf { result is SecretStoreResult.Success }
    }
    suspend fun loadBoundSecret(ref: String, provider: String, endpoint: String): String? = withContext(Dispatchers.IO) {
        if (!ref.startsWith("bound_")) return@withContext null // Unbound legacy keys require re-entry.
        val normalized = normalizeEndpoint(endpoint) ?: return@withContext null
        val bound = try { Json.decodeFromString<BoundSecret>(loadSecret(ref) ?: return@withContext null) } catch (_: Exception) { return@withContext null }
        bound.key.takeIf { bound.provider == provider && bound.endpoint == normalized && it.isNotBlank() }
    }
    companion object {
        private val lock = Any()
        private fun normalizeEndpoint(endpoint: String): String? {
            val url = endpoint.toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || url.query != null) return null
            return url.toString().trimEnd('/')
        }
    }
}
