package com.sprich.app.speech

import android.util.Log
import com.sprich.app.speech.api.FinalTranscript
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.canary.CanaryEngine
import com.sprich.app.speech.lid.WhisperLidEngine

/**
 * Minimal production coordinator — extracts transcription routing from SprichIME.
 *
 * T3: Automatic is Whisper Tiny language ID followed by Canary 180M Flash with the detected
 * language. FastConformer (NVIDIA NGC terms, no redistribution right) is no longer part of the
 * production configuration. If language detection fails or returns an unsupported language the
 * utterance is discarded without decoding (fail-closed, no guessed language), so Automatic never
 * silently transcribes in the wrong language.
 *
 * Does NOT over-engineer: single suspend transcribe(), no fake confidence.
 */
data class LocalTranscriptionResult(
    val text: String,
    val resolvedLanguage: ResolvedUtteranceLanguage,
    val effectiveConfig: SpeechSessionConfig,
    val engineId: String,
    val lidLatencyMs: Long = 0,
    val lidRaw: String = "",
)

open class LocalTranscriptionCoordinator(
    private val lid: WhisperLidEngine,
    private val canary: CanaryEngine,
) {
    /**
     * Transcribe one immutable pending utterance according to its captured route.
     * Does NOT mutate live capture buffers; uses pcm snapshot only.
     */
    open suspend fun transcribe(
        pcm: ShortArray,
        route: LocalAsrRoute,
        baseConfig: SpeechSessionConfig,
    ): LocalTranscriptionResult {
        return when (route) {
            is LocalAsrRoute.AutomaticCanary -> transcribeAutomatic(pcm, baseConfig)
            is LocalAsrRoute.AccurateCanary -> transcribeAccurate(pcm, baseConfig, route.language)
        }
    }

    private suspend fun transcribeAutomatic(
        pcm: ShortArray,
        baseConfig: SpeechSessionConfig,
    ): LocalTranscriptionResult {
        var lidLatency: Long = 0
        var lidRaw = baseConfig.resolvedLanguageTag()
        var detected: Language? = null

        // Per-utterance LID — production-safe, no mock, fail-closed.
        try {
            if (!lid.isLoaded()) {
                val lr = lid.load()
                Log.i("Coordinator", "LID auto-load success=${lr.isSuccess} err=${lr.exceptionOrNull()?.message}")
            }
            when (val outcome = lid.identify(pcm)) {
                is WhisperLidEngine.LidOutcome.Detected -> {
                    lidLatency = outcome.latencyMs
                    lidRaw = outcome.rawCode
                    detected = outcome.language
                    Log.i("Coordinator", "LID Detected raw=${outcome.rawCode} lang=${outcome.language} latencyMs=${outcome.latencyMs} pcm=${pcm.size}")
                }
                is WhisperLidEngine.LidOutcome.Unsupported -> {
                    lidLatency = outcome.latencyMs
                    lidRaw = outcome.rawCode
                    Log.w("Coordinator", "LID unsupported raw=${outcome.rawCode} — fail-closed, no Automatic decode")
                }
                is WhisperLidEngine.LidOutcome.Failed -> {
                    lidLatency = outcome.latencyMs
                    lidRaw = "failed:${outcome.reason}"
                    Log.w("Coordinator", "LID failed reason=${outcome.reason} — fail-closed, no Automatic decode")
                }
                is WhisperLidEngine.LidOutcome.Unavailable -> {
                    lidRaw = "unavailable:${outcome.reason}"
                    Log.w("Coordinator", "LID unavailable reason=${outcome.reason} — fail-closed, no Automatic decode")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Coordinator", "LID exception — fail-closed, no Automatic decode", e)
            lidRaw = "exception"
        }

        val language = detected
            ?: return LocalTranscriptionResult(
                text = "",
                resolvedLanguage = ResolvedUtteranceLanguage.Unknown,
                effectiveConfig = baseConfig,
                engineId = canary.engineId,
                lidLatencyMs = lidLatency,
                lidRaw = lidRaw,
            )

        val effectiveConfig = baseConfig.copy(
            language = language,
            speechLanguage = SpeechLanguage.Fixed(language.code),
        )
        if (!canary.isLoaded()) {
            val cLoad = canary.load()
            Log.i("Coordinator", "Canary load for Automatic success=${cLoad.isSuccess} lang=$language pcm=${pcm.size}")
            if (cLoad.isFailure) {
                return LocalTranscriptionResult("", ResolvedUtteranceLanguage.Known(language), effectiveConfig, canary.engineId, lidLatency, lidRaw)
            }
        }
        val text = decodeCanarySegmented(pcm, effectiveConfig)
        return LocalTranscriptionResult(
            text = text,
            resolvedLanguage = ResolvedUtteranceLanguage.Known(language),
            effectiveConfig = effectiveConfig,
            engineId = canary.engineId,
            lidLatencyMs = lidLatency,
            lidRaw = lidRaw,
        )
    }

    /**
     * R1: long utterances are decoded in bounded, lowest-energy segments and joined with
     * typography rules. The IME still performs exactly one editor mutation for the utterance.
     */
    private suspend fun decodeCanarySegmented(pcm: ShortArray, config: SpeechSessionConfig): String {
        val language = (config.speechLanguage as? SpeechLanguage.Fixed)?.toLegacyLanguage()
            ?: config.language ?: Language.AUTO
        val ranges = LongUtteranceSegmenter.plan(pcm)
        if (ranges.size <= 1) {
            return canary.transcribeSnapshot(pcm, config).text.trim()
        }
        val parts = ArrayList<String>(ranges.size)
        for (range in ranges) {
            val segment = pcm.copyOfRange(range.start, range.start + range.length)
            parts += canary.transcribeSnapshot(segment, config).text.trim()
        }
        Log.i("Coordinator", "segmented utterance samples=${pcm.size} segments=${ranges.size} decoded=${parts.count { it.isNotEmpty() }}")
        return LongUtteranceSegmenter.join(parts, language)
    }

    private suspend fun transcribeAccurate(
        pcm: ShortArray,
        baseConfig: SpeechSessionConfig,
        explicitLang: Language,
    ): LocalTranscriptionResult {
        // Ensure Canary loaded; LID not required.
        if (!canary.isLoaded()) {
            val cLoad = canary.load()
            Log.i("Coordinator", "Canary load for Accurate success=${cLoad.isSuccess} lang=$explicitLang")
        }
        // Accurate uses explicit language — no Auto.
        val accurateConfig = baseConfig.copy(
            language = explicitLang,
            speechLanguage = SpeechLanguage.Fixed(explicitLang.code),
        )
        val text = decodeCanarySegmented(pcm, accurateConfig)
        val resolved: ResolvedUtteranceLanguage = ResolvedUtteranceLanguage.Known(explicitLang)
        return LocalTranscriptionResult(
            text = text,
            resolvedLanguage = resolved,
            effectiveConfig = accurateConfig,
            engineId = canary.engineId,
        )
    }
}
