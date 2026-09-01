package com.sprich.app.speech.refinement

data class RefinementConfig(
    val providerId: String,
    val endpoint: String,
    val model: String,
    val mode: RefinementMode,
    val deadlineMs: Long,
    val credentialRef: String,
) {
    override fun toString(): String {
        val safeEndpoint = try {
            val uri = java.net.URI(endpoint)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: "[host]"
            "$scheme://$host/[REDACTED_PATH]"
        } catch (_: Exception) { "[REDACTED_ENDPOINT]" }
        return "RefinementConfig(providerId=$providerId, endpoint=$safeEndpoint, model=$model, mode=$mode, deadlineMs=$deadlineMs, credentialRef=[REDACTED])"
    }
}

data class RefinementRequest(
    val text: String,
    val language: String, // BCP-47 or "auto"
    val mode: RefinementMode,
    val protectedTerms: List<String> = emptyList(),
) {
    override fun toString(): String {
        return "RefinementRequest(textLen=${text.length}, language=$language, mode=$mode, protectedTerms=${protectedTerms.size} terms [REDACTED])"
    }
}

data class RefinementProviderResult(
    val text: String,
    val latencyMs: Long = 0,
)

data class RefinementCapabilities(
    val structuredOutput: Boolean = false,
    val streaming: Boolean = false,
)
