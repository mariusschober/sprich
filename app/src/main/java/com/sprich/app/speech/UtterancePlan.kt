package com.sprich.app.speech

import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.remote.RemoteSttConfig

/**
 * One immutable per-utterance plan. Conceptually:
 * Mic -> UtteranceAudioCollector -> immutable UtterancePlan + PCM -> TranscriptionCoordinator
 *
 * Once speech onset occurs, route / language configuration / transcription mode / provider config revision / refinement mode must not change for that utterance.
 * Settings changes apply to NEXT utterance.
 */
data class UtterancePlan(
    val transcription: TranscriptionPlan,
    val refinement: RefinementPlan,
    val speechConfig: SpeechSessionConfig,
)

sealed interface TranscriptionPlan {
    data class Local(val route: LocalAsrRoute) : TranscriptionPlan
    data class ApiPrimary(val remote: RemoteSttConfig, val localFallback: LocalAsrRoute?) : TranscriptionPlan
    data class LocalApiFallback(val local: LocalAsrRoute, val remote: RemoteSttConfig) : TranscriptionPlan
}

sealed interface RefinementPlan {
    data object Off : RefinementPlan
    data class Enabled(val config: com.sprich.app.speech.refinement.RefinementConfig, val mode: com.sprich.app.speech.refinement.RefinementMode) : RefinementPlan
}
