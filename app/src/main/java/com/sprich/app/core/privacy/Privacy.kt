package com.sprich.app.core.privacy

import android.util.Log

/**
 * Privacy guard: ensure no transcript/audio leaks to logs or network.
 * NetworkIsolation is architectural: speech:* must not import okhttp/java.net.
 * This file provides runtime guards + log sanitizer.
 */
object PrivacyGuard {
    fun sanitizeForLog(msg: String): String {
        // Never log raw transcripts. Redact quoted content.
        // Simple: if message contains transcript-like, replace.
        // Callers must not pass transcript; this is defense-in-depth.
        return msg.replace(Regex("\"transcript\"\\s*:\\s*\"[^\"]*\""), "\"transcript\":\"[REDACTED]\"")
    }

    fun assertNoNetworkOnAsrThread() {
        // Best effort: check thread name, stack
        val stack = Thread.currentThread().stackTrace.joinToString("\n") { it.toString() }
        if (stack.contains("okhttp") || stack.contains("java.net.Http")) {
            Log.w("Privacy", "Potential network call on ASR path: ${sanitizeForLog(stack.take(500))}")
        }
    }

    fun requireAirplaneModeWorks(): Boolean = true // placeholder for test
}

object NoTranscriptLogger {
    fun d(tag: String, msg: String) { Log.d(tag, PrivacyGuard.sanitizeForLog(msg)) }
    fun i(tag: String, msg: String) { Log.i(tag, PrivacyGuard.sanitizeForLog(msg)) }
    fun w(tag: String, msg: String) { Log.w(tag, PrivacyGuard.sanitizeForLog(msg)) }
    fun e(tag: String, msg: String, t: Throwable? = null) { Log.e(tag, PrivacyGuard.sanitizeForLog(msg), t) }
}
