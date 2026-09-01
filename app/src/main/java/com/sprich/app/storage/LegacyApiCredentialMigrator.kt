package com.sprich.app.storage

import android.util.Log
import kotlinx.coroutines.flow.first

/**
 * One explicit migration path for legacy plaintext keys stored in DataStore.
 *
 * Invariants:
 * - Legacy plaintext must not remain as indefinitely supported credential source.
 * - Migration is FAIL CLOSED: if secure save fails, do NOT continue using plaintext.
 * - After migration, plaintext DataStore keys are deleted.
 */
object LegacyApiCredentialMigrator {

    suspend fun migrateIfNeeded(prefs: Preferences, secretStore: ApiSecretStore) {
        // STT
        migrateSingle(
            legacyLoad = { prefs.sttApiKey.first() },
            legacyClear = { prefs.setSttApiKey("") },
            secretRef = "stt_default",
            secretStore = secretStore,
        )
        // AI / refinement
        migrateSingle(
            legacyLoad = { prefs.aiApiKey.first() },
            legacyClear = { prefs.setAiApiKey("") },
            secretRef = "refine_default",
            secretStore = secretStore,
        )
    }

    private suspend fun migrateSingle(
        legacyLoad: suspend () -> String,
        legacyClear: suspend () -> Unit,
        secretRef: String,
        secretStore: ApiSecretStore,
    ) {
        val legacy = try { legacyLoad().trim() } catch (_: Exception) { "" }
        val hasSecure = try { secretStore.hasSecret(secretRef) } catch (_: Exception) { false }

        if (hasSecure) {
            // Secure already present -> delete legacy immediately
            if (legacy.isNotBlank()) {
                try {
                    legacyClear()
                    Log.i("LegacyMigrator", "deleted legacy plaintext for $secretRef (secure already present)")
                } catch (e: Exception) {
                    Log.w("LegacyMigrator", "failed to delete legacy $secretRef", e)
                }
            }
            return
        }

        if (legacy.isBlank()) return

        // Legacy exists and secure empty -> attempt secure save
        val result = try {
            secretStore.saveSecret(secretRef, legacy)
        } catch (e: Exception) {
            SecretStoreResult.Failure(e.message ?: "migrate failed")
        }

        when (result) {
            is SecretStoreResult.Success -> {
                try {
                    legacyClear()
                    Log.i("LegacyMigrator", "migrated legacy $secretRef to secure store and cleared plaintext")
                } catch (e: Exception) {
                    Log.w("LegacyMigrator", "migrate succeeded but clear failed $secretRef", e)
                    // Even if clear fails, we have secure copy; retry clear next startup
                }
            }
            is SecretStoreResult.Failure -> {
                // FAIL CLOSED: delete plaintext even though migration failed, do NOT keep using it
                try {
                    legacyClear()
                    Log.w("LegacyMigrator", "migration failed for $secretRef (${result.reason}), deleted plaintext — user must re-enter")
                } catch (e: Exception) {
                    Log.w("LegacyMigrator", "failed to delete legacy after migration failure $secretRef", e)
                }
            }
        }
    }
}
