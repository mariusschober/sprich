package com.sprich.app.speech

import com.sprich.app.diagnostics.UtteranceOutcome
import com.sprich.app.speech.remote.ApiFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P07 — per-utterance remote-failure mapping is exhaustive and stable.
 * Pure JVM: no Android runtime, no Robolectric.
 */
class RemoteFailureOutcomeTest {

    @Test fun transportProblemsMapToTransportFailure() {
        val transport = listOf(
            ApiFailure.Offline,
            ApiFailure.Network(RuntimeException("boom")),
            ApiFailure.Timeout,
            ApiFailure.ProviderUnavailable,
            ApiFailure.ModelUnavailable,
            ApiFailure.RateLimited,
            ApiFailure.Authentication,
            ApiFailure.Http(500),
            ApiFailure.Http(429),
        )
        for (failure in transport) {
            assertEquals(failure.toString(), UtteranceOutcome.TRANSPORT_FAILURE, remoteFailureOutcome(failure))
        }
    }

    @Test fun unusableProviderOutputMapsToMalformedResponse() {
        assertEquals(UtteranceOutcome.MALFORMED_RESPONSE, remoteFailureOutcome(ApiFailure.InvalidResponse))
        assertEquals(UtteranceOutcome.MALFORMED_RESPONSE, remoteFailureOutcome(ApiFailure.OutputRejected))
        assertEquals(UtteranceOutcome.MALFORMED_RESPONSE, remoteFailureOutcome(ApiFailure.OutputLimit))
    }

    @Test fun explicitCancellationMapsToCancelled() {
        assertEquals(UtteranceOutcome.CANCELLED, remoteFailureOutcome(ApiFailure.Cancelled))
    }
}
