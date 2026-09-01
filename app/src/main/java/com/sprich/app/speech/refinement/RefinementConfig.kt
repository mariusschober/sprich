package com.sprich.app.speech.refinement

data class RefinementConfig(
    val providerId: String,
    val endpoint: String,
    val model: String,
    val mode: RefinementMode,
    val deadlineMs: Long,
    val credentialRef: String,
)

data class RefinementRequest(
    val text: String,
    val language: String, // BCP-47 or "auto"
    val mode: RefinementMode,
    val protectedTerms: List<String> = emptyList(),
)

data class RefinementProviderResult(
    val text: String,
    val latencyMs: Long = 0,
)

data class RefinementCapabilities(
    val structuredOutput: Boolean = false,
    val streaming: Boolean = false,
)
