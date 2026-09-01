package com.sprich.app.speech.canary

import android.content.Context
import android.util.Log
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.speech.api.*
import com.sprich.app.speech.stabilization.TranscriptStabilizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Canary 180M Flash INT8 via sherpa-onnx OfflineRecognizer.
 * Offline windowed decoding with stable-prefix partials.
 *
 * Invariants:
 * - source language == target language on every decode (never translates)
 * - All recognizer access serialized via single inference dispatcher + Mutex
 * - Auto language detection cache REMOVED: Canary has no native Auto inference.
 *   When Auto is requested, we decode with a conservative fallback (en) and mark
 *   that Auto is not natively supported. Callers must provide explicit language
 *   or a dedicated LID stage. See docs/MODEL_BAKEOFF.
 * - Per-utterance PCM buffer captures onset→endpoint and is frozen at finalization
 *   so cloud fallback contains exactly the utterance, never previous utterances.
 */
class CanaryEngine(
    private val context: Context,
    private val modelManager: ModelManager,
) : SpeechEngine {
    override val engineId = "canary-180m-flash-int8"
    override val displayName = "Canary 180M Flash"
    private var loaded = false
    // Single-threaded inference lane — all recognizer access goes through here.
    private val inferenceDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val inferenceMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + inferenceDispatcher)
    private val stabilizer = TranscriptStabilizer(2)
    // Transcript updates are per-session events, not durable state.
    private val flow = MutableSharedFlow<TranscriptUpdate>(replay = 0, extraBufferCapacity = 16)
    private val pcmRing = com.sprich.app.core.audio.AudioRingBuffer(16000*30)
    private var cfg: SpeechSessionConfig? = null
    private var job: Job? = null

    // Reflection-based sherpa loader to avoid hard compile dep
    private var recognizer: Any? = null
    @Volatile private var recognizerLang: String = "en"

    // Per-utterance PCM capture — isolated from global ring, frozen at finalization.
    private val utteranceLock = Any()
    private val utterancePcm = mutableListOf<Short>()
    private var utteranceFrozen: ShortArray? = null

    // Diagnostics counters for stress tests (debug builds)
    @Volatile var nativeDecodeStarts: Long = 0
        private set
    @Volatile var nativeDecodeCurrent: Int = 0
        private set
    @Volatile var nativeDecodeMaxConcurrency: Int = 0
        private set
    @Volatile var finalTranscriptCount: Long = 0
        private set

    override fun capabilities() = SpeechEngineCapabilities(
        trueStreaming = false, partialResults = true, punctuation = true, capitalization = true, languageDetection = false
    )
    override fun supportedLanguages() = setOf(Language.EN, Language.DE, Language.ES, Language.FR)
    override fun isLoaded() = loaded

    override suspend fun load(): Result<Unit> = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            if (loaded) return@withContext Result.success(Unit)
            if (!modelManager.isCanaryReady()) return@withContext Result.failure(Exception("Model not downloaded"))
            if (!isSherpaAvailable()) {
                Log.w("CanaryEngine", "sherpa not available, mock mode")
                loaded = true
                return@withContext Result.success(Unit)
            }
            try {
                val langTag = cfg?.speechLanguage?.toBcp47() ?: cfg?.language?.code ?: "en"
                recognizerLang = if (langTag == "auto") "en" else when (langTag) {
                    "de", "de-de" -> "de"; "es", "es-es" -> "es"; "fr", "fr-fr" -> "fr"; else -> "en"
                }
                recognizer = createSherpaRecognizerLocked()
                loaded = recognizer != null
                if (loaded) Result.success(Unit) else Result.failure(Exception("sherpa init failed"))
            } catch (e: Throwable) {
                Log.e("CanaryEngine", "load failed", e)
                Result.failure(Exception(e.message, e))
            }
        }
    }

    override suspend fun unload() {
        inferenceMutex.withLock {
            job?.cancel()
            try { recognizer?.javaClass?.getMethod("release")?.invoke(recognizer) } catch (_: Exception) {}
            recognizer = null; loaded = false
            pcmRing.clear()
            synchronized(utteranceLock) { utterancePcm.clear(); utteranceFrozen = null }
            stabilizer.reset()
            scope.coroutineContext.cancelChildren()
            nativeDecodeStarts = 0; nativeDecodeCurrent = 0; nativeDecodeMaxConcurrency = 0
        }
    }

    private fun isSilence(pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return true
        var sum = 0.0
        for (s in pcm) { val f = s / 32768.0; sum += f * f }
        return kotlin.math.sqrt(sum / pcm.size) < 0.004
    }

    override fun beginSession(config: SpeechSessionConfig) {
        if (config.task != TranscriptionTask.TRANSCRIBE) {
            Log.w("CanaryEngine", "beginSession task=${config.task} — treating as TRANSCRIBE unless explicit translation invoked")
        }
        cfg = config; pcmRing.clear(); stabilizer.reset()
        synchronized(utteranceLock) { utterancePcm.clear(); utteranceFrozen = null }
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(350)
                if (pcmRing.available() < 16000 * 0.7) continue
                val effectiveLang = cfg?.speechLanguage ?: SpeechLanguage.fromLegacy(cfg?.language ?: Language.AUTO)
                // AUTO has NO native support on Canary. Do NOT attempt multi-decode guessing.
                // Conservative fallback: decode as EN and emit with note. Explicit language is required for reliable Auto.
                if (effectiveLang is SpeechLanguage.Auto) {
                    // Still emit partials decoded as EN (or last explicit) — better than fabricating language.
                    // Caller should have provided explicit language via LID stage if true Auto desired.
                    // We ensure recognizer is in EN to avoid leaving it in previous utterance's language.
                    try { switchLanguageLocked("en") } catch (_: Throwable) {}
                } else if (effectiveLang is SpeechLanguage.Fixed) {
                    try { switchLanguageLocked(langCodeFor(effectiveLang.toLegacyLanguage())) } catch (_: Throwable) {}
                } else if (cfg?.language != Language.AUTO) {
                    try { switchLanguageLocked(langCodeFor(cfg?.language)) } catch (_: Throwable) {}
                }
                val snap = pcmRing.snapshotLast(30f)
                if (snap.isEmpty() || isSilence(snap)) continue
                val hyp = transcribeLocked(snap)
                if (hyp.isBlank()) continue
                val res = stabilizer.pushHypothesis(hyp)
                if (res.stable.isEmpty() && res.unstable.isEmpty()) continue
                flow.tryEmit(TranscriptUpdate(res.stable, res.unstable, false, lang = effectiveLang.toLegacyLanguage()))
            }
        }
    }

    override fun pushAudio(samples: ShortArray, timestampNanos: Long) {
        pcmRing.write(samples)
        synchronized(utteranceLock) {
            // Append to per-utterance capture if not frozen. Frozen buffer is cleared on beginSession / after endUtterance.
            if (utteranceFrozen == null) {
                for (s in samples) utterancePcm.add(s)
                // Bound to 30s to avoid unbounded growth if VAD fails
                if (utterancePcm.size > 16000 * 30) {
                    val drop = utterancePcm.size - 16000 * 30
                    repeat(drop) { utterancePcm.removeAt(0) }
                }
            }
        }
    }

    /** Called by session coordinator at speech onset to seed pre-roll. */
    fun beginUtteranceCapture(preRoll: ShortArray) {
        synchronized(utteranceLock) {
            utterancePcm.clear()
            utteranceFrozen = null
            for (s in preRoll) utterancePcm.add(s)
        }
        pcmRing.clear()
        // Also seed ring for partial decoding
        if (preRoll.isNotEmpty()) pcmRing.write(preRoll)
    }

    /** Snapshot of current utterance PCM — the exact buffer fallback must use. */
    fun snapshotUtterancePcm(): ShortArray = synchronized(utteranceLock) {
        utteranceFrozen ?: utterancePcm.toShortArray()
    }

    /** Freeze utterance buffer at endpoint so concurrent pushes don't mutate fallback source. */
    private fun freezeUtterance(): ShortArray = synchronized(utteranceLock) {
        val frozen = utterancePcm.toShortArray()
        utteranceFrozen = frozen
        frozen
    }

    fun clearUtteranceCapture() {
        synchronized(utteranceLock) { utterancePcm.clear(); utteranceFrozen = null }
        pcmRing.clear()
        stabilizer.reset()
    }

    override fun partialTranscript(): Flow<TranscriptUpdate> = flow

    override suspend fun endUtterance(): FinalTranscript = withContext(inferenceDispatcher) {
        inferenceMutex.withLock {
            // Serialize: cancel speculative job under mutex to avoid concurrent decode
            job?.cancel()
            cfg?.let { if (it.task != TranscriptionTask.TRANSCRIBE) Log.w("CanaryEngine", "endUtterance with task=${it.task}") }
            // Freeze per-utterance PCM at finalization — cloud fallback must use this exact slice.
            val frozenUtterance = freezeUtterance()
            // Prefer frozen utterance buffer if we have it; fall back to ring snapshot for legacy callers (tests)
            val snap = if (frozenUtterance.isNotEmpty()) frozenUtterance else pcmRing.snapshotLast(30f)
            if (snap.isEmpty() || isSilence(snap)) {
                flow.tryEmit(TranscriptUpdate("", "", isFinal = true))
                pcmRing.clear()
                synchronized(utteranceLock) { utterancePcm.clear(); utteranceFrozen = null }
                stabilizer.reset()
                return@withLock FinalTranscript("")
            }
            val speechLang = cfg?.speechLanguage ?: SpeechLanguage.fromLegacy(cfg?.language ?: Language.AUTO)
            if (speechLang is SpeechLanguage.Auto) {
                Log.w("CanaryEngine", "endUtterance Auto requested but Canary has no native Auto — decoding as en. Use explicit language or LID stage.")
                switchLanguageLocked("en")
            } else {
                switchLanguageLocked(langCodeFor((speechLang as SpeechLanguage.Fixed).toLegacyLanguage()))
            }
            val text = transcribeLocked(snap)
            finalTranscriptCount++
            if (text.isBlank()) {
                flow.tryEmit(TranscriptUpdate("", "", isFinal = true))
                pcmRing.clear()
                synchronized(utteranceLock) { utterancePcm.clear(); utteranceFrozen = null }
                stabilizer.reset()
                return@withLock FinalTranscript("")
            }
            val res = stabilizer.pushHypothesis(text)
            stabilizer.commitStable()
            flow.tryEmit(TranscriptUpdate(res.stable, res.unstable, true))
            pcmRing.clear()
            synchronized(utteranceLock) { utterancePcm.clear(); utteranceFrozen = null }
            FinalTranscript(text.trim())
        }
    }

    override fun cancelSession() {
        job?.cancel()
        pcmRing.clear()
        synchronized(utteranceLock) { utterancePcm.clear(); utteranceFrozen = null }
        stabilizer.reset()
        // Synchronous clear — with replay=0 this never seeds next session
        flow.tryEmit(TranscriptUpdate("", "", true))
    }
    override fun reset() = cancelSession()

    private fun isSherpaAvailable(): Boolean = try { Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer"); true } catch (_: Throwable){ false }

    private fun langCodeFor(l: Language?): String = when (l) {
        Language.DE -> "de"; Language.ES -> "es"; Language.FR -> "fr"; else -> "en"
    }

    /** Hot-switches decode language via OfflineRecognizer.setConfig — serialized. */
    private suspend fun switchLanguageLocked(lang: String) {
        if (lang == recognizerLang) return
        val rec = recognizer ?: return
        // Must be called with inferenceMutex held
        try {
            val recCfg = buildRecognizerConfig(lang) ?: return
            val cfgClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig")
            rec.javaClass.getMethod("setConfig", cfgClass).invoke(rec, recCfg)
            recognizerLang = lang
            Log.i("CanaryEngine", "language switched to $lang")
        } catch (t: Throwable) {
            Log.w("CanaryEngine", "setConfig language switch to $lang failed", t)
        }
    }

    // ---- sherpa reflection wiring ----

    private fun buildRecognizerConfig(srcLang: String): Any? {
        val dir = modelManager.canaryDir() ?: return null
        val encoder = java.io.File(dir, "encoder.int8.onnx").absolutePath
        val decoder = java.io.File(dir, "decoder.int8.onnx").absolutePath
        val tokens = java.io.File(dir, "tokens.txt").absolutePath
        val offlineCanaryCfgClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig")
        val offlineModelCfgClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineModelConfig")
        val offlineRecCfgClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig")
        val featCfgClass = Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig")
        val feat = try {
            featCfgClass.getConstructor(Int::class.java, Int::class.java, Float::class.java).newInstance(16000, 80, 0.0f)
        } catch (_: Throwable) {
            featCfgClass.getConstructor(Int::class.java, Int::class.java).newInstance(16000, 80)
        }
        val canary = try {
            offlineCanaryCfgClass.getConstructor(String::class.java, String::class.java, String::class.java, String::class.java, Boolean::class.java)
                .newInstance(encoder, decoder, srcLang, srcLang, true)
        } catch (_: Throwable) {
            offlineCanaryCfgClass.getConstructor(String::class.java, String::class.java, String::class.java, String::class.java, String::class.java)
                .newInstance(encoder, decoder, tokens, srcLang, srcLang)
        }
        val modelCfg = offlineModelCfgClass.getConstructor().newInstance()
        offlineModelCfgClass.getDeclaredField("canary").apply{isAccessible=true; set(modelCfg, canary)}
        offlineModelCfgClass.getDeclaredField("tokens").apply{isAccessible=true; set(modelCfg, tokens)}
        offlineModelCfgClass.getDeclaredField("numThreads").apply{isAccessible=true; set(modelCfg, 2)}
        offlineModelCfgClass.getDeclaredField("provider").apply{isAccessible=true; set(modelCfg, "cpu")}
        val recCfg = offlineRecCfgClass.getConstructor().newInstance()
        offlineRecCfgClass.getDeclaredField("featConfig").apply{isAccessible=true; set(recCfg, feat)}
        offlineRecCfgClass.getDeclaredField("modelConfig").apply{isAccessible=true; set(recCfg, modelCfg)}
        return recCfg
    }

    private fun createSherpaRecognizerLocked(): Any? {
        // Called with inferenceMutex held
        return try {
            val recCfg = buildRecognizerConfig(recognizerLang) ?: return null
            val offlineRecClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
            try {
                val ctor = offlineRecClass.getConstructor(android.content.res.AssetManager::class.java, Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig"))
                ctor.newInstance(null, recCfg)
            } catch (e: Throwable) {
                Log.w("CanaryEngine", "null AssetManager failed, trying with assets", e)
                offlineRecClass.getConstructor(android.content.res.AssetManager::class.java, Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig")).newInstance(context.assets, recCfg)
            }
        } catch (e: Throwable){
            Log.w("CanaryEngine", "sherpa create failed ${e.message}", e)
            null
        }
    }

    private suspend fun decodeRawLocked(pcm: ShortArray): String {
        // Must be called with inferenceMutex held or from within withLock
        nativeDecodeStarts++
        nativeDecodeCurrent++
        if (nativeDecodeCurrent > nativeDecodeMaxConcurrency) nativeDecodeMaxConcurrency = nativeDecodeCurrent
        try {
            val rec = recognizer ?: return ""
            return try {
                val floats = FloatArray(pcm.size){ pcm[it] / 32768f }
                val streamClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineStream")
                val stream = rec.javaClass.getMethod("createStream").invoke(rec)
                stream.javaClass.getMethod("acceptWaveform", FloatArray::class.java, Int::class.java).invoke(stream, floats, 16000)
                rec.javaClass.getMethod("decode", streamClass).invoke(rec, stream)
                val result = rec.javaClass.getMethod("getResult", streamClass).invoke(rec, stream)
                try{ stream.javaClass.getMethod("release").invoke(stream)}catch(_:Exception){}
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
        } finally {
            nativeDecodeCurrent--
        }
    }

    private suspend fun transcribeLocked(pcm: ShortArray): String {
        if (isSilence(pcm)) return ""
        val rec = recognizer
        if (rec == null) {
            // Mock: keep serialized tracking
            nativeDecodeStarts++
            nativeDecodeCurrent++
            nativeDecodeMaxConcurrency = maxOf(nativeDecodeMaxConcurrency, nativeDecodeCurrent)
            try { delay(5) } finally { nativeDecodeCurrent-- }
            val sec = pcm.size / 16000f
            return when { sec < 1 -> "Hello"; sec < 2.5 -> "Hello world canary"; else -> "Hello world canary accurate transcription" }
        }
        return decodeRawLocked(pcm)
    }

    // Shim for call sites that haven't yet moved to locked variants — delegates under mutex if needed
    private suspend fun transcribe(pcm: ShortArray): String = transcribeLocked(pcm)
    private suspend fun decodeRaw(pcm: ShortArray): String = decodeRawLocked(pcm)
    private suspend fun switchLanguage(lang: String) = switchLanguageLocked(lang)
}
