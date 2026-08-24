package com.sprich.app.speech.whisper

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.sprich.app.speech.api.*
import com.sprich.app.speech.stabilization.TranscriptStabilizer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WhisperEngine(
    private val context: Context,
    private val modelAssetPath: String = "models/whisper-base-q5_1.bin",
) : SpeechEngine {
    override val engineId = "whisper-base-q5_1"
    override val displayName = "Whisper Base Q5_1"
    private var ctxPtr: Long = 0L
    private var loaded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private var sessionConfig: SpeechSessionConfig? = null
    private val pcmRing = com.sprich.app.core.audio.AudioRingBuffer(16000 * 30)
    private val transcriptFlow = MutableSharedFlow<TranscriptUpdate>(replay = 1, extraBufferCapacity = 16)
    private val stabilizer = TranscriptStabilizer(requiredAgreement = 2)
    private var segmentJob: Job? = null
    private var lastDecodeMs = 0L

    // Mock fallback: if native unavailable or model missing, produce deterministic mock
    private val useMock: Boolean by lazy { !WhisperJNI.isAvailable() || !hasBundledModel() }

    private fun hasBundledModel(): Boolean {
        return try {
            context.assets.open(modelAssetPath).use { it.available() > 0 }
        } catch (_: Exception) { false }
    }

    fun isPlaceholderModel(): Boolean {
        return try { context.assets.open(modelAssetPath).use{ it.available() < 1_000_000 } } catch (_:Exception){ true }
    }

    override fun capabilities() = SpeechEngineCapabilities(
        trueStreaming = false,
        partialResults = true,
        wordTimestamps = false,
        languageDetection = true,
        punctuation = true,
        capitalization = true,
    )

    override fun supportedLanguages() = setOf(Language.EN, Language.DE, Language.ES, Language.AUTO)
    override fun isLoaded() = loaded
    private val loadMutex = Mutex()
    override suspend fun load(): Result<Unit> = withContext(Dispatchers.IO) {
        if (loaded) return@withContext Result.success(Unit)
        if (useMock) {
            Log.i("WhisperEngine", "Using mock (no native/model)")
            loaded = true
            return@withContext Result.success(Unit)
        }
        return@withContext loadMutex.withLock {
            if (loaded) Result.success(Unit)
            else try {
                val outFile = java.io.File(context.filesDir, "whisper-base-q5_1.bin")
                val tmpFile = java.io.File(context.filesDir, "whisper-base-q5_1.bin.tmp")
                if (!outFile.exists() || outFile.length() < 50_000_000) {
                    tmpFile.delete()
                    context.assets.open(modelAssetPath).use { inp ->
                        tmpFile.outputStream().use { out -> inp.copyTo(out) }
                    }
                    if (tmpFile.length() < 50_000_000) {
                        tmpFile.delete()
                        return@withLock Result.failure(Exception("asset copy truncated ${tmpFile.length()}"))
                    }
                    if (!tmpFile.renameTo(outFile)) {
                        tmpFile.copyTo(outFile, overwrite = true)
                        tmpFile.delete()
                    }
                    try { java.io.FileOutputStream(outFile, true).channel.force(true) } catch (_: Exception) {}
                }
                val ptr = WhisperJNI.nativeInit(outFile.absolutePath)
                if (ptr == 0L) return@withLock Result.failure(Exception("nativeInit returned 0"))
                ctxPtr = ptr
                val threads = minOf(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
                WhisperJNI.nativeSetThreads(ctxPtr, threads)
                loaded = true
                Log.i("WhisperEngine", "Loaded ctx=$ptr threads=$threads file=${outFile.length()}")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e("WhisperEngine", "load failed", e)
                Result.failure(e)
            }
        }
    }

    private val ctxMutex = Mutex()
    override suspend fun unload() {
        segmentJob?.cancel()
        try { segmentJob?.join() } catch (_: Exception) {}
        segmentJob = null
        // Serialize nativeRelease vs nativeTranscribe
        ctxMutex.withLock {
            if (ctxPtr != 0L && !useMock) {
                val ptr = ctxPtr
                ctxPtr = 0L
                withContext(Dispatchers.IO) { try { WhisperJNI.nativeRelease(ptr) } catch (_: Exception) {} }
            } else {
                ctxPtr = 0L
            }
        }
        loaded = false
        pcmRing.clear()
        stabilizer.reset()
        scope.coroutineContext.cancelChildren()
    }

    override fun beginSession(config: SpeechSessionConfig) {
        sessionConfig = config
        pcmRing.clear()
        stabilizer.reset()
        lastDecodeMs = SystemClock.elapsedRealtime()
        segmentJob?.cancel()
        segmentJob = scope.launch {
            while (isActive) {
                delay(320)
                if (!isActive) break
                if (pcmRing.available() < 16000 * 0.6) continue
                val snapshot = pcmRing.snapshotLast(30f)
                if (snapshot.isEmpty()) continue
                // Only decode if new audio > 0.3s since last decode or utterance likely
                val now = SystemClock.elapsedRealtime()
                if (now - lastDecodeMs < 280) continue
                lastDecodeMs = now
                try {
                    val hyp = transcribeSnapshot(snapshot)
                    if (hyp.isBlank()) continue // don't spam empty hypotheses
                    val res = stabilizer.pushHypothesis(hyp)
                    // avoid emitting duplicate stable+unstable (prevents HelloHello spam)
                    if (res.stable.isEmpty() && res.unstable.isEmpty()) continue
                    transcriptFlow.tryEmit(TranscriptUpdate(stable = res.stable, unstable = res.unstable, isFinal = false))
                } catch (e: Exception) {
                    Log.w("WhisperEngine", "speculative decode error", e)
                }
            }
        }
    }

    private fun isSilence(pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return true
        var sum = 0.0
        for (s in pcm) {
            val f = s / 32768.0
            sum += f * f
        }
        val rms = kotlin.math.sqrt(sum / pcm.size)
        // Threshold tuned to Vad energyThreshold ~0.015; silence <0.018
        return rms < 0.018
    }

    private suspend fun transcribeSnapshot(pcm: ShortArray): String {
        if (isSilence(pcm)) return ""
        if (useMock) {
            val sec = pcm.size / 16000f
            return when {
                sec < 1.0 -> "Hello"
                sec < 2.0 -> "Hello world"
                sec < 3.0 -> "Hello world this is"
                sec < 4.5 -> "Hello world this is Sprich"
                else -> "Hello world this is Sprich speaking"
            }
        }
        val ptr = ctxPtr
        if (ptr == 0L) return ""
        val lang = when (sessionConfig?.language) {
            Language.DE -> "de"; Language.ES -> "es"; Language.EN -> "en"; else -> "auto"
        }
        val window = if (pcm.size > 16000 * 10) pcm.copyOfRange(pcm.size - 16000 * 10, pcm.size) else pcm
        return try {
            // Serialize whisper_full via ctxMutex — native whisper_full NOT thread-safe for same ctx
            ctxMutex.withLock { WhisperJNI.nativeTranscribe(ptr, window, lang) ?: "" }
        } catch (e: Exception) {
            Log.w("WhisperEngine", "nativeTranscribe error", e)
            ""
        } catch (e: Throwable) {
            Log.w("WhisperEngine", "nativeTranscribe throwable", e)
            ""
        }
    }

    override fun pushAudio(samples: ShortArray, timestampNanos: Long) {
        pcmRing.write(samples)
    }

    override fun partialTranscript(): Flow<TranscriptUpdate> = transcriptFlow.asSharedFlow()

    override suspend fun endUtterance(): FinalTranscript = withContext(scope.coroutineContext) {
        segmentJob?.cancel()
        try { segmentJob?.join() } catch (_: Exception) {}
        val snapshot = pcmRing.snapshotLast(30f)
        val finalText = try { transcribeSnapshot(snapshot) } catch (e: Exception) { "" } catch (e: Throwable) { "" }
        if (finalText.isBlank()) {
            transcriptFlow.tryEmit(TranscriptUpdate("", "", isFinal = true))
            pcmRing.clear()
            stabilizer.reset()
            beginSession(sessionConfig ?: SpeechSessionConfig())
            return@withContext FinalTranscript("")
        }
        val res = stabilizer.pushHypothesis(finalText)
        stabilizer.commitStable()
        transcriptFlow.tryEmit(TranscriptUpdate(stable = res.stable, unstable = res.unstable, isFinal = true))
        pcmRing.clear()
        beginSession(sessionConfig ?: SpeechSessionConfig())
        FinalTranscript(text = finalText.trim())
    }

    override fun cancelSession() {
        segmentJob?.cancel()
        segmentJob = null
        pcmRing.clear()
        stabilizer.reset()
        scope.launch { transcriptFlow.emit(TranscriptUpdate("", "", isFinal = true)) }
    }

    override fun reset() { cancelSession() }
}
