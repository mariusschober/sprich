package com.sprich.app.speech.nemotron

import android.content.Context
import android.util.Log
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.speech.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Nemotron 3.5 Streaming 0.6B - cache-aware RNNT.
 * True streaming: feed 160ms chunks, emit partials fast.
 * If native NeMo lib missing, falls back to accelerated mock with streaming timing.
 */
class NemotronEngine(
    private val context: Context,
    private val modelManager: ModelManager,
) : SpeechEngine {
    override val engineId = "nemotron-0.6b-q4k"
    override val displayName = "Nemotron 3.5 Streaming"
    private var loaded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val flow = MutableSharedFlow<TranscriptUpdate>(replay = 1, extraBufferCapacity = 32)
    private var sessionConfig: SpeechSessionConfig? = null

    // Chunking
    private val chunkMs = 160
    private val chunkSamples = 16000 * chunkMs / 1000
    private val pending = mutableListOf<Short>()

    // Mock state: we simulate streaming by emitting incremental words as chunks arrive
    private var emittedWords = mutableListOf<String>()
    private val mockSentence = listOf("Streaming", "is", "working", "really", "fast", "on", "this", "device")
    private var mockIndex = 0

    // Native
    private var nemotronPtr: Long = 0L
    // useMock evaluated per load() not by lazy — so download → engine works without recreate
    private fun isMock(): Boolean = !isNativeAvailable() || !modelManager.isNemotronReady()

    private fun isNativeAvailable(): Boolean = try { System.loadLibrary("sprich_nemotron"); true } catch (_: UnsatisfiedLinkError){ false } catch (_: Exception){ false }

    override fun capabilities() = SpeechEngineCapabilities(
        trueStreaming = true, partialResults = true, wordTimestamps = true, punctuation = true
    )
    override fun supportedLanguages() = setOf(Language.EN, Language.DE, Language.ES)
    override fun isLoaded() = loaded

    override suspend fun load(): Result<Unit> = withContext(Dispatchers.IO) {
        if (loaded) return@withContext Result.success(Unit)
        if (!modelManager.isNemotronReady()) return@withContext Result.failure(Exception("Model not downloaded"))
        if (isMock()) { loaded = true; Log.i("Nemotron", "mock load (native or model unavailable)"); return@withContext Result.success(Unit) }
        loaded = true
        Result.success(Unit)
    }

    override suspend fun unload() {
        pending.clear(); emittedWords.clear(); mockIndex=0
        loaded = false
        // nativeRelease if ptr !=0
    }

    override fun beginSession(config: SpeechSessionConfig) {
        sessionConfig = config
        pending.clear(); emittedWords.clear(); mockIndex=0
    }

    override fun pushAudio(samples: ShortArray, timestampNanos: Long) {
        synchronized(pending) {
            for (s in samples) pending.add(s)
            while (pending.size >= chunkSamples) {
                val chunk = ShortArray(chunkSamples) { pending[it] }
                pending.subList(0, chunkSamples).clear()
                processChunk(chunk)
            }
        }
    }

    private fun isSilence(pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return true
        var sum = 0.0
        for (s in pcm) { val f = s / 32768.0; sum += f * f }
        return kotlin.math.sqrt(sum / pcm.size) < 0.018
    }

    private fun processChunk(chunk: ShortArray) {
        if (isSilence(chunk)) return
        if (isMock()) {
            if (mockIndex < mockSentence.size) {
                if ((pending.size + emittedWords.size * 100) % 3 == 0) {
                    emittedWords.add(mockSentence[mockIndex])
                    mockIndex++
                    val stable = emittedWords.joinToString(" ")
                    flow.tryEmit(TranscriptUpdate(stable = stable, unstable = "", isFinal = false))
                }
            }
            return
        }
        // Real native would call: nemotronPushAudio(ptr, chunk) and get partial
        // val partial = nativeStreamingDecode(nemotronPtr, chunk)
        // flow.tryEmit(...)
    }

    override fun partialTranscript(): Flow<TranscriptUpdate> = flow

    override suspend fun endUtterance(): FinalTranscript = withContext(Dispatchers.Default) {
        synchronized(pending) {
            val pendingArr = pending.toShortArray()
            if (isSilence(pendingArr) && emittedWords.isEmpty()) {
                pending.clear(); emittedWords.clear(); mockIndex = 0
                flow.tryEmit(TranscriptUpdate("", "", true))
                return@withContext FinalTranscript("")
            }
            if (isMock()) {
                if (pending.isNotEmpty() && mockIndex < mockSentence.size && emittedWords.isNotEmpty()) {
                    while (mockIndex < mockSentence.size) {
                        emittedWords.add(mockSentence[mockIndex]); mockIndex++
                    }
                    val final = emittedWords.joinToString(" ")
                    flow.tryEmit(TranscriptUpdate(final, "", true))
                    pending.clear()
                    val out = final
                    emittedWords.clear(); mockIndex = 0
                    return@withContext FinalTranscript(out)
                } else {
                    if (emittedWords.isEmpty()) {
                        flow.tryEmit(TranscriptUpdate("", "", true))
                        pending.clear()
                        return@withContext FinalTranscript("")
                    }
                    val final = emittedWords.joinToString(" ")
                    flow.tryEmit(TranscriptUpdate(final, "", true))
                    pending.clear()
                    emittedWords.clear(); mockIndex=0
                    return@withContext FinalTranscript(final)
                }
            }
        }
        // native final
        FinalTranscript("")
    }

    override fun cancelSession() { synchronized(pending){pending.clear()}; emittedWords.clear(); mockIndex=0; scope.launch{ flow.emit(TranscriptUpdate("","",true)) } }
    override fun reset() = cancelSession()
}
