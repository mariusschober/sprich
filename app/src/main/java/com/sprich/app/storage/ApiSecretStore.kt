package com.sprich.app.storage

import android.content.Context
import android.util.Base64
import java.io.File

/**
 * BYOK secret store — Android Keystore-backed AES-GCM.
 * Ciphertext stored in noBackupFilesDir (not in DataStore, not backed up).
 * DataStore keeps only provider/model/endpoint/credential ID reference.
 *
 * Required invariants:
 * - API key absent from DataStore, logs, diagnostics, backups, crash breadcrumbs
 * - Clear local data deletes all credentials
 * - Production storage is Keystore AES-GCM or FAIL CLOSED — never reversible fallback
 */

sealed interface SecretStoreResult {
    data object Success : SecretStoreResult
    data class Failure(val reason: String) : SecretStoreResult
}

open class ApiSecretStore(
    private val context: Context,
    private val crypto: SecretCryptoBackend = AndroidKeystoreCryptoBackend(),
) {
    private fun secretsDir(): File {
        // noBackupFilesDir is excluded from auto backup
        val dir = try { context.noBackupFilesDir } catch (_: Exception) { context.filesDir }
        val apiDir = File(dir, "api_secrets")
        if (!apiDir.exists()) apiDir.mkdirs()
        return apiDir
    }

    private fun fileFor(id: String): File {
        // sanitize id
        val safe = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        return File(secretsDir(), "$safe.enc")
    }

    /**
     * Save secret securely. Returns Success only after durable encrypted storage.
     * On any crypto/IO failure returns Failure and guarantees no recoverable plaintext file remains.
     * Blank plaintext removes the secret.
     */
    open fun saveSecret(id: String, plaintext: String): SecretStoreResult {
        if (plaintext.isBlank()) {
            removeSecret(id)
            return SecretStoreResult.Success
        }
        return try {
            val combined = crypto.encrypt(plaintext.toByteArray(Charsets.UTF_8))
            val b64 = Base64.encodeToString(combined, Base64.NO_WRAP)
            val f = fileFor(id)
            // Write atomically via temp + rename where possible
            try {
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeText(b64)
                if (!tmp.renameTo(f)) {
                    // fallback to direct write if rename fails
                    f.writeText(b64)
                    try { tmp.delete() } catch (_: Exception) {}
                }
            } catch (_: Exception) {
                f.writeText(b64)
            }
            SecretStoreResult.Success
        } catch (e: Exception) {
            // FAIL CLOSED: ensure no plaintext or partial file remains
            try { fileFor(id).delete() } catch (_: Exception) {}
            SecretStoreResult.Failure(e.message ?: "secure storage unavailable")
        }
    }

    open fun loadSecret(id: String): String? {
        val f = fileFor(id)
        if (!f.exists()) return null
        return try {
            val b64 = f.readText().trim()
            if (b64.isBlank()) return null
            val combined = Base64.decode(b64, Base64.NO_WRAP)
            val pt = crypto.decrypt(combined)
            String(pt, Charsets.UTF_8)
        } catch (e: Exception) {
            // Corrupted or key invalidated — remove to force re-entry (fail closed)
            try { f.delete() } catch (_: Exception) {}
            null
        }
    }

    fun removeSecret(id: String) {
        try { fileFor(id).delete() } catch (_: Exception) {}
    }

    fun clearAll() {
        try {
            secretsDir().listFiles()?.forEach { try { it.delete() } catch (_: Exception) {} }
            // Also try to delete key entry if backend supports it
            try { (crypto as? AndroidKeystoreCryptoBackend)?.deleteKey() } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    /**
     * Returns true only if a decryptable secure credential exists.
     * File existence alone is insufficient (key may be invalidated).
     */
    fun hasSecret(id: String): Boolean {
        val f = fileFor(id)
        if (!f.exists()) return false
        // Attempt decrypt to verify usability; loadSecret deletes corrupted file as side effect
        return try {
            val b64 = f.readText().trim()
            if (b64.isBlank()) return false
            val combined = Base64.decode(b64, Base64.NO_WRAP)
            crypto.decrypt(combined)
            true
        } catch (_: Exception) {
            try { f.delete() } catch (_: Exception) {}
            false
        }
    }

    /** Returns true if decryptable, false if missing or corrupted (and deletes corrupted). */
    fun hasDecryptableSecret(id: String): Boolean = hasSecret(id)
}
