package com.sprich.app.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A shared legacy key has no reliable provider/endpoint binding. Require explicit re-entry. */
object LegacyApiCredentialMigrator {
    suspend fun migrateIfNeeded(prefs: Preferences, secretStore: ApiSecretStore) = withContext(Dispatchers.IO) {
        // Idempotent and cheap after the first successful upgrade. Marking last means an
        // interrupted cleanup is retried instead of leaving plaintext behind.
        if (prefs.legacyCredentialCleanupDone()) return@withContext
        prefs.clearLegacyApiKeys()
        secretStore.removeSecret("stt_default")
        secretStore.removeSecret("refine_default")
        prefs.markLegacyCredentialCleanupDone()
    }
}
