package com.sprich.app

import com.sprich.app.storage.ApiSecretStore
import com.sprich.app.ui.settings.savedKeyVerified
import com.sprich.app.ui.settings.shouldProtectApiWindow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyHealthTest {
    @Test fun availabilityIsRequiredInAdditionToPersistedProof() {
        assertTrue(savedKeyVerified(true, true, ApiSecretStore.SecretHealth.AVAILABLE))
        assertFalse(savedKeyVerified(true, true, ApiSecretStore.SecretHealth.UNAVAILABLE))
        assertFalse(savedKeyVerified(true, true, ApiSecretStore.SecretHealth.MISSING))
    }

    @Test fun anUncheckedKeyDoesNotShowAsVerifiedYet() {
        assertFalse(savedKeyVerified(true, true, null))
    }

    @Test fun changedOrUncheckedConfigurationCannotEnable() {
        assertFalse(savedKeyVerified(false, true, ApiSecretStore.SecretHealth.AVAILABLE))
        assertFalse(savedKeyVerified(true, false, ApiSecretStore.SecretHealth.AVAILABLE))
    }

    @Test fun screenshotsStayBlockedWhileKeyOrTranscriptIsVisible() {
        assertFalse(shouldProtectApiWindow(false, false, false, false, false, false))
        assertTrue(shouldProtectApiWindow(true, false, false, false, false, false))
        assertTrue(shouldProtectApiWindow(false, true, false, false, false, false))
        assertTrue(shouldProtectApiWindow(false, false, true, false, false, false))
        assertTrue(shouldProtectApiWindow(false, false, false, true, true, false))
        // The key is cleared after a successful check; the transcript still needs protection.
        assertTrue(shouldProtectApiWindow(false, false, false, false, false, true))
    }
}
