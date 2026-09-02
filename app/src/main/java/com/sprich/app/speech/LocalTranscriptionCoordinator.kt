package com.sprich.app.speech

import android.util.Log
import com.sprich.app.speech.api.FinalTranscript
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.canary.CanaryEngine
import com.sprich.app.speech.fastconformer.FastConformerEngine
import com.sprich.app.speech.lid.WhisperLidEngine

/**
 * Minimal production coordinator — extracts transcription routing from SprichIME.
 *
 * Benefits: easier Auto-without-Canary test, API sprint can compose another TranscriptionSource,
 * service stays focused on IME lifecycle, engine selection testable.
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
    private val fast: FastConformerEngine,
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
            is LocalAsrRoute.AutomaticFastConformer -> transcribeAutomatic(pcm, baseConfig)
            is LocalAsrRoute.AccurateCanary -> transcribeAccurate(pcm, baseConfig, route.language)
        }
    }

    private suspend fun transcribeAutomatic(
        pcm: ShortArray,
        baseConfig: SpeechSessionConfig,
    ): LocalTranscriptionResult {
        var effectiveConfig = baseConfig
        var resolved: ResolvedUtteranceLanguage = ResolvedUtteranceLanguage.Unknown
        var lidLatency: Long = 0
        var lidRaw = baseConfig.resolvedLanguageTag()

        // Per-utterance LID — production-safe, no mock, fail-closed.
        try {
            if (!lid.isLoaded()) {
                val lr = lid.load()
                Log.i("Coordinator", "LID auto-load success=${lr.isSuccess} err=${lr.exceptionOrNull()?.message}")
            }
            val outcome = lid.identify(pcm)
            when (outcome) {
                is WhisperLidEngine.LidOutcome.Detected -> {
                    lidLatency = outcome.latencyMs
                    lidRaw = outcome.rawCode
                    effectiveConfig = baseConfig.copy(
                        language = outcome.language,
                        speechLanguage = SpeechLanguage.Fixed(outcome.language.code)
                    )
                    resolved = ResolvedUtteranceLanguage.Known(outcome.language)
                    Log.i("Coordinator", "LID Detected raw=${outcome.rawCode} lang=${outcome.language} latencyMs=${outcome.latencyMs} pcm=${pcm.size}")
                }
                is WhisperLidEngine.LidOutcome.Unsupported -> {
                    lidLatency = outcome.latencyMs
                    lidRaw = outcome.rawCode
                    resolved = ResolvedUtteranceLanguage.Unknown
                    Log.w("Coordinator", "LID unsupported raw=${outcome.rawCode} — Unknown, FastConformer will decode language-neutrally")
                }
                is WhisperLidEngine.LidOutcome.Failed -> {
                    lidLatency = outcome.latencyMs
                    lidRaw = "failed:${outcome.reason}"
                    resolved = ResolvedUtteranceLanguage.Unknown
                    Log.w("Coordinator", "LID failed reason=${outcome.reason} — Unknown")
                }
                is WhisperLidEngine.LidOutcome.Unavailable -> {
                    lidRaw = "unavailable:${outcome.reason}"
                    resolved = ResolvedUtteranceLanguage.Unknown
                    Log.w("Coordinator", "LID unavailable reason=${outcome.reason} — Unknown")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Coordinator", "LID exception", e)
            resolved = ResolvedUtteranceLanguage.Unknown
            lidRaw = "exception"
        }

        // FastConformer is primary for Automatic — sequential after LID to bound peak heap (lid 98M + fast 126M).
        // Parallel would spike PSS (3 threads + 224M mmap). Keep sequential unless device proves >100ms median win.
        if (!fast.isLoaded()) {
            val fLoad = fast.load()
            Log.i("Coordinator", "FastConformer load success=${fLoad.isSuccess} pcm=${pcm.size}")
        }
        // Sequential heap-safe path (parallel gated off until device proves >100ms median win)
        val ft: FinalTranscript = try {
            fast.transcribeSnapshot(pcm, effectiveConfig)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Coordinator", "FastConformer transcribeSnapshot failed", e)
            FinalTranscript("")
        }
        return LocalTranscriptionResult(
            text = ft.text,
            resolvedLanguage = resolved,
            effectiveConfig = effectiveConfig,
            engineId = fast.engineId,
            lidLatencyMs = lidLatency,
            lidRaw = lidRaw,
        )
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
            speechLanguage = SpeechLanguage.Fixed(explicitLang.code)
        )
        val ft = try {
            canary.transcribeSnapshot(pcm, accurateConfig)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Coordinator", "Canary transcribeSnapshot failed", e)
            FinalTranscript("")
        }
        val resolved: ResolvedUtteranceLanguage = ResolvedUtteranceLanguage.Known(explicitLang)
        return LocalTranscriptionResult(
            text = ft.text,
            resolvedLanguage = resolved,
            effectiveConfig = accurateConfig,
            engineId = canary.engineId,
        )
    }
}
