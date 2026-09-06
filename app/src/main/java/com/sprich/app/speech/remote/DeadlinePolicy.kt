package com.sprich.app.speech.remote

/**
 * Interactive deadlines — separate product deadlines from socket safety timeouts.
 * All values test-configurable via constructor.
 *
 * Plan §6.3: a single absolute finalization budget runs from the actual
 * utterance endpoint and includes queue age. Provider connect/read limits stay
 * separate safety ceilings and must not reset the user budget at any stage.
 * Soft experience targets ([localSoftTargetMs]/[cloudSoftTargetMs]) never
 * discard a correct-but-late result; only [requiredRecognitionHardTimeoutMs]
 * fails required recognition. Optional cleanup gets only the remaining soft
 * budget ([cleanupBudgetMs]) — no retries, no fresh deadline per stage.
 */
data class DeadlinePolicy(
    val nonStreamingEndpointToResultMs: Long = 3500L, // 3-4s max
    val streamingConnectionMs: Long = 3000L,
    val streamingEndpointToFinalMs: Long = 2500L,
    val refinementMs: Long = 1000L, // 800-1200 hard deadline
    val socketConnectMs: Long = 10000L,
    val socketReadMs: Long = 30000L,
    val socketWriteMs: Long = 30000L,
    // Absolute finalization budget from utterance endpoint, queue age included.
    val absoluteFinalizationMs: Long = 8000L,
    // Hard resource/safety timeout for required recognition (overrun is counted,
    // authority invalidated — resources are never freed under native decode).
    val requiredRecognitionHardTimeoutMs: Long = 8000L,
    // Provisional soft experience objectives (not evidence, not quality gates).
    val localSoftTargetMs: Long = 700L, // warm local short-utterance p95
    val cloudSoftTargetMs: Long = 1000L, // cloud-assisted p95 on good network
) {
    /** Remaining absolute budget; negative means the utterance is over deadline. */
    fun remainingMs(endpointMonoMs: Long, nowMonoMs: Long): Long =
        absoluteFinalizationMs - (nowMonoMs - endpointMonoMs)

    fun isOverAbsoluteDeadline(endpointMonoMs: Long, nowMonoMs: Long): Boolean =
        remainingMs(endpointMonoMs, nowMonoMs) < 0

    fun isOverHardRecognitionTimeout(endpointMonoMs: Long, nowMonoMs: Long): Boolean =
        nowMonoMs - endpointMonoMs > requiredRecognitionHardTimeoutMs

    /**
     * Remaining budget for optional cleanup once a valid transcript exists at
     * [transcriptReadyMonoMs]: the soft target minus elapsed time since the
     * endpoint. Returns <= 0 when cleanup must be skipped (faithful text is
     * committed instead). Provider ceilings never extend this value.
     */
    fun cleanupBudgetMs(
        endpointMonoMs: Long,
        transcriptReadyMonoMs: Long,
        cloudAssisted: Boolean,
    ): Long {
        val soft = if (cloudAssisted) cloudSoftTargetMs else localSoftTargetMs
        return soft - (transcriptReadyMonoMs - endpointMonoMs)
    }

    companion object {
        val DEFAULT = DeadlinePolicy()
        // For benchmark/tests
        val TEST_SHORT = DeadlinePolicy(
            nonStreamingEndpointToResultMs = 1200L,
            streamingConnectionMs = 800L,
            streamingEndpointToFinalMs = 800L,
            refinementMs = 600L,
            socketConnectMs = 800L,
            socketReadMs = 1200L,
            socketWriteMs = 1200L,
            absoluteFinalizationMs = 2500L,
            requiredRecognitionHardTimeoutMs = 2500L,
            localSoftTargetMs = 400L,
            cloudSoftTargetMs = 600L,
        )
    }
}
