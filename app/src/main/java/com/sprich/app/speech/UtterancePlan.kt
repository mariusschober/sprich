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
    val vocabulary: com.sprich.app.vocab.VocabSnapshot = com.sprich.app.vocab.VocabSnapshot.EMPTY,
) {
    override fun toString(): String {
        val mode = when (transcription) {
            is TranscriptionPlan.Local -> "LOCAL"
            is TranscriptionPlan.ApiPrimary -> "API_PRIMARY"
            is TranscriptionPlan.LocalApiFallback -> "LOCAL_API_FALLBACK"
        }
        val provider = when (transcription) {
            is TranscriptionPlan.ApiPrimary -> transcription.remote.providerId
            is TranscriptionPlan.LocalApiFallback -> transcription.remote.providerId
            else -> "local"
        }
        val model = when (transcription) {
            is TranscriptionPlan.ApiPrimary -> transcription.remote.model
            is TranscriptionPlan.LocalApiFallback -> transcription.remote.model
            else -> (transcription as? TranscriptionPlan.Local)?.route.toString() ?: "local"
        }
        val langPolicy = when (transcription) {
            is TranscriptionPlan.ApiPrimary -> transcription.remote.languagePolicy.toString()
            is TranscriptionPlan.LocalApiFallback -> transcription.remote.languagePolicy.toString()
            else -> speechConfig.speechLanguage.toString()
        }
        val refinementStr = when (refinement) {
            is RefinementPlan.Off -> "OFF"
            is RefinementPlan.Enabled -> refinement.mode.toString()
        }
        // Privacy-safe: no endpoint, no credential, no transcript, no vocab, only safe ids
        return "UtterancePlan(mode=$mode, provider=$provider, model=$model, languagePolicy=$langPolicy, refinement=$refinementStr, speechLang=${speechConfig.speechLanguage})"
    }
}

sealed interface TranscriptionPlan {
    data class Local(val route: LocalAsrRoute) : TranscriptionPlan
    data class ApiPrimary(val remote: RemoteSttConfig, val localFallback: LocalAsrRoute?) : TranscriptionPlan
    data class LocalApiFallback(val local: LocalAsrRoute, val remote: RemoteSttConfig) : TranscriptionPlan
}

sealed interface RefinementPlan {
    data object Off : RefinementPlan
    data class Enabled(val config: com.sprich.app.speech.refinement.RefinementConfig, val mode: com.sprich.app.speech.refinement.RefinementMode) : RefinementPlan
}
