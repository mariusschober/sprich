package com.sprich.app.speech.canary

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.sprich.app.core.audio.AudioRingBuffer
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.speech.api.FinalTranscript
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechEngine
import com.sprich.app.speech.api.SpeechEngineCapabilities
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.api.TranscriptUpdate
import com.sprich.app.speech.stabilization.TranscriptStabilizer
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class CanaryLanguageTask(
    val sourceLanguage: String,
    val targetLanguage: String,
)

internal fun normalizeCanaryLanguage(language: Language): Language =
    if (language == Language.AUTO) Language.EN else language

internal fun canaryLanguageTask(language: Language): CanaryLanguageTask {
    val explicit = normalizeCanaryLanguage(language)
    return CanaryLanguageTask(
        sourceLanguage = explicit.code,
        targetLanguage = explicit.code,
    )
}

/**
 * Local NeMo Canary 180M Flash INT8 transcription through sherpa-onnx.
 *
 * Canary is an offline, non-streaming model. It requires an explicit source language.
 * Sprich therefore never guesses a language by decoding the same audio as several languages:
 * that heuristic can turn a bad language guess into an apparent translation. For every decode,
 * source and target are the same language by construction.
 */
class CanaryEngine(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val modelManager: ModelManager,
) : SpeechEngine {

    override val engineId = "canary-180m-flash-int8"
    override val displayName = "Canary 180M Flash"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val loadMutex = Mutex()
    private val decodeMutex = Mutex()
    private val sessionGeneration = AtomicLong(0)
    private val stabilizer = TranscriptStabilizer(2)
    private val updates = MutableSharedFlow<TranscriptUpdate>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val pcmRing = AudioRingBuffer(SAMPLE_RATE * MAX_UTTERANCE_SECONDS)

    @Volatile private var loaded = false
    @Volatile private var acceptingAudio = false
    @Volatile private var sessionConfig = SpeechSessionConfig(language = Language.EN)

    private var recognizer: OfflineRecognizer? = null
    private var recognizerLanguage = Language.EN
    private var recognizerPunctuation = true
    private var partialJob: Job? = null

    override fun capabilities() = SpeechEngineCapabilities(
        trueStreaming = false,
        partialResults = true,
        punctuation = true,
        capitalization = true,
        languageDetection = false,
    )

    override fun supportedLanguages() = setOf(
        Language.EN,
        Language.DE,
        Language.ES,
        Language.FR,
    )

    override fun isLoaded(): Boolean = loaded

    override suspend fun load(): Result<Unit> = loadMutex.withLock {
        if (loaded && recognizer != null) return@withLock Result.success(Unit)

        val modelDir = modelManager.canaryDir()
            ?: return@withLock Result.failure(IllegalStateException("Canary model is not ready"))

        val requestedLanguage = normalizeCanaryLanguage(sessionConfig.language)
        val requestedPunctuation = sessionConfig.enablePunctuation

        try {
            val newRecognizer = createRecognizer(
                modelDir = modelDir,
                language = requestedLanguage,
                punctuation = requestedPunctuation,
            )
            recognizer?.release()
            recognizer = newRecognizer
            recognizerLanguage = requestedLanguage
            recognizerPunctuation = requestedPunctuation
            loaded = true
            Log.i(TAG, "loaded language=${requestedLanguage.code} punctuation=$requestedPunctuation")
            Result.success(Unit)
        } catch (t: Throwable) {
            loaded = false
            recognizer = null
            Log.e(TAG, "load failed", t)
            Result.failure(IllegalStateException("Could not initialize Canary: ${t.message}", t))
        }
    }

    override suspend fun unload() {
        acceptingAudio = false
        sessionGeneration.incrementAndGet()
        partialJob?.cancelAndJoin()
        partialJob = null
        decodeMutex.withLock {
            recognizer?.release()
            recognizer = null
            loaded = false
        }
        pcmRing.clear()
        stabilizer.reset()
    }

    override fun beginSession(config: SpeechSessionConfig) {
        val normalized = config.copy(language = normalizeCanaryLanguage(config.language))
        sessionConfig = normalized
        acceptingAudio = true
        pcmRing.clear()
        stabilizer.reset()

        val generation = sessionGeneration.incrementAndGet()
        partialJob?.cancel()
        partialJob = scope.launch {
            var lastDecodedSamples = 0
            while (isActive && generation == sessionGeneration.get()) {
                delay(PARTIAL_INTERVAL_MS)
                val available = pcmRing.available()
                if (available < MIN_PARTIAL_SAMPLES) continue
                if (available - lastDecodedSamples < MIN_NEW_PARTIAL_SAMPLES) continue

                val snapshot = pcmRing.snapshotLast(MAX_UTTERANCE_SECONDS.toFloat(), SAMPLE_RATE)
                if (!hasAudibleSignal(snapshot)) continue
                lastDecodedSamples = available

                val hypothesis = try {
                    decodeTranscript(snapshot, normalized)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    Log.w(TAG, "partial decode failed", t)
                    ""
                }
                if (
                    hypothesis.isBlank() ||
                    generation != sessionGeneration.get() ||
                    !acceptingAudio
                ) {
                    continue
                }

                val stable = stabilizer.pushHypothesis(hypothesis)
                if (stable.stable.isNotEmpty() || stable.unstable.isNotEmpty()) {
                    updates.tryEmit(
                        TranscriptUpdate(
                            stable = stable.stable,
                            unstable = stable.unstable,
                            isFinal = false,
                            lang = normalized.language,
                        ),
                    )
                }
            }
        }
    }

    override fun pushAudio(samples: ShortArray, timestampNanos: Long) {
        if (!acceptingAudio || samples.isEmpty()) return
        pcmRing.write(samples)
    }

    override fun partialTranscript(): Flow<TranscriptUpdate> = updates

    override suspend fun endUtterance(): FinalTranscript {
        acceptingAudio = false
        sessionGeneration.incrementAndGet()
        partialJob?.cancelAndJoin()
        partialJob = null

        val config = sessionConfig
        val snapshot = pcmRing.snapshotLast(MAX_UTTERANCE_SECONDS.toFloat(), SAMPLE_RATE)
        pcmRing.clear()

        if (snapshot.isEmpty() || !hasAudibleSignal(snapshot)) {
            stabilizer.reset()
            updates.tryEmit(
                TranscriptUpdate(
                    stable = "",
                    unstable = "",
                    isFinal = true,
                    lang = config.language,
                ),
            )
            return FinalTranscript("", language = config.language)
        }

        val text = try {
            decodeTranscript(snapshot, config).trim()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.e(TAG, "final decode failed", t)
            ""
        }

        if (text.isBlank()) {
            stabilizer.reset()
            updates.tryEmit(
                TranscriptUpdate(
                    stable = "",
                    unstable = "",
                    isFinal = true,
                    lang = config.language,
                ),
            )
            return FinalTranscript("", language = config.language)
        }

        val stabilized = stabilizer.pushHypothesis(text)
        updates.tryEmit(
            TranscriptUpdate(
                stable = stabilized.stable,
                unstable = stabilized.unstable,
                isFinal = true,
                lang = config.language,
            ),
        )
        stabilizer.reset()
        return FinalTranscript(text = text, language = config.language)
    }

    override fun cancelSession() {
        acceptingAudio = false
        sessionGeneration.incrementAndGet()
        partialJob?.cancel()
        partialJob = null
        pcmRing.clear()
        stabilizer.reset()
        updates.tryEmit(TranscriptUpdate("", "", isFinal = true))
    }

    override fun reset() = cancelSession()

    private suspend fun decodeTranscript(
        pcm: ShortArray,
        config: SpeechSessionConfig,
    ): String = decodeMutex.withLock {
        val activeRecognizer = recognizer
            ?: throw IllegalStateException("Canary recognizer is not loaded")
        val language = normalizeCanaryLanguage(config.language)
        val punctuation = config.enablePunctuation

        if (language != recognizerLanguage || punctuation != recognizerPunctuation) {
            activeRecognizer.setConfig(
                buildRecognizerConfig(
                    modelDir = modelManager.canaryDir()
                        ?: throw IllegalStateException("Canary model disappeared"),
                    language = language,
                    punctuation = punctuation,
                ),
            )
            recognizerLanguage = language
            recognizerPunctuation = punctuation
            Log.i(TAG, "decode config language=${language.code} punctuation=$punctuation")
        }

        val stream = activeRecognizer.createStream()
        try {
            val floats = FloatArray(pcm.size) { index -> pcm[index] / 32768f }
            stream.acceptWaveform(floats, SAMPLE_RATE)
            activeRecognizer.decode(stream)
            activeRecognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun createRecognizer(
        modelDir: java.io.File,
        language: Language,
        punctuation: Boolean,
    ): OfflineRecognizer = OfflineRecognizer(
        assetManager = null,
        config = buildRecognizerConfig(
            modelDir = modelDir,
            language = language,
            punctuation = punctuation,
        ),
    )

    private fun buildRecognizerConfig(
        modelDir: java.io.File,
        language: Language,
        punctuation: Boolean,
    ): OfflineRecognizerConfig {
        val task = canaryLanguageTask(language)
        require(language != Language.AUTO) { "Canary requires an explicit source language" }

        val encoder = java.io.File(modelDir, "encoder.int8.onnx")
        val decoder = java.io.File(modelDir, "decoder.int8.onnx")
        val tokens = java.io.File(modelDir, "tokens.txt")
        require(encoder.isFile && encoder.length() > 0) { "Missing Canary encoder" }
        require(decoder.isFile && decoder.length() > 0) { "Missing Canary decoder" }
        require(tokens.isFile && tokens.length() > 0) { "Missing Canary tokens" }

        val canary = OfflineCanaryModelConfig(
            encoder = encoder.absolutePath,
            decoder = decoder.absolutePath,
            srcLang = task.sourceLanguage,
            tgtLang = task.targetLanguage,
            usePnc = punctuation,
        )

        val model = OfflineModelConfig(
            canary = canary,
            tokens = tokens.absolutePath,
            numThreads = NUM_THREADS,
            debug = false,
            provider = "cpu",
        )

        val features = FeatureConfig(
            sampleRate = SAMPLE_RATE,
            featureDim = 80,
            dither = 0f,
        )

        return OfflineRecognizerConfig(
            featConfig = features,
            modelConfig = model,
            decodingMethod = "greedy_search",
        )
    }

    private fun hasAudibleSignal(pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return false
        var sumSquares = 0.0
        for (sample in pcm) {
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
        }
        return kotlin.math.sqrt(sumSquares / pcm.size) >= MIN_RMS
    }

    private companion object {
        const val TAG = "CanaryEngine"
        const val SAMPLE_RATE = 16_000
        const val NUM_THREADS = 2
        const val MAX_UTTERANCE_SECONDS = 30
        const val PARTIAL_INTERVAL_MS = 900L
        const val MIN_PARTIAL_SAMPLES = SAMPLE_RATE
        const val MIN_NEW_PARTIAL_SAMPLES = SAMPLE_RATE * 3 / 4
        const val MIN_RMS = 0.00035
    }
}
