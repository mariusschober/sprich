package com.sprich.app.speech.remote

import com.sprich.app.api.ApiException
import com.sprich.app.api.ApiHttp
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.zip.CRC32

/** Capture-scoped streaming work. Audio never waits on the network; overflow fails the whole request. */
class LiveRemoteUtterance(
    parent: CoroutineScope,
    val config: RemoteSttConfig,
    private val permissionEpoch: Long,
    private val utteranceId: Long,
    private val vocabulary: List<String>,
    private val credential: suspend () -> String,
    private val isCurrent: () -> Boolean = { true },
    private val onProgress: (RemoteTranscriptUpdate) -> Unit = {},
) {
    private val chunks = Channel<ShortArray>(64, onUndeliveredElement = { it.fill(0) })
    private val result = CompletableDeferred<RemoteSttResult>()
    private val state = MutableStateFlow(RemoteTranscriptUpdate())
    val updates = state.asStateFlow()
    private val lock = Any()
    private val checksum = CRC32()
    private var samples = 0
    private var ended = false
    private var quietMs = 0L
    private val sealedPcm = CompletableDeferred<ShortArray>()
    @Volatile private var snapshot: ShortArray? = null
    @Volatile private var session: StreamingSttSession? = null

    private val worker = parent.launch(Dispatchers.IO) {
        try {
            val key = credential()
            ensureAuthority()
            if (key.isBlank()) throw ApiException(ApiFailure.Authentication)
            val provider = RemoteProviderFactory.create(config) as? StreamingRemoteSttProvider
                ?: throw ApiException(ApiFailure.ModelUnavailable)
            val stream = provider.createStreamingSession(RemoteSttRequest(ShortArray(0), 16000, config.languagePolicy,
                vocabulary, utteranceId, key, preferStreaming = true, options = config.options,
                isAuthorized = { isCurrent() && permissionEpoch == ApiHttp.currentEpoch }))
            session = stream
            coroutineScope {
                val observer = launch {
                    var lastUiNanos = 0L
                    var lastStage: VoiceApiStage? = null
                    stream.updates.collect { update ->
                        state.value = update
                        val now = System.nanoTime()
                        if (now - lastUiNanos >= 80_000_000 || update.stage != lastStage) {
                            ensureAuthority(); onProgress(update); lastUiNanos = now; lastStage = update.stage
                        }
                    }
                }
                try {
                    stream.start()
                    for (chunk in chunks) {
                        try { ensureAuthority(); stream.sendAudio(chunk) }
                        finally { chunk.fill(0) }
                    }
                    ensureAuthority()
                    val frozen = sealedPcm.await()
                    val actual = CRC32()
                    for (sample in frozen) { val n = sample.toInt(); actual.update(n and 255); actual.update((n shr 8) and 255) }
                    val expected = synchronized(lock) { samples to checksum.value }
                    if (frozen.size != expected.first || actual.value != expected.second) throw CancellationException("Streaming PCM authority mismatch")
                    result.complete(stream.finish())
                } finally { observer.cancel() }
            }
        } catch (_: TimeoutCancellationException) {
            result.completeExceptionally(ApiException(ApiFailure.Timeout))
            reportFailure()
        } catch (e: CancellationException) {
            result.cancel(e)
        } catch (e: Exception) {
            result.completeExceptionally(if (e is ApiException) e else ApiException(ApiFailure.fromException(e)))
            reportFailure()
        } finally {
            session?.cancel(); chunks.cancel()
        }
    }
    init { worker.invokeOnCompletion { cause -> if (cause != null) result.cancel(CancellationException("Capture cancelled", cause)) } }

    private fun ensureAuthority() {
        if (permissionEpoch != ApiHttp.currentEpoch || !isCurrent()) throw CancellationException("API permission revoked")
    }
    private fun reportFailure() {
        state.value = state.value.copy(stage = VoiceApiStage.FAILED)
        if (permissionEpoch == ApiHttp.currentEpoch) onProgress(state.value)
    }

    /** Server completion can end a phrase sooner; a quiet watchdog also flushes empty/noisy turns. */
    fun shouldFinish(speaking: Boolean, localEndpoint: Boolean, durationMs: Long): Boolean {
        quietMs = if (speaking) 0 else quietMs + durationMs
        if (!config.options.detectTurns || state.value.stage == VoiceApiStage.FAILED) return localEndpoint
        val progress = state.value
        return (progress.completedTurns > 0 && progress.activeTurns == 0 && quietMs >= 128) || quietMs >= 1800
    }

    /** Caller can immediately reuse its recorder buffer. The queue owns this single extra copy. */
    fun offerAudio(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size - offset): Boolean = synchronized(lock) {
        if (ended || result.isCompleted || permissionEpoch != ApiHttp.currentEpoch || !isCurrent()) return@synchronized false
        if (samples + length > MetaVoiceProtocol.MAX_AUDIO_SAMPLES) {
            result.completeExceptionally(ApiException(ApiFailure.InvalidResponse)); reportFailure(); cancelWorker(); return@synchronized false
        }
        val copy = pcm.copyOfRange(offset, offset + length)
        if (!chunks.trySend(copy).isSuccess) {
            copy.fill(0)
            result.completeExceptionally(ApiException(ApiFailure.ProviderUnavailable)); reportFailure(); cancelWorker(); return@synchronized false
        }
        // Detect collector/stream divergence before accepting a remote result. This is an internal consistency check.
        for (i in offset until offset + length) { val n = pcm[i].toInt(); checksum.update(n and 255); checksum.update((n shr 8) and 255) }
        samples += length
        true
    }

    /** Half-close immediately at the capture boundary, even if an older utterance occupies the editor queue. */
    fun sealInput(frozenPcm: ShortArray) = synchronized(lock) {
        if (!ended) {
            ended = true; snapshot = frozenPcm; sealedPcm.complete(frozenPcm); chunks.close()
        } else if (snapshot !== frozenPcm) {
            cancel()
        }
    }

    suspend fun finish(frozenPcm: ShortArray): RemoteSttResult {
        ensureAuthority()
        sealInput(frozenPcm)
        return result.await()
    }

    private fun cancelWorker() { worker.cancel(); session?.cancel(); chunks.cancel() }
    fun cancel() { result.cancel(); cancelWorker() }
}
