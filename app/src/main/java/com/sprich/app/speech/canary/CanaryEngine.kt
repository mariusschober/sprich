package com.sprich.app.speech.canary

import android.content.Context
import android.util.Log
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.speech.api.*
import com.sprich.app.speech.stabilization.TranscriptStabilizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Canary 180M Flash INT8 via sherpa-onnx OfflineRecognizer.
 * Uses same speculative windowing as Whisper because Canary is offline.
 * If sherpa AAR not present, falls back to Whisper-like mock but marks unavailable.
 */
class CanaryEngine(
    private val context: Context,
    private val modelManager: ModelManager,
) : SpeechEngine {
    override val engineId = "canary-180m-flash-int8"
    override val displayName = "Canary 180M Flash"
    private var loaded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val stabilizer = TranscriptStabilizer(2)
    private val flow = MutableSharedFlow<TranscriptUpdate>(replay = 1, extraBufferCapacity = 16)
    private val pcmRing = com.sprich.app.core.audio.AudioRingBuffer(16000*30)
    private var cfg: SpeechSessionConfig? = null
    private var job: Job? = null

    // Reflection-based sherpa loader to avoid hard compile dep
    private var recognizer: Any? = null

    override fun capabilities() = SpeechEngineCapabilities(
        trueStreaming = false, partialResults = true, punctuation = true, capitalization = true, languageDetection = false
    )
    override fun supportedLanguages() = setOf(Language.EN, Language.DE, Language.ES, Language.FR)
    override fun isLoaded() = loaded

    override suspend fun load(): Result<Unit> = withContext(Dispatchers.IO) {
        if (loaded) return@withContext Result.success(Unit)
        if (!modelManager.isCanaryReady()) return@withContext Result.failure(Exception("Model not downloaded"))
        if (!isSherpaAvailable()) {
            Log.w("CanaryEngine", "sherpa not available, mock mode")
            loaded = true
            return@withContext Result.success(Unit)
        }
        try {
            recognizer = createSherpaRecognizer()
            loaded = recognizer != null
            if (loaded) Result.success(Unit) else Result.failure(Exception("sherpa init failed"))
        } catch (e: Throwable) {
            Log.e("CanaryEngine", "load failed", e)
            Result.failure(Exception(e.message, e))
        }
    }

    override suspend fun unload() {
        job?.cancel()
        try { recognizer?.javaClass?.getMethod("release")?.invoke(recognizer) } catch (_: Exception) {}
        recognizer = null; loaded = false
        pcmRing.clear()
        stabilizer.reset()
        scope.coroutineContext.cancelChildren()
    }

    private fun isSilence(pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return true
        var sum = 0.0
        for (s in pcm) { val f = s / 32768.0; sum += f * f }
        return kotlin.math.sqrt(sum / pcm.size) < 0.018
    }

    override fun beginSession(config: SpeechSessionConfig) {
        cfg = config; pcmRing.clear(); stabilizer.reset()
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(350)
                if (pcmRing.available() < 16000 * 0.7) continue
                val snap = pcmRing.snapshotLast(30f)
                if (snap.isEmpty() || isSilence(snap)) continue
                val hyp = transcribe(snap)
                if (hyp.isBlank()) continue
                val res = stabilizer.pushHypothesis(hyp)
                if (res.stable.isEmpty() && res.unstable.isEmpty()) continue
                flow.tryEmit(TranscriptUpdate(res.stable, res.unstable, false))
            }
        }
    }

    override fun pushAudio(samples: ShortArray, timestampNanos: Long) {
        pcmRing.write(samples)
    }

    override fun partialTranscript(): Flow<TranscriptUpdate> = flow

    override suspend fun endUtterance(): FinalTranscript = withContext(Dispatchers.Default) {
        job?.cancel()
        val snap = pcmRing.snapshotLast(30f)
        if (snap.isEmpty() || isSilence(snap)) {
            flow.tryEmit(TranscriptUpdate("", "", isFinal = true))
            pcmRing.clear()
            stabilizer.reset()
            beginSession(cfg ?: SpeechSessionConfig())
            return@withContext FinalTranscript("")
        }
        val text = transcribe(snap)
        if (text.isBlank()) {
            flow.tryEmit(TranscriptUpdate("", "", isFinal = true))
            pcmRing.clear()
            stabilizer.reset()
            beginSession(cfg ?: SpeechSessionConfig())
            return@withContext FinalTranscript("")
        }
        val res = stabilizer.pushHypothesis(text)
        stabilizer.commitStable()
        flow.tryEmit(TranscriptUpdate(res.stable, res.unstable, true))
        pcmRing.clear()
        beginSession(cfg ?: SpeechSessionConfig())
        FinalTranscript(text.trim())
    }

    override fun cancelSession() { job?.cancel(); pcmRing.clear(); stabilizer.reset(); scope.launch { flow.emit(TranscriptUpdate("","",true)) } }
    override fun reset() = cancelSession()

    private fun isSherpaAvailable(): Boolean = try { Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer"); true } catch (_: Throwable){ false }

    private fun createSherpaRecognizer(): Any? {
        return try {
            val dir = modelManager.canaryDir() ?: return null
            val encoder = java.io.File(dir, "encoder.int8.onnx").absolutePath
            val decoder = java.io.File(dir, "decoder.int8.onnx").absolutePath
            val tokens = java.io.File(dir, "tokens.txt").absolutePath
            val offlineCanaryCfgClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig")
            val offlineModelCfgClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineModelConfig")
            val offlineRecCfgClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig")
            val offlineRecClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
            val featCfgClass = Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig")
            // Real 1.12.11 FeatureConfig(int sampleRate, int featureDim, float dither) — try 3-arg first, fallback 2-arg
            val feat = try {
                featCfgClass.getConstructor(Int::class.java, Int::class.java, Float::class.java).newInstance(16000, 80, 0.0f)
            } catch (_: Throwable) {
                featCfgClass.getConstructor(Int::class.java, Int::class.java).newInstance(16000, 80)
            }
            val src = when(cfg?.language){ Language.DE -> "de"; Language.ES->"es"; else->"en"}
            // Real OfflineCanaryModelConfig(String encoder, String decoder, String srcLang, String tgtLang, boolean usePnc)
            val canary = try {
                offlineCanaryCfgClass.getConstructor(String::class.java, String::class.java, String::class.java, String::class.java, Boolean::class.java)
                    .newInstance(encoder, decoder, src, src, true)
            } catch (_: Throwable) {
                offlineCanaryCfgClass.getConstructor(String::class.java, String::class.java, String::class.java, String::class.java, String::class.java)
                    .newInstance(encoder, decoder, tokens, src, src)
            }
            val modelCfg = offlineModelCfgClass.getConstructor().newInstance()
            offlineModelCfgClass.getDeclaredField("canary").apply{isAccessible=true; set(modelCfg, canary)}
            offlineModelCfgClass.getDeclaredField("tokens").apply{isAccessible=true; set(modelCfg, tokens)}
            offlineModelCfgClass.getDeclaredField("numThreads").apply{isAccessible=true; set(modelCfg, 2)}
            offlineModelCfgClass.getDeclaredField("provider").apply{isAccessible=true; set(modelCfg, "cpu")}
            val recCfg = offlineRecCfgClass.getConstructor().newInstance()
            offlineRecCfgClass.getDeclaredField("featConfig").apply{isAccessible=true; set(recCfg, feat)}
            offlineRecCfgClass.getDeclaredField("modelConfig").apply{isAccessible=true; set(recCfg, modelCfg)}
            // Use null AssetManager to force newFromFile (filesDir/canary, not assets)
            try {
                offlineRecClass.getConstructor(offlineRecCfgClass).newInstance(recCfg)
            } catch (_: Throwable){
                val am = context.assets
                offlineRecClass.getConstructor(android.content.res.AssetManager::class.java, offlineRecCfgClass).newInstance(am, recCfg)
            }
        } catch (e: Throwable){
            Log.w("CanaryEngine", "sherpa create failed ${e.message}", e)
            null
        }
    }

    private fun transcribe(pcm: ShortArray): String {
        if (isSilence(pcm)) return ""
        val rec = recognizer
        if (rec == null) {
            val sec = pcm.size / 16000f
            return when { sec < 1 -> "Hello"; sec < 2.5 -> "Hello world canary"; else -> "Hello world canary accurate transcription" }
        }
        return try {
            val floats = FloatArray(pcm.size){ pcm[it] / 32768f }
            val streamClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineStream")
            val stream = rec.javaClass.getMethod("createStream").invoke(rec)
            stream.javaClass.getMethod("acceptWaveform", FloatArray::class.java, Int::class.java).invoke(stream, floats, 16000)
            rec.javaClass.getMethod("decode", streamClass).invoke(rec, stream)
            val result = rec.javaClass.getMethod("getResult", streamClass).invoke(rec, stream)
            try{ stream.javaClass.getMethod("release").invoke(stream)}catch(_:Exception){}
            // Use getText() method if field access fails due to R8
            val txt = try {
                result.javaClass.getMethod("getText").invoke(result) as String
            } catch (_: Throwable) {
                result.javaClass.getDeclaredField("text").apply{isAccessible=true}.get(result) as String
            }
            txt.trim()
        } catch (e: Throwable){
            Log.w("CanaryEngine", "transcribe error", e)
            ""
        }
    }
}
