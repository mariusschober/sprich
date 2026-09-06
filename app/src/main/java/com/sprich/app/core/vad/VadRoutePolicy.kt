package com.sprich.app.core.vad

/**
 * P02 (plan §5.2): neural speech presence usable in EVERY route, including cloud.
 *
 * JVM-pure (no Android/sherpa imports) so it compiles with the cached kotlinc
 * harness. Wiring the bundled Silero detector (SpeechPresence) into the cloud
 * route needs Gradle + device and is recorded BLOCKED in the child report.
 *
 * Signal split (kept separate, never conflated):
 * - [EndpointSignals.neuralPresence]: bundled-detector decision; owns onset and
 *   no-speech protection. Null = detector not ready this frame (degraded mode).
 * - [EndpointSignals.energySpeech]: [Vad] energy frame; bounded local endpoint
 *   safety only, never onset while the neural detector is reporting.
 * - [EndpointSignals.providerCompleted]: server turn completion; authoritative
 *   only when [EndpointSignals.providerEndpointAuthoritative] holds under that
 *   provider's contract, and never cuts active neural speech.
 *
 * `localAsrAttemptCount == 0` on successful cloud recognition MUST NOT gate VAD:
 * [evaluate] accepts the counter for telemetry threading but the decision is
 * provably independent of it (covered by host test asserting identical outcomes
 * for 0 and N attempts).
 *
 * Endpoint debounce (plan: "validate endpoint debounce after a previous
 * endpoint/long silence, not only at the first onset"): ENDPOINT additionally
 * requires [msSinceLastEndpointMs] >= [EndpointConfig.debounceAfterEndpointMs].
 */
data class EndpointSignals(
    val neuralPresence: Boolean?,
    val energySpeech: Boolean,
    val providerCompleted: Boolean = false,
    val providerEndpointAuthoritative: Boolean = false,
)

data class EndpointConfig(
    val endpointSilenceMs: Long = 650,
    val debounceAfterEndpointMs: Long = 1500,
)

enum class SpeechEndpoint { NO_SPEECH, SPEECH, ENDPOINT }

object VadRoutePolicy {
    /**
     * @param silenceElapsedMs sustained non-speech duration feeding a potential endpoint.
     * @param msSinceLastEndpointMs time since the previous endpoint (Long.MAX_VALUE if none).
     * @param localAsrAttemptCount accepted for telemetry only; MUST NOT change the decision.
     * @param hasSpeechEver whether neural/energy speech was ever observed this utterance.
     */
    @Suppress("UNUSED_PARAMETER")
    fun evaluate(
        signals: EndpointSignals,
        silenceElapsedMs: Long,
        msSinceLastEndpointMs: Long,
        config: EndpointConfig = EndpointConfig(),
        localAsrAttemptCount: Int = 0,
        hasSpeechEver: Boolean = true,
    ): SpeechEndpoint {
        require(localAsrAttemptCount >= 0) { "localAsrAttemptCount must be >= 0" }
        val neural = signals.neuralPresence

        // Active neural speech is never cut by a provider completion.
        if (neural == true) return SpeechEndpoint.SPEECH

        // Onset / continued speech while the detector is reporting.
        if (neural == null && signals.energySpeech) return SpeechEndpoint.SPEECH

        // Silence path: neural false (or degraded-mode energy silence).
        val silent = neural == false || (neural == null && !signals.energySpeech)
        if (!silent) return SpeechEndpoint.NO_SPEECH
        if (!hasSpeechEver) return SpeechEndpoint.NO_SPEECH

        val debounceOk = msSinceLastEndpointMs >= config.debounceAfterEndpointMs
        val endpointByLocal = silenceElapsedMs >= config.endpointSilenceMs && debounceOk
        val endpointByProvider = signals.providerCompleted &&
            signals.providerEndpointAuthoritative &&
            debounceOk
        return if (endpointByLocal || endpointByProvider) SpeechEndpoint.ENDPOINT
        else SpeechEndpoint.NO_SPEECH
    }
}
