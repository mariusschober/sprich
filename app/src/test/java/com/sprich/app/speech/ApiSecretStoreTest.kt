package com.sprich.app.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.storage.ApiSecretStore
import com.sprich.app.storage.FakeSecretCryptoBackend
import com.sprich.app.storage.FailingCryptoBackend
import com.sprich.app.storage.PermanentlyInvalidatedCryptoBackend
import com.sprich.app.storage.SecretStoreResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiSecretStoreTest {

    @Test
    fun saveLoadRemove() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = ApiSecretStore(ctx, FakeSecretCryptoBackend())
        store.clearAll()
        val res = store.saveSecret("test_ref", "my-secret-key-123")
        assertTrue(res is SecretStoreResult.Success)
        assertTrue(store.hasSecret("test_ref"))
        val loaded = store.loadSecret("test_ref")
        assertEquals("my-secret-key-123", loaded)
        store.removeSecret("test_ref")
        assertFalse(store.hasSecret("test_ref"))
        assertNull(store.loadSecret("test_ref"))
    }

    @Test
    fun notInDataStore() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = ApiSecretStore(ctx, FakeSecretCryptoBackend())
        store.clearAll()
        val res = store.saveSecret("stt_default", "secret123")
        assertTrue(res is SecretStoreResult.Success)
        assertTrue(store.hasSecret("stt_default"))
        val dir = try { ctx.noBackupFilesDir } catch (_: Exception) { ctx.filesDir }
        val secretFile = java.io.File(dir, "api_secrets/stt_default.enc")
        assertTrue(secretFile.exists())
        val content = secretFile.readText()
        assertFalse(content.contains("secret123")) // encrypted, not plaintext
    }

    @Test
    fun clearAllDeletes() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = ApiSecretStore(ctx, FakeSecretCryptoBackend())
        store.saveSecret("a", "key1")
        store.saveSecret("b", "key2")
        assertTrue(store.hasSecret("a"))
        store.clearAll()
        assertFalse(store.hasSecret("a"))
        assertFalse(store.hasSecret("b"))
    }

    @Test
    fun keystoreFailureFailClosed() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = ApiSecretStore(ctx, FailingCryptoBackend("keystore unavailable"))
        store.clearAll()
        val res = store.saveSecret("fail_ref", "my-secret")
        assertTrue(res is SecretStoreResult.Failure)
        // No recoverable file should remain
        val dir = try { ctx.noBackupFilesDir } catch (_: Exception) { ctx.filesDir }
        val f = java.io.File(dir, "api_secrets/fail_ref.enc")
        if (f.exists()) {
            val content = f.readText()
            assertFalse(content.contains("my-secret"))
            // Even if file exists, hasSecret must be false (not decryptable)
            assertFalse(store.hasSecret("fail_ref"))
            assertNull(store.loadSecret("fail_ref"))
        } else {
            assertFalse(store.hasSecret("fail_ref"))
            assertNull(store.loadSecret("fail_ref"))
        }
    }

    @Test
    fun transientDecryptFailureIsUnavailableButPreserved() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val good = ApiSecretStore(ctx, FakeSecretCryptoBackend())
        good.clearAll()
        good.saveSecret("k", "value")
        assertTrue(good.hasSecret("k"))
        // A generic Keystore/IO failure may be transient. Never delete the only copy of the key.
        val bad = ApiSecretStore(ctx, FailingCryptoBackend("temporarily unavailable"))
        assertFalse(bad.hasSecret("k"))
        assertEquals(ApiSecretStore.SecretHealth.UNAVAILABLE, bad.secretHealth("k"))
        val file = java.io.File(ctx.noBackupFilesDir, "api_secrets/k.enc")
        assertTrue(file.exists())
        // A later successful backend can still read it.
        assertEquals("value", good.loadSecret("k"))
    }

    @Test
    fun permanentlyInvalidatedKeyIsRemovedSoTheUiCanAskForReentry() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val good = ApiSecretStore(ctx, FakeSecretCryptoBackend())
        good.clearAll()
        good.saveSecret("k", "value")
        val invalidated = ApiSecretStore(ctx, PermanentlyInvalidatedCryptoBackend())
        assertEquals(ApiSecretStore.SecretHealth.MISSING, invalidated.secretHealth("k"))
        assertFalse(java.io.File(ctx.noBackupFilesDir, "api_secrets/k.enc").exists())
    }
    @Test fun providerAndEndpointChangesCannotReuseCredentials() = kotlinx.coroutines.runBlocking {
        val store = ApiSecretStore(ApplicationProvider.getApplicationContext<Context>(), FakeSecretCryptoBackend())
        store.clearAll()
        val ref = store.saveBoundSecret("custom", "https://one.example/v1", "bound-key")!!
        assertEquals("bound-key", store.loadBoundSecret(ref, "custom", "https://one.example/v1"))
        assertNull(store.loadBoundSecret(ref, "gemini", "https://one.example/v1"))
        assertNull(store.loadBoundSecret(ref, "custom", "https://two.example/v1"))
        assertNull(store.loadBoundSecret(ref, "custom", "https://one.example/v2"))
        store.saveSecret("stt_default", "legacy")
        assertNull(store.loadBoundSecret("stt_default", "custom", "https://one.example/v1"))
    }
    @Test fun credentialRevisionDoesNotChangeAfterAnotherSave() = kotlinx.coroutines.runBlocking {
        val store = ApiSecretStore(ApplicationProvider.getApplicationContext<Context>(), FakeSecretCryptoBackend())
        val first = store.saveBoundSecret("custom", "https://one.example/v1", "first")!!
        val second = store.saveBoundSecret("custom", "https://one.example/v1", "second")!!
        assertNotEquals(first, second)
        assertEquals("first", store.loadBoundSecret(first, "custom", "https://one.example/v1"))
        store.clearAll()
        assertNull(store.loadBoundSecret(first, "custom", "https://one.example/v1"))
        assertNull(store.loadBoundSecret(second, "custom", "https://one.example/v1"))
    }
    @Test fun encryptionFailurePreservesPreviouslySavedKey() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val good = ApiSecretStore(ctx, FakeSecretCryptoBackend())
        good.saveSecret("existing", "original")
        val broken = ApiSecretStore(ctx, FailingCryptoBackend("unavailable"))
        assertTrue(broken.saveSecret("existing", "replacement") is SecretStoreResult.Failure)
        assertEquals("original", good.loadSecret("existing"))
    }
    @Test fun oversizedAtomicBackupIsRejectedAndRemoved() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = ApiSecretStore(ctx, FakeSecretCryptoBackend())
        store.clearAll()
        val file = java.io.File(ctx.noBackupFilesDir, "api_secrets/oversized.enc")
        file.parentFile!!.mkdirs()
        val backup = java.io.File(file.path + ".bak")
        backup.writeBytes(ByteArray(65_536))
        assertNull(store.loadSecret("oversized"))
        assertFalse(file.exists())
        assertFalse(backup.exists())
    }

}
