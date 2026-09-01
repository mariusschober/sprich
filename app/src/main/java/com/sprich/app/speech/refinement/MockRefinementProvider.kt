package com.sprich.app.speech.refinement

import kotlinx.coroutines.delay

class MockRefinementProvider(
    var next: Result<RefinementProviderResult> = Result.success(RefinementProviderResult("mock refined")),
    var delayMs: Long = 10,
) : TranscriptRefinementProvider {
    override val id = "mock-refine"
    override val capabilities = RefinementCapabilities()
    var calls = 0
    var lastRequest: RefinementRequest? = null
    override suspend fun refine(request: RefinementRequest): RefinementProviderResult {
        calls++
        lastRequest = request
        if (delayMs > 0) delay(delayMs)
        return next.getOrElse { throw it }
    }
}
