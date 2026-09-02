package com.sprich.app.core.security

import com.sprich.app.BuildConfig

/**
 * Centralized endpoint validation — production HTTPS only, no userinfo, valid host.
 * Debug allows localhost/127.0.0.1/10.0.2.2 HTTP only.
 * Delegates to single source to avoid duplication drift across IME/Settings/STT/Refinement.
 */
object EndpointValidator {
    fun isValidHttpsUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val uri = java.net.URI(url.trim())
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "https") {
                if (scheme == "http") {
                    val host = uri.host?.lowercase() ?: return false
                    val isDebug = try { BuildConfig.DEBUG } catch (_: Exception) { false }
                    if (!isDebug) return false
                    if (host != "localhost" && host != "127.0.0.1" && host != "10.0.2.2") return false
                } else return false
            }
            val host = uri.host ?: return false
            if (host.isBlank()) return false
            if (uri.userInfo != null) return false
            true
        } catch (_: Exception) { false }
    }
}
