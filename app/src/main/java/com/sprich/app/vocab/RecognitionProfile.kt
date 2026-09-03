package com.sprich.app.vocab

import com.sprich.app.speech.*
import com.sprich.app.speech.remote.RemoteSttConfig
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Locale

/** A correction belongs to a recognizer configuration, never to an API credential. */
@Serializable
data class RecognitionProfile(
    val key: String,
    val engine: String,
    val language: String? = null,
    val streaming: Boolean = false,
    val whisper: Boolean = false,
) {
    companion object {
        fun local(route: LocalAsrRoute, whisper: Boolean): RecognitionProfile {
            val language = (route as? LocalAsrRoute.AccurateCanary)?.language?.code
            return RecognitionProfile(hash(listOf("local", route.engineId(), language.orEmpty(), "$whisper")),
                if (language == null) "automatic" else "accurate", language, whisper = whisper)
        }

        fun remote(config: RemoteSttConfig, whisper: Boolean): RecognitionProfile {
            val language = config.languagePolicy.toHint()?.lowercase(Locale.ROOT)
            val options = config.options
            // Speaker labels affect formatting, not which words were heard. Lessons omit labels.
            val key = hash(listOf("api", config.providerId, config.endpoint.trimEnd('/'), config.model,
                language.orEmpty(), "${config.preferStreaming}", "${options.detectTurns}",
                options.languageHints.map { it.lowercase(Locale.ROOT) }.sorted().joinToString(","), "$whisper"))
            return RecognitionProfile(key, config.providerId, language, config.preferStreaming, whisper)
        }

        fun primary(plan: TranscriptionPlan, whisper: Boolean): RecognitionProfile = when (plan) {
            is TranscriptionPlan.Local -> local(plan.route, whisper)
            is TranscriptionPlan.ApiPrimary -> remote(plan.remote, whisper)
            is TranscriptionPlan.LocalApiFallback -> local(plan.local, whisper)
        }

        fun result(plan: UtterancePlan, source: TranscriptionSourceId): RecognitionProfile? {
            val route = plan.transcription
            return when (source) {
                TranscriptionSourceId.LOCAL_FAST, TranscriptionSourceId.LOCAL_CANARY -> {
                    val localRoute = when (route) {
                        is TranscriptionPlan.Local -> route.route
                        is TranscriptionPlan.ApiPrimary -> route.localFallback
                        is TranscriptionPlan.LocalApiFallback -> route.local
                    } ?: return null
                    val matches = when (localRoute) {
                        is LocalAsrRoute.AutomaticFastConformer -> source == TranscriptionSourceId.LOCAL_FAST
                        is LocalAsrRoute.AccurateCanary -> source == TranscriptionSourceId.LOCAL_CANARY
                    }
                    if (matches) local(localRoute, plan.whisperMode) else null
                }
                else -> when (route) {
                    is TranscriptionPlan.ApiPrimary -> remoteResult(route.remote, plan.whisperMode, source)
                    is TranscriptionPlan.LocalApiFallback -> remoteResult(route.remote, plan.whisperMode, source)
                    else -> null
                }
            }
        }

        private fun remoteResult(config: RemoteSttConfig, whisper: Boolean, source: TranscriptionSourceId): RecognitionProfile? {
            val matches = when (source) {
                TranscriptionSourceId.API_META_MUSE -> config.providerId in setOf("meta-muse", "meta-muse-voice-transcribe")
                TranscriptionSourceId.API_GEMINI -> config.providerId == "gemini"
                TranscriptionSourceId.API_OPENAI_COMPATIBLE -> config.providerId in setOf("openai", "custom", "openai-compatible")
                TranscriptionSourceId.API_MOCK -> config.providerId == "mock"
                else -> false
            }
            return if (matches) remote(config, whisper) else null
        }

        private fun hash(parts: List<String>): String = MessageDigest.getInstance("SHA-256")
            .digest(("recognition-v1\n" + parts.joinToString("\n") { "${it.length}:$it" }).toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
