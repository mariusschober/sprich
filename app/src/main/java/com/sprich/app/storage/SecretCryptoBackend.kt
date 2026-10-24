package com.sprich.app.storage

/**
 * Ciphertext that cannot possibly be a valid stored secret (for example a truncated header).
 * This is deliberately distinct from a generic [IllegalArgumentException], which may come from
 * an unrelated bug and must not cause the stored ciphertext to be deleted.
 */
internal class CorruptSecretFormatException(message: String) : Exception(message)

/**
 * Crypto backend for API secret storage.
 * Production implementation uses Android Keystore AES-GCM.
 * Test implementations live in src/test only and must never be used in production.
 */
interface SecretCryptoBackend {
    /**
     * Encrypt plaintext UTF-8 bytes.
     * @return combined bytes: IV (12) + ciphertext+tag
     * @throws Exception on failure — caller must treat as failure closed, not fallback to reversible encoding
     */
    @Throws(Exception::class)
    fun encrypt(plaintext: ByteArray): ByteArray

    /**
     * Decrypt combined bytes.
     * @throws Exception on failure (key invalidated, corrupted)
     */
    @Throws(Exception::class)
    fun decrypt(combined: ByteArray): ByteArray
}
