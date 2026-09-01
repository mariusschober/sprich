package com.sprich.app.speech.refinement

interface TranscriptRefinementProvider {
    val id: String
    val capabilities: RefinementCapabilities
    suspend fun refine(request: RefinementRequest): RefinementProviderResult
}
