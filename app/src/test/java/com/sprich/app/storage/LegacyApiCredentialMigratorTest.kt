package com.sprich.app.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@Suppress("DEPRECATION")
class LegacyApiCredentialMigratorTest {
    @Test fun startupCleanupRemovesPlaintextAndLegacySecretsButKeepsBoundKeys() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Preferences(context)
        val store = ApiSecretStore(context, FakeSecretCryptoBackend())
        prefs.clearAll()
        store.clearAll()
        prefs.setSttApiKey("legacy-voice-secret")
        prefs.setAiApiKey("legacy-writing-secret")
        store.saveSecret("stt_default", "legacy-voice-secret")
        store.saveSecret("refine_default", "legacy-writing-secret")
        val bound = store.saveBoundSecret("custom", "https://api.example/v1", "bound-secret")!!

        LegacyApiCredentialMigrator.migrateIfNeeded(prefs, store)

        assertEquals("", prefs.sttApiKey.first())
        assertEquals("", prefs.aiApiKey.first())
        assertNull(store.loadSecret("stt_default"))
        assertNull(store.loadSecret("refine_default"))
        assertEquals("bound-secret", store.loadBoundSecret(bound, "custom", "https://api.example/v1"))
        assertTrue(prefs.legacyCredentialCleanupDone())

        LegacyApiCredentialMigrator.migrateIfNeeded(prefs, store)
        assertEquals("bound-secret", store.loadBoundSecret(bound, "custom", "https://api.example/v1"))
        assertFalse(store.hasSecret("stt_default"))
    }
}
