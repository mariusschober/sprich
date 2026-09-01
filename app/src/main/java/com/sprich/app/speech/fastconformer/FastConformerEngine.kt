package com.sprich.app.speech.fastconformer

import android.content.Context
import android.util.Log
import com.sprich.app.speech.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * NeMo FastConformer CTC EN-DE-ES-FR (14288) — offline CTC, multilingual implicit (no explicit language flag)
 * Model: sherpa-onnx-nemo-fast-conformer-ctc-en-de-es-fr-14288-int8
 * Archive 102,875,642 bytes, extracted model.int8.onnx 126 MB + tokens.txt 23K
 * License: NVIDIA NeMo Apache-2.0
 * Sherpa: OfflineRecognizer with OfflineNemoEncDecCtcModelConfig (model=...), via 1.13.6
 * Semantics: offline, non-streaming, implicit multilingual vocab without prompt_index, CTC beam search
 * Punctuation: to be measured (may be lower-case, punctuation-light)
 */
class FastConformerEngine(
    private val context: Context,
) : SpeechEngine {
    override val engineId = "fastconformer-ctc-en-de-es-fr-14288-int8"
    override val displayName = "FastConformer CTC 4-lang"
    private var loaded = false
    private val inferenceDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val inferenceMutex = Mutex()
    private var recognizer: Any? = null
    private val flow = MutableSharedFlow<TranscriptUpdate>(replay = 0, extraBufferCapacity = 16)
    private var cfg: SpeechSessionConfig? = null

    @Volatile var nativeDecodeStarts: Long = 0
    @Volatile var nativeDecodeCurrent: Int = 0
    @Volatile var nativeDecodeMaxConcurrency: Int = 0
    // Primitive bounded buffer — same architecture as Canary (no MutableList<Short> boxing)
    private val pcmBuffer = com.sprich.app.core.audio.UtterancePcmBuffer(16000 * 30)

    override fun capabilities() = SpeechEngineCapabilities(trueStreaming = false, partialResults = false, punctuation = false, capitalization = false, languageDetection = false)
    override fun supportedLanguages() = setOf(Language.EN, Language.DE, Language.ES, Language.FR)
    override fun isLoaded() = loaded

    private fun modelDir(): java.io.File? {
        val d = java.io.File(context.filesDir, "fastconformer")
        if (d.exists() && java.io.File(d, "model.int8.onnx").exists()) return d
        val tmp = java.io.File("/data/local/tmp/fastconformer")
        if (tmp.exists() && java.io.File(tmp, "model.int8.onnx").exists()) return tmp
        return null
    }

    private fun isSherpaAvailable(): Boolean = try { Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer"); true } catch (_: Throwable) { false }

    override suspend fun load(): Result<Unit> = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            if (loaded) return@withContext Result.success(Unit)
            val dir = modelDir() ?: return@withContext Result.failure(Exception("fastconformer model not found"))
            if (!isSherpaAvailable()) return@withContext Result.failure(Exception("sherpa not available"))
            val modelPath = java.io.File(dir, "model.int8.onnx").absolutePath
            val tokensPath = java.io.File(dir, "tokens.txt").absolutePath
            try {
                val featClass = Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig")
                val feat = try { featClass.getConstructor(Int::class.java, Int::class.java, Float::class.java).newInstance(16000, 80, 0.0f) } catch (_: Throwable) { featClass.getConstructor(Int::class.java, Int::class.java).newInstance(16000, 80) }
                val nemoCtcClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig")
                val nemo = nemoCtcClass.getConstructor(String::class.java).newInstance(modelPath)
                val modelConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineModelConfig")
                val modelCfg = modelConfigClass.getConstructor().newInstance()
                modelConfigClass.getDeclaredField("nemo").apply { isAccessible = true; set(modelCfg, nemo) }
                modelConfigClass.getDeclaredField("tokens").apply { isAccessible = true; set(modelCfg, tokensPath) }
                modelConfigClass.getDeclaredField("numThreads").apply { isAccessible = true; set(modelCfg, 2) }
                modelConfigClass.getDeclaredField("provider").apply { isAccessible = true; set(modelCfg, "cpu") }
                modelConfigClass.getDeclaredField("modelType").apply { isAccessible = true; set(modelCfg, "") }
                val recConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig")
                val recCfg = recConfigClass.getConstructor().newInstance()
                recConfigClass.getDeclaredField("featConfig").apply { isAccessible = true; set(recCfg, feat) }
                recConfigClass.getDeclaredField("modelConfig").apply { isAccessible = true; set(recCfg, modelCfg) }
                // Absolute path requires null AssetManager (https://github.com/k2-fsa/sherpa-onnx/issues/2562)
                val recClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
                val rec = recClass.getConstructor(android.content.res.AssetManager::class.java, Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig")).newInstance(null, recCfg)
                recognizer = rec
                loaded = rec != null
                if (loaded) Result.success(Unit) else Result.failure(Exception("create failed"))
            } catch (e: Throwable) {
                Log.w("FastConformer", "load failed", e)
                Result.failure(Exception("load failed: ${e.message}", e))
            }
        }
    }

    override suspend fun unload() {
        inferenceMutex.withLock {
            try { recognizer?.javaClass?.getMethod("release")?.invoke(recognizer) } catch (_: Exception) {}
            recognizer = null; loaded = false; pcmBuffer.clear()
        }
    }

    override fun beginSession(config: SpeechSessionConfig) {
        cfg = config; pcmBuffer.clear()
    }

    override fun pushAudio(samples: ShortArray, timestampNanos: Long) {
        pcmBuffer.append(samples)
    }

    fun beginUtteranceCapture(preRoll: ShortArray) {
        pcmBuffer.beginWithPreRoll(preRoll)
    }

    fun snapshotUtterancePcm(): ShortArray = pcmBuffer.snapshot()
    fun clearUtteranceCapture() = pcmBuffer.clear()

    override fun partialTranscript(): Flow<TranscriptUpdate> = flow

    private fun isSilence(pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return true
        var sum = 0.0; for (s in pcm) { val f = s/32768.0; sum+=f*f }
        return kotlin.math.sqrt(sum/pcm.size) < 0.0005
    }

    override suspend fun endUtterance(): FinalTranscript = withContext(inferenceDispatcher) {
        inferenceMutex.withLock {
            val pcm = pcmBuffer.snapshot()
            pcmBuffer.clear()
            if (pcm.isEmpty() || isSilence(pcm)) return@withLock FinalTranscript("")
            val text = decode(pcm)
            flow.tryEmit(TranscriptUpdate(text, "", true))
            FinalTranscript(text)
        }
    }

    suspend fun transcribeSnapshot(pcm: ShortArray, config: SpeechSessionConfig): FinalTranscript = withContext(inferenceDispatcher) {
        inferenceMutex.withLock {
            if (pcm.isEmpty() || isSilence(pcm)) return@withLock FinalTranscript("")
            val text = decode(pcm)
            FinalTranscript(text)
        }
    }

    private suspend fun decode(pcm: ShortArray): String {
        nativeDecodeStarts++; nativeDecodeCurrent++; if (nativeDecodeCurrent > nativeDecodeMaxConcurrency) nativeDecodeMaxConcurrency = nativeDecodeCurrent
        try {
            val rec = recognizer ?: return ""
            return try {
                val floats = FloatArray(pcm.size) { pcm[it]/32768f }
                val streamClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineStream")
                val stream = rec.javaClass.getMethod("createStream").invoke(rec)
                stream.javaClass.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType).invoke(stream, floats, 16000)
                rec.javaClass.getMethod("decode", streamClass).invoke(rec, stream)
                val result = rec.javaClass.getMethod("getResult", streamClass).invoke(rec, stream)
                try { stream.javaClass.getMethod("release").invoke(stream) } catch (_: Exception) {}
                val txt = try { result.javaClass.getMethod("getText").invoke(result) as String } catch (_: Throwable) { result.javaClass.getDeclaredField("text").apply { isAccessible = true }.get(result) as String }
                txt.trim()
            } catch (e: Throwable) {
                Log.w("FastConformer", "decode error", e)
                ""
            }
        } finally { nativeDecodeCurrent-- }
    }

    override fun cancelSession() { pcmBuffer.clear(); flow.tryEmit(TranscriptUpdate("", "", true)) }
    override fun reset() = cancelSession()
    fun isUsingPrimitiveBuffer(): Boolean = true // for test verification that boxed MutableList removed
}
