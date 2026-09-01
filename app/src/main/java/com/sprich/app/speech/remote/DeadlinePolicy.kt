package com.sprich.app.speech.remote

/**
 * Interactive deadlines — separate product deadlines from socket safety timeouts.
 * All values test-configurable via constructor.
 */
data class DeadlinePolicy(
    val nonStreamingEndpointToResultMs: Long = 3500L, // 3-4s max
    val streamingConnectionMs: Long = 3000L,
    val streamingEndpointToFinalMs: Long = 2500L,
    val refinementMs: Long = 1000L, // 800-1200 hard deadline
    val socketConnectMs: Long = 10000L,
    val socketReadMs: Long = 30000L,
    val socketWriteMs: Long = 30000L,
) {
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
        )
    }
}
