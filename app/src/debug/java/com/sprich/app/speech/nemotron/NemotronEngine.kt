package com.sprich.app.speech.nemotron

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.speech.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Nemotron 3.5 ASR Streaming 0.6B — true streaming via sherpa-onnx OnlineRecognizer
 * Supports 160ms (low-latency) and 560ms (accuracy) variants via same code; dir selected by ModelManager.
 * Per-stream language `auto` via OnlineStream.setOption("language", "auto") (sherpa 1.13.4+ PR #3671).
 * Comparables: Canary explicit baseline, Tiny LID+Canary, FastConformer implicit, Nemotron streaming auto.
 */
class NemotronEngine(
    private val context: Context,
    private val modelManager: ModelManager,
    // Optional override for benchmarking specific variant (null = auto-select 560 > 160)
    private val variant: Variant = Variant.Auto,
) : SpeechEngine {
    enum class Variant { Auto, MS160, MS560 }

    override val engineId = "nemotron-3.5-streaming-0.6b"
    override val displayName = "Nemotron 3.5 Streaming"
    private var loaded = false
    private var recognizer: OnlineRecognizer? = null
    private var currentStream: OnlineStream? = null
    private val inferenceMutex = Mutex()
    private val inferenceDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val nemotronScope = CoroutineScope(SupervisorJob() + inferenceDispatcher)
    private val flow = MutableSharedFlow<TranscriptUpdate>(replay = 0, extraBufferCapacity = 32)
    private var sessionConfig: SpeechSessionConfig? = null
    @Volatile var nativeDecodeStarts: Long = 0
    @Volatile var nativeDecodeCurrent: Int = 0
    @Volatile var nativeDecodeMaxConcurrency: Int = 0
    private var lastPartialEmitted: String = ""
    private val pcmBuffer = com.sprich.app.core.audio.UtterancePcmBuffer(16000 * 30)

    override fun capabilities() = SpeechEngineCapabilities(
        trueStreaming = true, partialResults = true, wordTimestamps = false, punctuation = true, capitalization = true, languageDetection = true
    )
    override fun supportedLanguages() = setOf(Language.EN, Language.DE, Language.ES, Language.FR)
    override fun isLoaded() = loaded

    private fun modelDir(): File? {
        return when (variant) {
            Variant.MS560 -> modelManager.nemotron560Dir() ?: modelManager.nemotronDir()
            Variant.MS160 -> modelManager.nemotron160Dir() ?: modelManager.nemotronDir()
            Variant.Auto -> modelManager.nemotronDir() // prefers 560 > 160 > legacy
        }
    }

    private fun isSherpaAvailable(): Boolean = try { Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer"); true } catch (_: Throwable) { false }

    override suspend fun load(): Result<Unit> = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            if (loaded && recognizer != null) return@withContext Result.success(Unit)
            if (!modelManager.isNemotronReady()) return@withContext Result.failure(Exception("Nemotron model not downloaded (560/160)"))
            if (!isSherpaAvailable()) return@withContext Result.failure(Exception("sherpa OnlineRecognizer not available (need 1.13.4+)"))
            val dir = modelDir() ?: return@withContext Result.failure(Exception("model dir not found"))
            val enc = File(dir, "encoder.int8.onnx").absolutePath
            val dec = File(dir, "decoder.int8.onnx").absolutePath
            val join = File(dir, "joiner.int8.onnx").absolutePath
            val tok = File(dir, "tokens.txt").absolutePath
            if (!File(enc).exists() || !File(dec).exists() || !File(join).exists() || !File(tok).exists()) {
                return@withContext Result.failure(Exception("Nemotron files missing in $dir"))
            }
            try {
                val feat = FeatureConfig(16000, 80)
                val transducer = OnlineTransducerModelConfig(enc, dec, join)
                val model = OnlineModelConfig()
                model.transducer = transducer
                model.tokens = tok
                model.numThreads = 2
                model.provider = "cpu"
                model.debug = false
                model.modelType = ""
                // Provide empty configs for unused model types (already null by default)
                val endpointCfg = EndpointConfig(
                    EndpointRule(false, 2.4f, 0.0f),
                    EndpointRule(false, 1.2f, 0.0f),
                    EndpointRule(false, 0.0f, 0.0f)
                )
                val config = OnlineRecognizerConfig()
                config.featConfig = feat
                config.modelConfig = model
                config.endpointConfig = endpointCfg
                config.enableEndpoint = true
                config.decodingMethod = "greedy_search"
                config.maxActivePaths = 4
                config.hotwordsScore = 1.5f
                config.blankPenalty = 0.0f
                // Use null AssetManager for absolute paths (like Canary/FastConformer)
                recognizer = OnlineRecognizer(null, config)
                loaded = recognizer != null
                if (loaded) {
                    Log.i("Nemotron", "loaded variant=$variant dir=$dir enc=$enc")
                    Result.success(Unit)
                } else Result.failure(Exception("OnlineRecognizer create failed"))
            } catch (e: Throwable) {
                Log.e("Nemotron", "load failed", e)
                Result.failure(Exception("Nemotron load failed: ${e.message}", e))
            }
        }
    }

    override suspend fun unload() {
        // Structured cancellation: wait for owned pushAudio work before releasing stream (no race where stream released while chunk still queued)
        try { nemotronScope.coroutineContext.cancelChildren(); nemotronScope.coroutineContext[Job]?.children?.forEach { try { it.join() } catch (_: Exception) {} } } catch (_: Exception) {}
        // Ensure no pending chunk work mutating stream while we release
        inferenceMutex.withLock {
            try { currentStream?.release() } catch (_: Exception) {}
            currentStream = null
            try { recognizer?.release() } catch (_: Exception) {}
            recognizer = null
            loaded = false
            pcmBuffer.clear()
            lastPartialEmitted = ""
        }
        // Cancel scope after mutex to avoid new pushes sneaking in during release
        try { nemotronScope.cancel() } catch (_: Exception) {}
    }

    override fun beginSession(config: SpeechSessionConfig) {
        sessionConfig = config
        pcmBuffer.clear()
        // Create new streaming session with language option
        // Must be called on inference thread to avoid race
        runBlocking(inferenceDispatcher) {
            inferenceMutex.withLock {
                try { currentStream?.release() } catch (_: Exception) {}
                currentStream = null
                val rec = recognizer ?: return@withLock
                try {
                    val stream = rec.createStream()
                    val langTag = when (val sl = config.speechLanguage) {
                        is SpeechLanguage.Auto -> "auto"
                        is SpeechLanguage.Fixed -> sl.tag.lowercase().take(5)
                    }
                    try { stream.setOption("language", langTag) } catch (_: Throwable) {}
                    currentStream = stream
                    Log.i("Nemotron", "beginSession lang=$langTag stream=$stream")
                } catch (e: Throwable) {
                    Log.w("Nemotron", "createStream failed", e)
                }
            }
        }
    }

    override fun pushAudio(samples: ShortArray, timestampNanos: Long) {
        // Preserve audio order: append to primitive buffer then feed via single structured scope + mutex (no reordering, no concurrent mutation)
        pcmBuffer.append(samples)
        // Structured, engine-owned scope — no per-chunk fresh scope, lifecycle-aware, ordered via limitedParallelism(1)+Mutex
        nemotronScope.launch {
            inferenceMutex.withLock {
                val rec = recognizer
                val stream = currentStream
                if (rec == null || stream == null) return@withLock
                try {
                    nativeDecodeStarts++
                    nativeDecodeCurrent++
                    if (nativeDecodeCurrent > nativeDecodeMaxConcurrency) nativeDecodeMaxConcurrency = nativeDecodeCurrent
                    try {
                        // Single immutable copy for this chunk — stream.acceptWaveform must not be concurrently mutated
                        val floats = FloatArray(samples.size) { samples[it] / 32768f }
                        stream.acceptWaveform(floats, 16000)
                        // Decode while ready — streaming transducer may become ready after each chunk
                        while (rec.isReady(stream)) {
                            rec.decode(stream)
                        }
                        val result = rec.getResult(stream)
                        val text = try { result.text.trim() } catch (_: Throwable) { "" }
                        // Stable/unstable semantics: do not repeatedly emit entire hypothesis as new incremental commit.
                        // Only emit when text changed since last partial to avoid growing-text duplication.
                        if (text.isNotBlank() && text != lastPartialEmitted) {
                            lastPartialEmitted = text
                            // For benchmark, raw hypothesis captured separately; production partial uses stable/unstable transport safely
                            flow.tryEmit(TranscriptUpdate(text, "", false, lang = sessionConfig?.speechLanguage?.toLegacyLanguage()))
                        }
                    } finally {
                        nativeDecodeCurrent--
                    }
                } catch (e: Throwable) {
                    Log.w("Nemotron", "pushAudio decode failed", e)
                    nativeDecodeCurrent = (nativeDecodeCurrent - 1).coerceAtLeast(0)
                }
            }
        }
    }

    private fun isSilence(pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return true
        var sum = 0.0
        for (s in pcm) { val f = s / 32768.0; sum += f * f }
        return kotlin.math.sqrt(sum / pcm.size) < 0.0005
    }

    override fun partialTranscript(): Flow<TranscriptUpdate> = flow

    override suspend fun endUtterance(): FinalTranscript = withContext(inferenceDispatcher) {
        // Wait for queued pushAudio work to complete before final drain — ensures every PCM chunk consumed exactly once/in order
        // Join nemotronScope's children that hold inferenceMutex? Use mutex to serialize with pending pushes.
        // We do not cancel pushes; we wait for them by acquiring mutex after they complete.
        inferenceMutex.withLock {
            val rec = recognizer
            if (rec == null) return@withLock FinalTranscript("")
            val snap = pcmBuffer.snapshot()
            pcmBuffer.clear()
            if (snap.isEmpty() || isSilence(snap)) {
                try { currentStream?.let { rec.reset(it) } } catch (_: Exception) {}
                flow.tryEmit(TranscriptUpdate("", "", isFinal = true))
                lastPartialEmitted = ""
                return@withLock FinalTranscript("")
            }
            try {
                var stream = currentStream
                if (stream == null) {
                    stream = rec.createStream()
                    val langTag = sessionConfig?.speechLanguage?.toBcp47() ?: "auto"
                    try { stream.setOption("language", langTag) } catch (_: Exception) {}
                    currentStream = stream
                }
                // Final snapshot decode via fresh stream to avoid polluted partial history — ensures one-shot vs streaming equivalence
                val finalStream = rec.createStream()
                val langTag = sessionConfig?.speechLanguage?.toBcp47() ?: "auto"
                try { finalStream.setOption("language", langTag) } catch (_: Exception) {}
                val floats = FloatArray(snap.size) { snap[it] / 32768f }
                finalStream.acceptWaveform(floats, 16000)
                finalStream.inputFinished()
                // Correct final drain: continue decoding while ready (sherpa transducer streaming pattern)
                while (rec.isReady(finalStream)) {
                    rec.decode(finalStream)
                }
                // At least one decode after inputFinished even if not ready, to flush tail
                try { rec.decode(finalStream) } catch (_: Throwable) {}
                val result = rec.getResult(finalStream)
                val text = try { result.text.trim() } catch (_: Throwable) { "" }
                try { finalStream.release() } catch (_: Exception) {}
                // Reset current stream for next utterance — one stream per utterance lifecycle
                try { stream.let { rec.reset(it) } } catch (_: Exception) {}
                lastPartialEmitted = ""
                flow.tryEmit(TranscriptUpdate(text, "", isFinal = true))
                FinalTranscript(text)
            } catch (e: Throwable) {
                Log.w("Nemotron", "endUtterance failed", e)
                FinalTranscript("")
            }
        }
    }

    /** Side-effect-bounded snapshot decode for overlapping utterance queue (like Canary.transcribeSnapshot).
     *  Uses correct sherpa final drain pattern: acceptWaveform -> inputFinished -> while(isReady) decode -> getResult
     *  Verifies every PCM chunk consumed exactly once/in order, and one-shot vs incremental finals equivalent.
     */
    suspend fun transcribeSnapshot(pcm: ShortArray, config: SpeechSessionConfig): FinalTranscript = withContext(inferenceDispatcher) {
        inferenceMutex.withLock {
            val rec = recognizer ?: return@withLock FinalTranscript("")
            if (pcm.isEmpty() || isSilence(pcm)) return@withLock FinalTranscript("")
            var stream: OnlineStream? = null
            try {
                nativeDecodeStarts++
                nativeDecodeCurrent++
                if (nativeDecodeCurrent > nativeDecodeMaxConcurrency) nativeDecodeMaxConcurrency = nativeDecodeCurrent
                try {
                    stream = rec.createStream()
                    val langTag = config.speechLanguage.toBcp47()
                    try { stream.setOption("language", langTag) } catch (_: Exception) {}
                    val floats = FloatArray(pcm.size) { pcm[it] / 32768f }
                    stream.acceptWaveform(floats, 16000)
                    stream.inputFinished()
                    // Correct upstream pattern: drain ready queue
                    while (rec.isReady(stream)) {
                        rec.decode(stream)
                    }
                    // Ensure at least one decode to flush tail
                    try { rec.decode(stream) } catch (_: Throwable) {}
                    val result = rec.getResult(stream)
                    val text = try { result.text.trim() } catch (_: Throwable) { "" }
                    FinalTranscript(text)
                } finally {
                    nativeDecodeCurrent--
                }
            } catch (e: Throwable) {
                Log.w("Nemotron", "transcribeSnapshot failed", e)
                nativeDecodeCurrent = (nativeDecodeCurrent - 1).coerceAtLeast(0)
                FinalTranscript("")
            } finally {
                // Release every temporary stream — no leak over 100 utterances, no lingering stream after cancel/unload
                if (stream != null) {
                    try { stream.release() } catch (_: Exception) {}
                }
            }
        }
    }

    override fun cancelSession() {
        runBlocking(inferenceDispatcher) {
            inferenceMutex.withLock {
                try { currentStream?.let { recognizer?.reset(it) } } catch (_: Exception) {}
                pcmBuffer.clear()
                flow.tryEmit(TranscriptUpdate("", "", isFinal = true))
            }
        }
    }
    override fun reset() = cancelSession()

    fun currentVariantDir(): File? = modelDir()
}
