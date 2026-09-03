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
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit

/**
 * General transcription coordinator — replaces ad-hoc SprichIME branching.
 * One utterance receives one immutable UtterancePlan at onset, then finalization uses that plan only.
 *
 * Guarantees:
 * - API_PRIMARY success path: local final decode count = 0 (no wasted local work)
 * - LOCAL_API_FALLBACK: remote called only on objective failure (exception/unavailable/blank)
 * - Every API operation belongs to one utterance token; supports cancellation via coroutine scope
 * - P0-18: shared HTTP client pooling (sharedHttpClient.newBuilder() shares ConnectionPool/Dispatcher)
 * - P1-37: uses real utteranceId from token, not fabricated nanoTime
 * - P1-38: retains typed failure metadata for observability
 */
class TranscriptionCoordinator(
    private val local: LocalTranscriptionCoordinator,
    private val remoteProviders: Map<String, RemoteSttProvider>, // providerId -> provider (test-injected mocks)
    private val secretStore: ApiSecretStore?,
    private val deadlinePolicy: DeadlinePolicy = DeadlinePolicy.DEFAULT,
    private val sharedHttpClient: okhttp3.OkHttpClient? = null,
) {
    // P1-38: retain last remote failure for Settings/diagnostics observability
    @Volatile var lastRemoteFailure: ApiFailure? = null
        private set
    @Volatile var lastRemoteFailureAtMs: Long = 0

    /**
     * Transcribe one immutable pending utterance according to its plan.
     * Uses same canonical frozen PCM regardless of path (API streaming is consumer, not duplicated).
     * utteranceId should be real UtteranceToken.utteranceId for identity (P1-37)
     */
    suspend fun transcribe(
        pcm: ShortArray,
        plan: UtterancePlan,
        utteranceId: Long = System.nanoTime(),
        live: com.sprich.app.speech.remote.LiveRemoteUtterance? = null,
        onProgress: ((com.sprich.app.speech.remote.RemoteTranscriptUpdate) -> Unit)? = null,
    ): TranscriptionResult {
        fun checkAuthority() {
            if (plan.apiPermissionEpoch != com.sprich.app.api.ApiHttp.currentEpoch) throw kotlinx.coroutines.CancellationException("API permission revoked")
        }
        checkAuthority()
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
                val remoteResult = tryRemote(pcm, tp.remote, plan.speechConfig, utteranceId, plan.remoteVocabularyHints, plan.apiPermissionEpoch, live, onProgress)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                checkAuthority()
                if (remoteResult != null) {
                    remoteResult.copy(timingMs = android.os.SystemClock.elapsedRealtime() - t0)
                } else {
                    // Remote failed — try fallback if exists, else return blank unknown (caller will trigger safe fallback handling)
                    val fallbackRoute = tp.localFallback
                    if (fallbackRoute != null) {
                        Log.w("TranscriptionCoordinator", "API primary failed (lastFailure=$lastRemoteFailure), falling back to local $fallbackRoute")
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
                // Local first, remote only on objective failure — Cancellation must NEVER trigger fallback/upload
                val localResult = try {
                    local.transcribe(pcm, tp.local, plan.speechConfig)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
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
                    checkAuthority()
                    val remoteResult = tryRemote(pcm, tp.remote, plan.speechConfig, utteranceId, plan.remoteVocabularyHints, plan.apiPermissionEpoch, null, onProgress)
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

    private suspend fun tryRemote(pcm: ShortArray, config: com.sprich.app.speech.remote.RemoteSttConfig, speechConfig: SpeechSessionConfig, utteranceId: Long, vocabulary: List<String>, permissionEpoch: Long,
        live: com.sprich.app.speech.remote.LiveRemoteUtterance?, onProgress: ((com.sprich.app.speech.remote.RemoteTranscriptUpdate) -> Unit)?): TranscriptionResult? {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (live != null && live.config != config) throw kotlinx.coroutines.CancellationException("Streaming plan mismatch")
        if (config.supportsStreaming && config.preferStreaming && live == null) {
            throw kotlinx.coroutines.CancellationException("Missing streaming authority")
        }
        val provider = if (live != null) null else try {
            com.sprich.app.speech.remote.RemoteProviderFactory.create(config, sharedHttpClient, deadlinePolicy, remoteProviders)
        } catch (_: IllegalArgumentException) {
            lastRemoteFailure = ApiFailure.ProviderUnavailable
            return null
        }
        // Resolve credential — secret store is authoritative, fail closed (no legacy plaintext fallback)
        val credential = if (live != null) "" else try { secretStore?.loadBoundSecret(config.credentialRef, config.providerId, config.endpoint) ?: "" } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "" }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (permissionEpoch != com.sprich.app.api.ApiHttp.currentEpoch) throw kotlinx.coroutines.CancellationException("API permission revoked")
        if (live == null && credential.isBlank()) {
            Log.w("TranscriptionCoordinator", "no credential for ${config.credentialRef}")
            lastRemoteFailure = ApiFailure.Authentication
            lastRemoteFailureAtMs = android.os.SystemClock.elapsedRealtime()
            return null
        }
        // P1-37: use real utteranceId from token, not fabricated nanoTime; pass preferStreaming for Muse/Gemini
        val request = RemoteSttRequest(
            pcm = pcm,
            sampleRate = 16000,
            languagePolicy = config.languagePolicy,
            personalVocabularyHints = if (config.supportsKeywordBiasing) vocabulary else emptyList(),
            utteranceId = utteranceId,
            credential = credential,
            preferStreaming = config.preferStreaming,
            options = config.options,
            onProgress = onProgress,
        )
        return try {
            val result = withTimeoutOrNull(config.deadlineMs) {
                if (live != null) live.finish(pcm) else checkNotNull(provider).transcribe(request)
            }
            if (result == null) {
                Log.w("TranscriptionCoordinator", "remote timeout after ${config.deadlineMs}ms")
                lastRemoteFailure = ApiFailure.Timeout
                lastRemoteFailureAtMs = android.os.SystemClock.elapsedRealtime()
                null
            } else {
                // P1-16: propagate remote-detected language consistently — update effectiveConfig to Known if detected
                val effective = when (val resolved = result.resolvedLanguage) {
                    is ResolvedUtteranceLanguage.Known -> speechConfig.copy(
                        language = resolved.language,
                        speechLanguage = com.sprich.app.speech.api.SpeechLanguage.Fixed(resolved.language.code)
                    )
                    else -> speechConfig
                }
                lastRemoteFailure = null
                TranscriptionResult(
                    text = result.text,
                    resolvedLanguage = result.resolvedLanguage,
                    effectiveConfig = effective,
                    source = result.sourceId,
                    timingMs = result.timingMs,
                )
            }
        } catch (e: RemoteSttException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (e.failure == ApiFailure.Cancelled) throw kotlinx.coroutines.CancellationException("Remote operation cancelled")
            Log.w("TranscriptionCoordinator", "remote failed ${e.failure}")
            lastRemoteFailure = e.failure
            lastRemoteFailureAtMs = android.os.SystemClock.elapsedRealtime()
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            lastRemoteFailure = (e as? com.sprich.app.api.ApiException)?.failure ?: ApiFailure.fromException(e)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (lastRemoteFailure == ApiFailure.Cancelled) throw kotlinx.coroutines.CancellationException("Remote operation cancelled")
            Log.w("TranscriptionCoordinator", "remote failed $lastRemoteFailure")
            lastRemoteFailureAtMs = android.os.SystemClock.elapsedRealtime()
            null
        }
    }
}
