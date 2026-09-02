package com.sprich.app.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A shared legacy key has no reliable provider/endpoint binding. Require explicit re-entry. */
object LegacyApiCredentialMigrator {
    suspend fun migrateIfNeeded(prefs: Preferences, secretStore: ApiSecretStore) = withContext(Dispatchers.IO) {
        prefs.setSttApiKey("")
        prefs.setAiApiKey("")
        secretStore.removeSecret("stt_default")
        secretStore.removeSecret("refine_default")
    }
}
