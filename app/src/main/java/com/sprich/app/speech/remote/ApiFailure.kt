package com.sprich.app.speech.remote

/**
 * Typed failure model — drives fallback, Settings Test result, diagnostics. No raw provider body in normal logs.
 */
sealed interface ApiFailure {
    data object Cancelled : ApiFailure
    data object Offline : ApiFailure
    data object Timeout : ApiFailure
    data object Authentication : ApiFailure
    data object RateLimited : ApiFailure
    data object ModelUnavailable : ApiFailure
    data object ProviderUnavailable : ApiFailure
    data object InvalidResponse : ApiFailure
    data class Http(val status: Int, val message: String = "") : ApiFailure
    data class Network(val cause: Throwable) : ApiFailure

    fun toDisplay(): String = when (this) {
        is Cancelled -> "Cancelled"
        is Offline -> "Offline"
        is Timeout -> "Timeout"
        is Authentication -> "Authentication failed (401/403)"
        is RateLimited -> "Rate limited (429)"
        is ModelUnavailable -> "Model unavailable (404)"
        is ProviderUnavailable -> "Provider unavailable (5xx)"
        is InvalidResponse -> "Invalid response"
        is Http -> "HTTP $status"
        is Network -> "Network error"
    }

    companion object {
        fun fromHttpCode(code: Int, bodySnippet: String = ""): ApiFailure = when (code) {
            401, 403 -> Authentication
            404 -> ModelUnavailable
            429 -> RateLimited
            in 500..599 -> ProviderUnavailable
            else -> Http(code, bodySnippet)
        }
        fun fromException(e: Throwable): ApiFailure {
            val msg = e.message?.lowercase().orEmpty()
            return when {
                e is java.net.UnknownHostException || msg.contains("unable to resolve host") -> Offline
                e is java.net.SocketTimeoutException || msg.contains("timeout") -> Timeout
                e is kotlinx.coroutines.CancellationException -> Cancelled
                else -> Network(e)
            }
        }
    }
}
