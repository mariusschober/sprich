package com.sprich.app.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.storage.ApiSecretStore
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
        val store = ApiSecretStore(ctx)
        store.clearAll()
        store.saveSecret("test_ref", "my-secret-key-123")
        assertTrue(store.hasSecret("test_ref"))
        val loaded = store.loadSecret("test_ref")
        assertEquals("my-secret-key-123", loaded)
        store.removeSecret("test_ref")
        assertFalse(store.hasSecret("test_ref"))
        assertNull(store.loadSecret("test_ref"))
    }

    @Test
    fun notInDataStore() {
        // Simulate that saving to ApiSecretStore does NOT write to DataStore
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = ApiSecretStore(ctx)
        store.clearAll()
        store.saveSecret("stt_default", "secret123")
        // DataStore should not contain plaintext
        // We check that ApiSecretStore file exists in noBackup, but DataStore not inspected here
        assertTrue(store.hasSecret("stt_default"))
        // File should be in noBackupFilesDir/api_secrets
        val dir = try { ctx.noBackupFilesDir } catch (_: Exception) { ctx.filesDir }
        val secretFile = java.io.File(dir, "api_secrets/stt_default.enc")
        assertTrue(secretFile.exists())
        val content = secretFile.readText()
        assertFalse(content.contains("secret123")) // encrypted, not plaintext
    }

    @Test
    fun clearAllDeletes() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = ApiSecretStore(ctx)
        store.saveSecret("a", "key1")
        store.saveSecret("b", "key2")
        assertTrue(store.hasSecret("a"))
        store.clearAll()
        assertFalse(store.hasSecret("a"))
        assertFalse(store.hasSecret("b"))
    }
}
