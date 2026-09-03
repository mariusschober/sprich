package com.sprich.app.speech.remote

import com.sprich.app.api.ApiHttp
import com.sprich.app.api.readApiBody
import com.sprich.app.api.readApiEvents
import com.sprich.app.core.security.EndpointValidator
import com.sprich.app.speech.ResolvedUtteranceLanguage
import com.sprich.app.speech.TranscriptionSourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

/** Recording JSON/SSE and true realtime PCM use the same documented settings and event reducer. */
class MetaMuseSttProvider(
    private val baseUrl: String = "https://api.meta.ai",
    private val model: String = "muse-voice-transcribe-1.0",
    private val httpClient: OkHttpClient = ApiHttp.client,
) : StreamingRemoteSttProvider {
    override val id = "meta-muse-voice-transcribe"
    override val capabilities = RemoteSttCapabilities(streaming = true, keywordBiasing = true, endpointing = true, partialResults = true)
    override suspend fun createStreamingSession(request: RemoteSttRequest): StreamingSttSession {
        require(EndpointValidator.isValidHttpsUrl(baseUrl) && request.credential.isNotBlank())
        return MetaRealtimeSession(baseUrl, model, request, httpClient)
    }
    override suspend fun transcribe(request: RemoteSttRequest): RemoteSttResult = withContext(Dispatchers.IO) {
        require(EndpointValidator.isValidHttpsUrl(baseUrl) && request.credential.isNotBlank()) { "API is not configured" }
        require(request.pcm.isNotEmpty() && request.pcm.size <= 1_920_000 && request.sampleRate == 16_000) { "Invalid audio" }
        val metadata = MetaVoiceProtocol.settings(model, request, realtime = false)
        val body = recordingBody(metadata.toString(), OpenAiCompatibleSttProvider.wavBytes(request.pcm, request.sampleRate))
        val endpoint = baseUrl.trimEnd('/').removeSuffix("/v1") + "/v1/asr/transcribe"
        val progress = request.onProgress
        val upload = if (progress == null) body else object : RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun writeTo(sink: BufferedSink) {
                val total = contentLength()
                var written = 0L
                var lastUpdate = 0L
                val counted = object : ForwardingSink(sink) {
                    override fun write(source: okio.Buffer, byteCount: Long) {
                        super.write(source, byteCount); written += byteCount
                        val now = System.nanoTime()
                        if (now - lastUpdate >= 80_000_000 || written == total) {
                            progress(RemoteTranscriptUpdate(uploadedBytes = written, totalUploadBytes = total, stage = VoiceApiStage.UPLOADING)); lastUpdate = now
                        }
                    }
                }.buffer()
                body.writeTo(counted); counted.flush()
                progress(RemoteTranscriptUpdate(stage = VoiceApiStage.PROCESSING))
            }
        }
        val req = Request.Builder().url(endpoint).header("Authorization", "Bearer ${request.credential}")
            .header("Accept", if (progress == null) "application/json" else "text/event-stream").post(upload).build()
        val start = System.nanoTime()
        val text = if (progress == null) MetaVoiceProtocol.buffered(httpClient.newCall(req).readApiBody(onHttpFailure = { MetaVoiceProtocol.httpFailure(it, request.credential) }), request.options.mode) else {
            val events = MetaVoiceProtocol.Events(request.options.mode, request.credential)
            httpClient.newCall(req).readApiEvents(onHttpFailure = { MetaVoiceProtocol.httpFailure(it, request.credential) }) { progress(events.accept(it)) }
            events.finish()
        }
        progress?.invoke(RemoteTranscriptUpdate(text, request.pcm.size * 1000L / request.sampleRate, stage = VoiceApiStage.COMPLETE))
        // Neither languageBias nor the response schema establishes a detected language.
        RemoteSttResult(text, ResolvedUtteranceLanguage.Unknown, TranscriptionSourceId.API_META_MUSE, (System.nanoTime() - start) / 1_000_000)
    }

    /** The documented form has only disposition/type per part; no part lengths or charset parameters. */
    private fun recordingBody(settings: String, wav: ByteArray): RequestBody {
        val boundary = "sprich-${java.util.UUID.randomUUID()}"
        val prefix = ("--$boundary\r\nContent-Disposition: form-data; name=\"request\"\r\n" +
            "Content-Type: application/json\r\n\r\n$settings\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"dictation.wav\"\r\n" +
            "Content-Type: audio/wav\r\n\r\n").toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.US_ASCII)
        return object : RequestBody() {
            override fun contentType() = "multipart/form-data; boundary=$boundary".toMediaType()
            override fun contentLength() = prefix.size.toLong() + wav.size + suffix.size
            override fun writeTo(sink: BufferedSink) { sink.write(prefix); sink.write(wav); sink.write(suffix) }
        }
    }
}
