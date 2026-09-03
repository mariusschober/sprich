package com.sprich.app.api

import com.sprich.app.core.audio.AudioCapture
import com.sprich.app.speech.LanguagePolicy
import com.sprich.app.speech.refinement.*
import com.sprich.app.speech.remote.*
import kotlinx.coroutines.*

/** Ephemeral results are shown to the person checking their key, never written to diagnostics. */
data class ApiCheckResult(val text: String, val latencyMs: Long) {
    override fun toString() = "ApiCheckResult(chars=${text.length}, latencyMs=$latencyMs)"
}
class MicrophoneUnavailableException : Exception("Microphone unavailable")

object ApiConnectionCheck {
    suspend fun check(choice: ApiChoice, key: String, audio: ShortArray? = null): ApiCheckResult = withTimeout(
        if (choice.use == ApiUse.WRITING) choice.refinement().deadlineMs else choice.remote(LanguagePolicy.Automatic).deadlineMs
    ) {
        val start = System.nanoTime()
        val text = when (choice.use) {
            ApiUse.WRITING -> {
                val original = "um I think I think we should meet tomorrow at 9"
                val result = RefinementProviderFactory.create(choice.refinement(), key).refine(
                    RefinementRequest(original, "en", RefinementMode.CLEAN_DICTATION))
                if (RefinementValidator.validate(original, result.text, RefinementMode.CLEAN_DICTATION) !is RefinementValidator.Result.Accept) throw ApiException(ApiFailure.OutputRejected)
                result.text
            }
            ApiUse.VOICE -> {
                require(!choice.voiceOptions.streaming) { "Streaming requires a live microphone check" }
                val pcm = requireNotNull(audio)
                RemoteProviderFactory.create(choice.remote(LanguagePolicy.Automatic)).transcribe(
                    RemoteSttRequest(pcm, 16000, LanguagePolicy.Automatic, utteranceId = System.nanoTime(), credential = key, options = choice.voiceOptions)).text
            }
        }
        ApiCheckResult(text, (System.nanoTime() - start) / 1_000_000)
    }

    /** The microphone feeds the real streaming session during the check, not a replay after recording. */
    suspend fun recordAndCheck(choice: ApiChoice, key: String, vocabulary: List<String> = emptyList(),
        onRecording: (Boolean) -> Unit = {}, onProgress: (RemoteTranscriptUpdate) -> Unit = {}): ApiCheckResult = coroutineScope {
        require(choice.use == ApiUse.VOICE)
        val config = choice.remote(LanguagePolicy.Automatic)
        val epoch = ApiHttp.currentEpoch
        val id = System.nanoTime()
        val live = if (config.preferStreaming) LiveRemoteUtterance(this, config, epoch, id, vocabulary,
            credential = { key }, onProgress = onProgress) else null
        var pcm: ShortArray? = null
        try {
            onRecording(true)
            pcm = try { recordSample { data, offset, size -> live?.offerAudio(data, offset, size) } }
            finally { onRecording(false) }
            val frozen = checkNotNull(pcm)
            val start = System.nanoTime()
            val result = withTimeout(config.deadlineMs) {
                if (epoch != ApiHttp.currentEpoch) throw CancellationException("API permission revoked")
                if (live != null) live.finish(frozen) else RemoteProviderFactory.create(config).transcribe(
                    RemoteSttRequest(frozen, 16000, config.languagePolicy, vocabulary, id, key, options = config.options, onProgress = onProgress))
            }
            if (result.text.isBlank()) throw ApiException(ApiFailure.InvalidResponse)
            ApiCheckResult(result.text, (System.nanoTime() - start) / 1_000_000)
        } finally { live?.cancel(); pcm?.fill(0) }
    }

    /** Reuses the capture-scoped recorder. Leaving the screen stops it without blocking Main. */
    suspend fun recordSample(onAudio: (ShortArray, Int, Int) -> Unit = { _, _, _ -> }): ShortArray {
        val capture = AudioCapture()
        val samples = ShortArray(4 * 16000)
        val ready = CompletableDeferred<ShortArray>()
        var size = 0
        try {
            val started = capture.startWithOffset({ chunk, offset, length, _, _ ->
                val n = minOf(length, samples.size - size)
                if (n > 0) { chunk.copyInto(samples, size, offset, offset + n); onAudio(chunk, offset, n) }
                size += n
                if (size == samples.size && !ready.isCompleted) ready.complete(samples.copyOf())
            }, { ready.completeExceptionally(MicrophoneUnavailableException()) })
            if (!started) throw MicrophoneUnavailableException()
            return withTimeoutOrNull(6000) { ready.await() } ?: throw MicrophoneUnavailableException()
        } catch (_: SecurityException) {
            throw MicrophoneUnavailableException()
        } finally {
            val handle = capture.requestStop()
            withContext(NonCancellable + Dispatchers.IO) { handle?.awaitStop(); samples.fill(0) }
        }
    }
}
