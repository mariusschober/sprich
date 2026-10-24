package com.sprich.app.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.api.ApiChoice
import com.sprich.app.api.ApiHttp
import com.sprich.app.api.ApiUse
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ApiSecretStorePruneTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun freshStore(crypto: SecretCryptoBackend = FakeSecretCryptoBackend()) =
        ApiSecretStore(context, crypto).also { it.clearAll() }

    private fun secretFile(id: String) = File(context.noBackupFilesDir, "api_secrets/$id.enc")
    private fun markerFile(id: String) = File(context.noBackupFilesDir, "api_secrets/$id.unreadable")

    @Test fun unreferencedUnreadableBoundRefIsDeletedAfterRepeatedPruneFailure() = runBlocking {
        val prefs = Preferences(context).also { it.clearAll() }
        val good = freshStore()
        val endpoint = "https://api.example/v1"
        val oldRef = good.saveBoundSecret("custom", endpoint, "old-key")!!
        val oldBytes = secretFile(oldRef).readBytes()
        val oldChoice = ApiChoice(ApiUse.VOICE, "custom", endpoint, "model", oldRef)
        assertTrue(prefs.commitApiCheck(oldChoice, prefs.apiChoiceForCheck(ApiUse.VOICE), ApiHttp.currentEpoch))

        val unreadable = ApiSecretStore(context, AeadBadTagCryptoBackend(oldBytes))
        assertEquals(ApiSecretStore.SecretHealth.UNAVAILABLE, unreadable.secretHealth(oldRef))
        val newRef = unreadable.saveBoundSecret("custom", endpoint, "new-key")!!
        val newChoice = oldChoice.copy(credentialRef = newRef)
        assertTrue(prefs.commitApiCheck(newChoice, prefs.apiChoiceForCheck(ApiUse.VOICE), ApiHttp.currentEpoch))

        val latest = prefs.runtimeConfigSnapshot.first()
        assertEquals(newRef, latest.sttCredentialRef)
        val latestRefs = setOf(latest.sttCredentialRef, latest.refinementCredentialRef)

        unreadable.pruneUnreferenced(latestRefs)
        assertTrue(secretFile(oldRef).exists())
        assertTrue(markerFile(oldRef).exists())

        unreadable.pruneUnreferenced(latestRefs)
        assertFalse(secretFile(oldRef).exists())
        assertFalse(markerFile(oldRef).exists())
        assertTrue(secretFile(newRef).exists())
        assertEquals("new-key", unreadable.loadBoundSecret(newRef, "custom", endpoint))
    }

    @Test fun singleTransientPruneFailureKeepsCiphertextAndRecordsMarker() = runBlocking {
        val good = freshStore()
        val endpoint = "https://api.example/v1"
        val ref = good.saveBoundSecret("custom", endpoint, "recoverable-key")!!
        val unreadable = ApiSecretStore(context, AeadBadTagCryptoBackend(secretFile(ref).readBytes()))

        unreadable.pruneUnreferenced(emptySet())

        assertTrue(secretFile(ref).exists())
        val marker = markerFile(ref)
        assertTrue(marker.exists())
        val recorded = marker.readText()
        assertFalse(recorded.contains("recoverable-key"))
        assertTrue(recorded.toLong() > 0)
        assertEquals("recoverable-key", good.loadBoundSecret(ref, "custom", endpoint))
    }

    @Test fun repeatedTransientPruneFailureDeletesCiphertextBackupAndMarker() = runBlocking {
        val good = freshStore()
        val endpoint = "https://api.example/v1"
        val ref = good.saveBoundSecret("custom", endpoint, "unrecoverable-key")!!
        val file = secretFile(ref)
        val backup = File(file.path + ".bak")
        val unreadable = ApiSecretStore(context, AeadBadTagCryptoBackend(file.readBytes()))

        unreadable.pruneUnreferenced(emptySet())
        assertTrue(file.exists())
        assertTrue(markerFile(ref).exists())

        // AtomicFile adopts an interrupted-write backup on read, so recreate one before the retry.
        backup.writeBytes(file.readBytes())
        unreadable.pruneUnreferenced(emptySet())
        assertFalse(file.exists())
        assertFalse(backup.exists())
        assertFalse(markerFile(ref).exists())
        assertEquals(ApiSecretStore.SecretHealth.MISSING, unreadable.secretHealth(ref))
    }

    @Test fun successfulReadAfterTransientFailureClearsMarkerAndPreventsDeletion() = runBlocking {
        val good = freshStore()
        val endpoint = "https://api.example/v1"
        val ref = good.saveBoundSecret("custom", endpoint, "recoverable-key")!!
        val unreadable = ApiSecretStore(context, AeadBadTagCryptoBackend(secretFile(ref).readBytes()))

        unreadable.pruneUnreferenced(emptySet())
        assertTrue(markerFile(ref).exists())

        assertEquals("recoverable-key", good.loadBoundSecret(ref, "custom", endpoint))
        assertFalse(markerFile(ref).exists())

        unreadable.pruneUnreferenced(emptySet())
        assertTrue(secretFile(ref).exists())
        assertTrue(markerFile(ref).exists())
    }

    @Test fun permanentlyInvalidatedUnreferencedBoundRefIsDeletedOnFirstPrune() = runBlocking {
        val good = freshStore()
        val ref = good.saveBoundSecret("custom", "https://api.example/v1", "invalidated-key")!!
        val invalidated = ApiSecretStore(context, PermanentlyInvalidatedCryptoBackend())

        invalidated.pruneUnreferenced(emptySet())

        assertFalse(secretFile(ref).exists())
        assertFalse(markerFile(ref).exists())
        assertEquals(ApiSecretStore.SecretHealth.MISSING, invalidated.secretHealth(ref))
    }

    @Test fun unreadableButStillReferencedBoundRefIsKept() = runBlocking {
        val good = freshStore()
        val ref = good.saveBoundSecret("custom", "https://api.example/v1", "working-key")!!
        val unreadable = ApiSecretStore(context, AeadBadTagCryptoBackend(secretFile(ref).readBytes()))

        unreadable.pruneUnreferenced(setOf(ref))
        unreadable.pruneUnreferenced(setOf(ref))

        assertTrue(secretFile(ref).exists())
        assertFalse(markerFile(ref).exists())
        assertEquals(ApiSecretStore.SecretHealth.UNAVAILABLE, unreadable.secretHealth(ref))
    }

    @Test fun decryptableUnreferencedBoundRefIsKept() = runBlocking {
        val store = freshStore()
        val ref = store.saveBoundSecret("custom", "https://api.example/v1", "frozen-key")!!

        store.pruneUnreferenced(emptySet())

        assertTrue(secretFile(ref).exists())
        assertEquals(ApiSecretStore.SecretHealth.AVAILABLE, store.secretHealth(ref))
        assertEquals("frozen-key", store.loadBoundSecret(ref, "custom", "https://api.example/v1"))
    }

    @Test fun unreadableLegacyNonBoundIdIsNeverTouched() {
        val store = freshStore()
        store.saveSecret("stt_default", "legacy-key")
        val unreadable = ApiSecretStore(context, AeadBadTagCryptoBackend(secretFile("stt_default").readBytes()))

        unreadable.pruneUnreferenced(emptySet())
        unreadable.pruneUnreferenced(emptySet())

        assertTrue(secretFile("stt_default").exists())
        assertFalse(markerFile("stt_default").exists())
        assertEquals(ApiSecretStore.SecretHealth.UNAVAILABLE, unreadable.secretHealth("stt_default"))
    }

    @Test fun transientAeadBadTagReadIsUnavailableAndPreservesTheFile() = runBlocking {
        val good = freshStore()
        val ref = good.saveBoundSecret("custom", "https://api.example/v1", "recoverable-key")!!
        val file = secretFile(ref)
        val unreadable = ApiSecretStore(context, AeadBadTagCryptoBackend(file.readBytes()))

        assertNull(unreadable.loadBoundSecret(ref, "custom", "https://api.example/v1"))
        assertEquals(ApiSecretStore.SecretHealth.UNAVAILABLE, unreadable.secretHealth(ref))
        assertTrue(file.exists())

        // A transient failure must not destroy the ciphertext for a later successful retry.
        assertEquals("recoverable-key", good.loadBoundSecret(ref, "custom", "https://api.example/v1"))
    }

    @Test fun removeSecretAndClearAllRemoveUnreadableMarkers() = runBlocking {
        val good = freshStore()
        val endpoint = "https://api.example/v1"
        val removedRef = good.saveBoundSecret("custom", endpoint, "removed-key")!!
        val unreadable = ApiSecretStore(context, AeadBadTagCryptoBackend(secretFile(removedRef).readBytes()))

        unreadable.pruneUnreferenced(emptySet())
        assertTrue(markerFile(removedRef).exists())
        unreadable.removeSecret(removedRef)
        assertFalse(secretFile(removedRef).exists())
        assertFalse(markerFile(removedRef).exists())

        val clearedRef = good.saveBoundSecret("custom", endpoint, "cleared-key")!!
        val unreadableAgain = ApiSecretStore(context, AeadBadTagCryptoBackend(secretFile(clearedRef).readBytes()))
        unreadableAgain.pruneUnreferenced(emptySet())
        assertTrue(markerFile(clearedRef).exists())
        unreadableAgain.clearAll()
        assertFalse(secretFile(clearedRef).exists())
        assertFalse(markerFile(clearedRef).exists())
    }
}
