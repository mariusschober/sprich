package com.sprich.app

import com.sprich.app.input.ime.StartFailureKind
import com.sprich.app.input.ime.StartFailureText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartFailureTest {
    @Test fun missingRemoteConfigurationIsAnApiConfigurationFailure() {
        assertEquals(StartFailureKind.API_CONFIG, StartFailureText.remote(remoteConfigured = false, credentialPresent = false))
    }

    @Test fun missingBoundCredentialIsAnApiKeyFailure() {
        assertEquals(StartFailureKind.API_KEY, StartFailureText.remote(remoteConfigured = true, credentialPresent = false))
    }

    @Test fun configuredRemoteWithCredentialDoesNotFail() {
        assertNull(StartFailureText.remote(remoteConfigured = true, credentialPresent = true))
    }

    @Test fun apiFailuresNeverUseTheSpeechDownloadCopy() {
        val local = StartFailureText.copy(StartFailureKind.LOCAL_MODEL)
        for (kind in listOf(StartFailureKind.API_CONFIG, StartFailureKind.API_KEY)) {
            val copy = StartFailureText.copy(kind)
            assertTrue(copy.status != local.status)
            assertEquals(R.string.api_check_key, copy.hint)
        }
    }
}
