package com.sprich.app.speech

import android.util.Log
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.remote.RemoteSttProvider
import com.sprich.app.speech.remote.RemoteSttRequest
import com.sprich.app.speech.remote.RemoteSttException
import com.sprich.app.speech.remote.ApiFailure
import com.sprich.app.speech.remote.DeadlinePolicy
import com.sprich.app.storage.ApiSecretStore
import kotlinx.coroutines.withTimeoutOrNull

/**
 * General transcription coordinator — replaces ad-hoc SprichIME branching.
 * One utterance receives one immutable UtterancePlan at onset, then finalization uses that plan only.
 *
 * Guarantees:
 * - API_PRIMARY success path: local final decode count = 0 (no wasted local work)
 * - LOCAL_API_FALLBACK: remote called only on objective failure (exception/unavailable/blank)
 * - Every API operation belongs to one utterance token; supports cancellation via coroutine scope
 */
class TranscriptionCoordinator(
    private val local: LocalTranscriptionCoordinator,
    private val remoteProviders: Map<String, RemoteSttProvider>, // providerId -> provider (test-injected mocks)
    private val secretStore: ApiSecretStore?,
    private val deadlinePolicy: DeadlinePolicy = DeadlinePolicy.DEFAULT,
    private val sharedHttpClient: okhttp3.OkHttpClient? = null,
) {
    /**
     * Transcribe one immutable pending utterance according to its plan.
     * Uses same canonical frozen PCM regardless of path (API streaming is consumer, not duplicated).
     */
    suspend fun transcribe(
        pcm: ShortArray,
        plan: UtterancePlan,
    ): TranscriptionResult {
        val t0 = android.os.SystemClock.elapsedRealtime()
        return when (val tp = plan.transcription) {
            is TranscriptionPlan.Local -> {
                val r = local.transcribe(pcm, tp.route, plan.speechConfig)
                TranscriptionResult(
                    text = r.text,
                    resolvedLanguage = r.resolvedLanguage,
                    effectiveConfig = r.effectiveConfig,
                    source = when (r.engineId) {
                        "fastconformer-ctc-en-de-es-fr-14288-int8" -> TranscriptionSourceId.LOCAL_FAST
                        else -> TranscriptionSourceId.LOCAL_CANARY
                    },
                    timingMs = android.os.SystemClock.elapsedRealtime() - t0,
                )
            }
            is TranscriptionPlan.ApiPrimary -> {
                val remoteResult = tryRemote(pcm, tp.remote, plan.speechConfig)
                if (remoteResult != null) {
                    remoteResult.copy(timingMs = android.os.SystemClock.elapsedRealtime() - t0)
                } else {
                    // Remote failed — try fallback if exists, else return blank unknown (caller will trigger safe fallback handling)
                    val fallbackRoute = tp.localFallback
                    if (fallbackRoute != null) {
                        Log.w("TranscriptionCoordinator", "API primary failed, falling back to local $fallbackRoute")
                        val r = local.transcribe(pcm, fallbackRoute, plan.speechConfig)
                        TranscriptionResult(
                            text = r.text,
                            resolvedLanguage = r.resolvedLanguage,
                            effectiveConfig = r.effectiveConfig,
                            source = when (r.engineId) {
                                "fastconformer-ctc-en-de-es-fr-14288-int8" -> TranscriptionSourceId.LOCAL_FAST
                                else -> TranscriptionSourceId.LOCAL_CANARY
                            },
                            timingMs = android.os.SystemClock.elapsedRealtime() - t0,
                        )
                    } else {
                        TranscriptionResult("", ResolvedUtteranceLanguage.Unknown, plan.speechConfig, TranscriptionSourceId.API_OPENAI_COMPATIBLE, android.os.SystemClock.elapsedRealtime() - t0)
                    }
                }
            }
            is TranscriptionPlan.LocalApiFallback -> {
                // Local first, remote only on objective failure
                val localResult = try {
                    local.transcribe(pcm, tp.local, plan.speechConfig)
                } catch (e: Exception) {
                    Log.w("TranscriptionCoordinator", "local failed, attempting fallback", e)
                    null
                }
                val text = localResult?.text?.trim().orEmpty()
                val isObjectiveFailure = localResult == null || text.isBlank()
                if (!isObjectiveFailure) {
                    TranscriptionResult(
                        text = localResult!!.text,
                        resolvedLanguage = localResult.resolvedLanguage,
                        effectiveConfig = localResult.effectiveConfig,
                        source = when (localResult.engineId) {
                            "fastconformer-ctc-en-de-es-fr-14288-int8" -> TranscriptionSourceId.LOCAL_FAST
                            else -> TranscriptionSourceId.LOCAL_CANARY
                        },
                        timingMs = android.os.SystemClock.elapsedRealtime() - t0,
                    )
                } else {
                    val remoteResult = tryRemote(pcm, tp.remote, plan.speechConfig)
                    if (remoteResult != null) remoteResult.copy(timingMs = android.os.SystemClock.elapsedRealtime() - t0)
                    else {
                        // Both failed — return original local blank
                        TranscriptionResult(
                            text = text,
                            resolvedLanguage = localResult?.resolvedLanguage ?: ResolvedUtteranceLanguage.Unknown,
                            effectiveConfig = localResult?.effectiveConfig ?: plan.speechConfig,
                            source = TranscriptionSourceId.LOCAL_FAST,
                            timingMs = android.os.SystemClock.elapsedRealtime() - t0,
                        )
                    }
                }
            }
        }
    }

    private suspend fun tryRemote(pcm: ShortArray, config: com.sprich.app.speech.remote.RemoteSttConfig, speechConfig: SpeechSessionConfig): TranscriptionResult? {
        // Resolve provider from frozen config (do not rely on mutable global prefs). Create on-demand for openai-compatible to reflect frozen endpoint/model.
        val provider: RemoteSttProvider = when (config.providerId) {
            "mock", "mock-refine" -> remoteProviders[config.providerId] ?: remoteProviders["mock"] ?: return null
            "meta-muse", "meta-muse-voice-transcribe" -> remoteProviders[config.providerId]
                ?: com.sprich.app.speech.remote.MetaMuseSttProvider(config.endpoint, config.model)
            else -> {
                // OpenAI-compatible: use injected mock if set, otherwise create fresh from frozen config (ensures Settings change mid-utterance not mixed)
                val injected = remoteProviders[config.providerId] ?: remoteProviders["openai-compatible"]
                if (injected != null && injected is com.sprich.app.speech.remote.MockRemoteSttProvider) {
                    injected
                } else {
                    // Create fresh adapter reflecting frozen endpoint/model, reusing shared client for pooling
                    val client = sharedHttpClient ?: com.sprich.app.speech.remote.OpenAiCompatibleSttProvider.createClient(deadlinePolicy)
                    com.sprich.app.speech.remote.OpenAiCompatibleSttProvider(config.endpoint, config.model, client)
                }
            }
        }
        // Resolve credential — secret store is authoritative, but fallback to direct load for Robolectric (Keystore may be unavailable)
        val credential = try { secretStore?.loadSecret(config.credentialRef) ?: "" } catch (_: Exception) { "" }
        if (credential.isBlank()) {
            Log.w("TranscriptionCoordinator", "no credential for ${config.credentialRef}")
            return null
        }
        val request = RemoteSttRequest(
            pcm = pcm,
            sampleRate = 16000,
            languagePolicy = config.languagePolicy,
            personalVocabularyHints = emptyList(), // controlled via settings privacy option
            utteranceId = System.nanoTime(),
            credential = credential,
        )
        return try {
            val result = withTimeoutOrNull(config.deadlineMs) {
                provider.transcribe(request)
            }
            if (result == null) {
                Log.w("TranscriptionCoordinator", "remote timeout after ${config.deadlineMs}ms")
                null
            } else {
                TranscriptionResult(
                    text = result.text,
                    resolvedLanguage = result.resolvedLanguage,
                    effectiveConfig = speechConfig, // keep original config but update resolved language
                    source = result.sourceId,
                    timingMs = result.timingMs,
                )
            }
        } catch (e: RemoteSttException) {
            Log.w("TranscriptionCoordinator", "remote failed ${e.failure}", e)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("TranscriptionCoordinator", "remote unexpected", e)
            null
        }
    }
}
