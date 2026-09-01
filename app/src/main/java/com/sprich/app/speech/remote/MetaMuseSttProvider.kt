package com.sprich.app.speech.remote

import com.sprich.app.speech.ResolvedUtteranceLanguage
import com.sprich.app.speech.TranscriptionSourceId

/**
 * Meta Muse Voice Transcribe adapter.
 *
 * At execution time official Meta Model API documentation must be consulted for:
 * - exact model ID, auth mechanism, streaming transport, audio format, stream/session lifecycle,
 *   partial/final events, endpointing, detected-language metadata, keyword/context bias schema,
 *   error schema, cancellation semantics.
 *
 * Current status: BLOCKED — official API documentation/access unavailable.
 * This stub implements the provider contract but throws BLOCKED on actual calls.
 * Generic architecture is completed via deterministic mock providers.
 *
 * Do NOT guess endpoint or invent mock Meta endpoint and call it implemented.
 */
class MetaMuseSttProvider(
    private val baseUrl: String = "",
    private val model: String = "",
) : RemoteSttProvider {
    override val id = "meta-muse-voice-transcribe"
    override val capabilities = RemoteSttCapabilities(
        streaming = true,
        automaticLanguage = true,
        explicitLanguageHint = true,
        keywordBiasing = true,
        contextBiasing = true,
        endpointing = true,
        partialResults = true,
    )

    override suspend fun transcribe(request: RemoteSttRequest): RemoteSttResult {
        // BLOCKED until verified docs
        throw RemoteSttException(
            ApiFailure.ProviderUnavailable,
            "META_MUSE_ADAPTER: BLOCKED — official API docs/access unavailable (no guessed endpoint)"
        )
    }

    companion object {
        const val BLOCKED_REASON = "META_MUSE_ADAPTER: BLOCKED — official API docs/access unavailable. Generic mock architecture covers tests."
        fun isBlocked(): Boolean = true
    }
}
