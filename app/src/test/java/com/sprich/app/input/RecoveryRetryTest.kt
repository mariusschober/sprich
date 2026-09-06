package com.sprich.app.input.ime

import com.sprich.app.input.lifecycle.UtteranceToken
import com.sprich.app.speech.ResolvedUtteranceLanguage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U03 — retry is same-field only, never blank/password/connectionless.
 * Pure JVM: no Android runtime, no Robolectric.
 */
class RecoveryRetryTest {

    private fun token(field: String? = "field-1") = UtteranceToken(
        sessionId = 1L,
        generation = 2L,
        utteranceId = 3L,
        fieldId = field,
        fieldGeneration = 4L,
        capturedIc = null,
    )

    private fun record(field: String? = "field-1", text: String = "hello world") =
        FailedCommit(token(field), text, ResolvedUtteranceLanguage.Unknown)

    private fun eligible(
        record: FailedCommit? = record(),
        field: String? = "field-1",
        ic: Boolean = true,
        controller: Boolean = true,
        password: Boolean = false,
    ) = retryEligible(record, field, ic, controller, password)

    @Test fun happyPathEligible() {
        assertTrue(eligible())
    }

    @Test fun nullOrBlankRecordRefused() {
        assertFalse(eligible(record = null))
        assertFalse(eligible(record = record(text = "   ")))
    }

    @Test fun passwordFieldRefused() {
        assertFalse(eligible(password = true))
    }

    @Test fun otherOrMissingFieldRefused() {
        assertFalse(eligible(field = "field-2"))
        assertFalse(eligible(field = null))
        assertFalse(eligible(record = record(field = null)))
    }

    @Test fun deadConnectionOrControllerRefused() {
        assertFalse(eligible(ic = false))
        assertFalse(eligible(controller = false))
    }
}
