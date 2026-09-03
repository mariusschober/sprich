package com.sprich.app.speech.remote

import kotlinx.coroutines.flow.Flow

/**
 * Provider adapter contract. Each provider (OpenAI-compatible, Meta Muse, Mock) implements this.
 */
interface RemoteSttProvider {
    val id: String
    val capabilities: RemoteSttCapabilities
    suspend fun transcribe(request: RemoteSttRequest): RemoteSttResult
}

/**
 * Streaming session contract for providers that support true streaming ASR (e.g. Meta Muse).
 */
interface StreamingSttSession {
    val updates: Flow<RemoteTranscriptUpdate>
    suspend fun start()
    suspend fun sendAudio(samples: ShortArray)
    suspend fun finish(): RemoteSttResult
    fun cancel()
}

interface StreamingRemoteSttProvider : RemoteSttProvider {
    suspend fun createStreamingSession(request: RemoteSttRequest): StreamingSttSession
}
