package com.sprich.app.storage

/**
 * Test-only fake crypto backend — uses simple reversible transform but lives ONLY in src/test.
 * Production code never uses this; it is injected via ApiSecretStore constructor for Robolectric.
 * Uses XOR with fixed key to simulate encrypt/decrypt without Android Keystore.
 */
class FakeSecretCryptoBackend : SecretCryptoBackend {
    private val key = "test-fake-key-32-bytes-long!!".toByteArray()

    override fun encrypt(plaintext: ByteArray): ByteArray {
        // Simple XOR + 12-byte IV prefix (zeros) to mimic GCM structure for file format
        val iv = ByteArray(12) { 0x42 }
        val ct = ByteArray(plaintext.size) { i -> (plaintext[i].toInt() xor key[i % key.size].toInt()).toByte() }
        return iv + ct
    }

    override fun decrypt(combined: ByteArray): ByteArray {
        if (combined.size <= 12) throw IllegalArgumentException("too short")
        val ct = combined.copyOfRange(12, combined.size)
        return ByteArray(ct.size) { i -> (ct[i].toInt() xor key[i % key.size].toInt()).toByte() }
    }
}

class FailingCryptoBackend(private val msg: String = "keystore unavailable") : SecretCryptoBackend {
    override fun encrypt(plaintext: ByteArray): ByteArray = throw IllegalStateException(msg)
    override fun decrypt(combined: ByteArray): ByteArray = throw IllegalStateException(msg)
}
