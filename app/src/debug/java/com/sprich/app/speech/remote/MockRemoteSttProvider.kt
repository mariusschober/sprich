package com.sprich.app.speech.remote

import com.sprich.app.speech.ResolvedUtteranceLanguage
import com.sprich.app.speech.TranscriptionSourceId
import kotlinx.coroutines.delay

/**
 * Deterministic mock provider for Most tests — no network. Configurable per-test behavior.
 */
class MockRemoteSttProvider(
    var nextResult: Result<RemoteSttResult> = Result.success(
        RemoteSttResult("mock transcript", ResolvedUtteranceLanguage.Unknown, TranscriptionSourceId.API_MOCK)
    ),
    var delayMs: Long = 10,
    override val capabilities: RemoteSttCapabilities = RemoteSttCapabilities(streaming = false, automaticLanguage = true, partialResults = false),
    private val idOverride: String = "mock",
) : RemoteSttProvider {
    override val id: String = idOverride
    var transcribeCalls: Int = 0
        private set
    var lastRequest: RemoteSttRequest? = null
        private set

    override suspend fun transcribe(request: RemoteSttRequest): RemoteSttResult {
        transcribeCalls++
        lastRequest = request
        if (delayMs > 0) delay(delayMs)
        return nextResult.getOrElse { throw it }
    }

    fun reset() {
        transcribeCalls = 0
        lastRequest = null
    }
}

class FailingMockProvider(
    private val failure: ApiFailure,
) : RemoteSttProvider {
    override val id = "mock-fail"
    override val capabilities = RemoteSttCapabilities()
    var calls = 0
    override suspend fun transcribe(request: RemoteSttRequest): RemoteSttResult {
        calls++
        throw RemoteSttException(failure, failure.toDisplay())
    }
}
