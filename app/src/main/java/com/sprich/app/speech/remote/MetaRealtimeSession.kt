package com.sprich.app.speech.remote

import com.sprich.app.api.ApiException
import com.sprich.app.api.ApiHttp
import com.sprich.app.speech.ResolvedUtteranceLanguage
import com.sprich.app.speech.TranscriptionSourceId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** One socket, one frozen configuration, paced PCM, endStream, then normal server close. No reconnect. */
internal class MetaRealtimeSession(
    private val endpoint: String,
    private val model: String,
    private val request: RemoteSttRequest,
    private val client: OkHttpClient,
) : StreamingSttSession {
    private val epoch = ApiHttp.currentEpoch
    private val acknowledged = CompletableDeferred<Unit>()
    private val completed = CompletableDeferred<RemoteSttResult>()
    private val inputEnded = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val events = MetaVoiceProtocol.Events(request.options.mode, request.credential)
    private val state = MutableStateFlow(RemoteTranscriptUpdate())
    override val updates = state.asStateFlow()
    @Volatile private var socket: WebSocket? = null
    private var nextFrameNanos = 0L
    private var sampleCount = 0L
    private var startedNanos = 0L

    private fun fail(failure: ApiFailure, detail: String? = null) {
        if (cancelled.get() || epoch != ApiHttp.currentEpoch || request.isAuthorized?.invoke() == false) { cancel(); return }
        val error = ApiException(failure, detail)
        acknowledged.completeExceptionally(error)
        completed.completeExceptionally(error)
        socket?.let { ApiHttp.untrack(it); it.cancel() }
    }

    override suspend fun start() {
        check(started.compareAndSet(false, true))
        require(request.sampleRate == 16000 && request.credential.isNotBlank())
        startedNanos = System.nanoTime()
        val url = endpoint.trimEnd('/').removeSuffix("/v1") + "/v1/asr/realtime"
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (cancelled.get() || epoch != ApiHttp.currentEpoch || request.isAuthorized?.invoke() == false) { cancel(); return }
                // Authorization is deliberately absent from HTTP and URLs. The documented handshake owns it.
                if (!webSocket.send(MetaVoiceProtocol.settings(model, request, realtime = true).toString())) fail(ApiFailure.ProviderUnavailable)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (cancelled.get() || epoch != ApiHttp.currentEpoch || request.isAuthorized?.invoke() == false || completed.isCompleted) { cancel(); return }
                if (text.length > MetaVoiceProtocol.MAX_EVENT_BYTES) { fail(ApiFailure.InvalidResponse); return }
                try {
                    if (!acknowledged.isCompleted) {
                        val ack = JSONObject(text)
                        if (ack.optString("type") == "error") throw MetaVoiceProtocol.error(ack, request.credential)
                        if (ack.has("type") || MetaVoiceProtocol.text(ack, "sessionId").isBlank()) throw ApiException(ApiFailure.InvalidResponse)
                        acknowledged.complete(Unit)
                        state.value = RemoteTranscriptUpdate(stage = VoiceApiStage.LISTENING)
                    } else {
                        state.value = events.accept(text)
                    }
                } catch (e: Exception) { fail((e as? ApiException)?.failure ?: ApiFailure.InvalidResponse, (e as? ApiException)?.publicDetail) }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { /* Forward-compatible: only text events carry results. */ }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ApiHttp.untrack(webSocket)
                if (code != 1000 || !inputEnded.get()) {
                    fail(when (code) { 1013 -> ApiFailure.RateLimited; 1008 -> ApiFailure.InvalidResponse; else -> ApiFailure.ProviderUnavailable })
                    return
                }
                try {
                    val result = events.finish()
                    state.value = state.value.copy(preview = result, stage = VoiceApiStage.COMPLETE)
                    completed.complete(RemoteSttResult(result, ResolvedUtteranceLanguage.Unknown, TranscriptionSourceId.API_META_MUSE,
                        (System.nanoTime() - startedNanos) / 1_000_000))
                } catch (e: Exception) { fail((e as? ApiException)?.failure ?: ApiFailure.InvalidResponse) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = response?.let { MetaVoiceProtocol.httpFailure(it, request.credential) }
                fail(error?.failure ?: ApiFailure.fromException(t), error?.publicDetail)
            }
        })
        ApiHttp.track(checkNotNull(socket), epoch)
        if (cancelled.get()) { cancel(); throw CancellationException("Stream cancelled") }
        try { withTimeout(6000) { acknowledged.await() }; nextFrameNanos = System.nanoTime() }
        catch (e: Exception) { cancel(); throw e }
    }

    override suspend fun sendAudio(samples: ShortArray) {
        currentCoroutineContext().ensureActive()
        if (cancelled.get() || epoch != ApiHttp.currentEpoch || request.isAuthorized?.invoke() == false) throw CancellationException("API permission revoked")
        check(!inputEnded.get())
        sampleCount += samples.size
        require(sampleCount <= MetaVoiceProtocol.MAX_AUDIO_SAMPLES)
        var offset = 0
        while (offset < samples.size) {
            if (completed.isCompleted) { completed.await(); error("Stream ended before input") }
            val size = minOf(1280, samples.size - offset) // At most 80 ms; the server requires approximately real-time ingress.
            val waitMs = (nextFrameNanos - System.nanoTime()) / 1_000_000
            if (waitMs > 0) delay(waitMs)
            if (cancelled.get() || epoch != ApiHttp.currentEpoch || request.isAuthorized?.invoke() == false) throw CancellationException("API permission revoked")
            val bytes = ByteArray(size * 2)
            repeat(size) { i -> val sample = samples[offset + i].toInt(); bytes[i * 2] = sample.toByte(); bytes[i * 2 + 1] = (sample shr 8).toByte() }
            val ws = checkNotNull(socket)
            if (ws.queueSize() > 128_000 || !ws.send(bytes.toByteString())) throw ApiException(ApiFailure.ProviderUnavailable)
            nextFrameNanos = maxOf(nextFrameNanos, System.nanoTime() - 80_000_000) + size * 1_000_000_000L / 16000
            offset += size
        }
    }

    override suspend fun finish(): RemoteSttResult {
        if (inputEnded.compareAndSet(false, true)) {
            state.value = state.value.copy(stage = VoiceApiStage.FINISHING)
            if (socket?.send("{\"type\":\"endStream\"}") != true) fail(ApiFailure.ProviderUnavailable)
        }
        return completed.await()
    }

    override fun cancel() {
        cancelled.set(true)
        acknowledged.cancel(); completed.cancel()
        socket?.let { ApiHttp.untrack(it); it.cancel() }
    }
}
