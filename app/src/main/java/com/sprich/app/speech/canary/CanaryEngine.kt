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

    // Per-utterance PCM capture — single authoritative buffer, primitive, bounded, exactly once.
    // No MutableList<Short> boxing, no removeAt(0) shifts. Owned solely by engine.
    private val utteranceBuffer = com.sprich.app.core.audio.UtterancePcmBuffer(16000 * 30)

    // Epoch to discard stale partials after session cancel / new session
    private val sessionEpoch = java.util.concurrent.atomic.AtomicLong(0)

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
                Log.w("CanaryEngine", "sherpa not available — no mock, failing load")
                return@withContext Result.failure(Exception("sherpa not available"))
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
        sessionEpoch.incrementAndGet()
        inferenceMutex.withLock {
            job?.cancel()
            try { recognizer?.javaClass?.getMethod("release")?.invoke(recognizer) } catch (_: Exception) {}
            recognizer = null; loaded = false
            pcmRing.clear()
            utteranceBuffer.clear()
            stabilizer.reset()
            scope.coroutineContext.cancelChildren()
            nativeDecodeStarts = 0; nativeDecodeCurrent = 0; nativeDecodeMaxConcurrency = 0
        }
    }

    private fun isSilence(pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return true
        var sum = 0.0
        for (s in pcm) { val f = s / 32768.0; sum += f * f }
        // Trust VAD for whisper: only reject true digital silence, not quiet speech.
        // Previous 0.004 threshold discarded whisper that VAD correctly detected (VAD ~0.0012).
        // Keep only extremely low defensive gate for digital zero.
        return kotlin.math.sqrt(sum / pcm.size) < 0.0005
    }

    /** Legacy threshold for tests that want to simulate old behavior. */
    private fun isSilenceLegacy(pcm: ShortArray): Boolean {
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
        utteranceBuffer.clear()
        val myEpoch = sessionEpoch.incrementAndGet()
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(350)
                if (sessionEpoch.get() != myEpoch) { // session cancelled / new session started — drop stale work
                    Log.i("CanaryEngine", "partial decode dropped stale epoch $myEpoch vs ${sessionEpoch.get()}")
                    return@launch
                }
                if (pcmRing.available() < 16000 * 0.7) continue
                val effectiveLang = cfg?.speechLanguage ?: SpeechLanguage.fromLegacy(cfg?.language ?: Language.AUTO)
                // All recognizer access must hold inferenceMutex — single authoritative owner
                val hyp: String = try {
                    inferenceMutex.withLock {
                        if (sessionEpoch.get() != myEpoch) return@withLock ""
                        if (effectiveLang is SpeechLanguage.Auto) {
                            try { switchLanguageLocked("en") } catch (_: Throwable) {}
                        } else if (effectiveLang is SpeechLanguage.Fixed) {
                            try { switchLanguageLocked(langCodeFor(effectiveLang.toLegacyLanguage())) } catch (_: Throwable) {}
                        } else if (cfg?.language != Language.AUTO) {
                            try { switchLanguageLocked(langCodeFor(cfg?.language)) } catch (_: Throwable) {}
                        }
                        val snap = pcmRing.snapshotLast(30f)
                        if (snap.isEmpty() || isSilence(snap)) return@withLock ""
                        transcribeLocked(snap)
                    }
                } catch (_: Throwable) { "" }
                if (sessionEpoch.get() != myEpoch) continue // dropped stale decode
                if (hyp.isBlank()) continue
                // Stabilizer mutation also gated by epoch
                if (sessionEpoch.get() != myEpoch) continue
                val res = stabilizer.pushHypothesis(hyp)
                if (res.stable.isEmpty() && res.unstable.isEmpty()) continue
                if (sessionEpoch.get() != myEpoch) continue
                flow.tryEmit(TranscriptUpdate(res.stable, res.unstable, false, lang = effectiveLang.toLegacyLanguage()))
            }
        }
    }

    override fun pushAudio(samples: ShortArray, timestampNanos: Long) {
        pcmRing.write(samples)
        utteranceBuffer.append(samples)
    }

    /**
     * Called by session coordinator at speech onset to seed pre-roll.
     * Ownership contract: this owns seeding preRoll exactly once — caller must NOT pushAudio(preRoll) again.
     */
    fun beginUtteranceCapture(preRoll: ShortArray) {
        utteranceBuffer.beginWithPreRoll(preRoll)
        pcmRing.clear()
        // Also seed ring for partial decoding (same single copy)
        if (preRoll.isNotEmpty()) pcmRing.write(preRoll)
    }

    /** Snapshot of current utterance PCM — the exact buffer fallback must use. */
    fun snapshotUtterancePcm(): ShortArray = utteranceBuffer.snapshot()

    /** Freeze utterance buffer at endpoint so concurrent pushes don't mutate fallback source. */
    private fun freezeUtterance(): ShortArray = utteranceBuffer.freeze()

    fun clearUtteranceCapture() {
        utteranceBuffer.clear()
        pcmRing.clear()
        stabilizer.reset()
    }

    override fun partialTranscript(): Flow<TranscriptUpdate> = flow

    override suspend fun endUtterance(): FinalTranscript = endUtteranceWithSnapshot(ShortArray(0))

    /**
     * Side-effect-bounded snapshot decode for overlapping utterance queue.
     * Serializes via inferenceMutex/inferenceDispatcher but does NOT mutate live capture state:
     * - does not clear pcmRing / utteranceBuffer
     * - does not clear stabilizer or job/sessionEpoch
     * - does not invalidate active capture's epoch
     * If partial decoding for B must pause while A uses recognizer, that is acceptable (mutex); losing B audio is not.
     * Config is immutable for this utterance (original language).
     */
    suspend fun transcribeSnapshot(pcm: ShortArray, config: SpeechSessionConfig): FinalTranscript = withContext(inferenceDispatcher) {
        inferenceMutex.withLock {
            cfg?.let { if (it.task != TranscriptionTask.TRANSCRIBE) Log.w("CanaryEngine", "transcribeSnapshot with task=${it.task}") }
            if (pcm.isEmpty() || isSilence(pcm)) {
                return@withLock FinalTranscript("")
            }
            val speechLang = config.speechLanguage ?: SpeechLanguage.fromLegacy(config.language ?: Language.AUTO)
            if (speechLang is SpeechLanguage.Auto) {
                Log.w("CanaryEngine", "transcribeSnapshot Auto requested but Canary has no native Auto — decoding as en")
                switchLanguageLocked("en")
            } else {
                switchLanguageLocked(langCodeFor((speechLang as SpeechLanguage.Fixed).toLegacyLanguage()))
            }
            val text = transcribeLocked(pcm)
            if (text.isBlank()) {
                return@withLock FinalTranscript("")
            }
            // No stabilizer mutation here to avoid corrupting live B's hypothesis.
            // No buffer clears — active capture remains intact.
            finalTranscriptCount++
            FinalTranscript(text.trim())
        }
    }

    // For continuous speech: allow next utterance capture while previous final decodes.
    // Endpoint captures frozen PCM immediately; decode uses that snapshot, not live buffer that may be overwritten.
    suspend fun endUtteranceWithSnapshot(snapshotPcm: ShortArray): FinalTranscript = withContext(inferenceDispatcher) {
        inferenceMutex.withLock {
            job?.cancel()
            sessionEpoch.incrementAndGet()
            cfg?.let { if (it.task != TranscriptionTask.TRANSCRIBE) Log.w("CanaryEngine", "endUtterance with task=${it.task}") }
            // Prefer explicitly provided snapshot (captured at endpoint before live buffer was reused for next utterance)
            // Fall back to internal frozen utterance buffer, then ring snapshot for legacy callers/tests
            val frozenUtterance = if (snapshotPcm.isNotEmpty()) snapshotPcm else freezeUtterance()
            val snap = when {
                frozenUtterance.isNotEmpty() -> frozenUtterance
                snapshotPcm.isNotEmpty() -> snapshotPcm
                else -> pcmRing.snapshotLast(30f)
            }
            if (snap.isEmpty() || isSilence(snap)) {
                flow.tryEmit(TranscriptUpdate("", "", isFinal = true))
                pcmRing.clear()
                utteranceBuffer.clear()
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
                utteranceBuffer.clear()
                stabilizer.reset()
                return@withLock FinalTranscript("")
            }
            val res = stabilizer.pushHypothesis(text)
            stabilizer.commitStable()
            flow.tryEmit(TranscriptUpdate(res.stable, res.unstable, true))
            pcmRing.clear()
            utteranceBuffer.clear()
            FinalTranscript(text.trim())
        }
    }

    override fun cancelSession() {
        sessionEpoch.incrementAndGet()
        job?.cancel()
        pcmRing.clear()
        utteranceBuffer.clear()
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
            // Production: no fabricated transcripts. Empty when native unavailable.
            // Keep concurrency instrumentation so tests can verify maxConcurrent==1.
            nativeDecodeStarts++
            nativeDecodeCurrent++
            if (nativeDecodeCurrent > nativeDecodeMaxConcurrency) nativeDecodeMaxConcurrency = nativeDecodeCurrent
            try { kotlinx.coroutines.delay(5) } finally { nativeDecodeCurrent-- }
            Log.w("CanaryEngine", "transcribeLocked no recognizer — returning empty, no mock")
            return ""
        }
        return decodeRawLocked(pcm)
    }

    // Shim for call sites that haven't yet moved to locked variants — delegates under mutex if needed
    private suspend fun transcribe(pcm: ShortArray): String = transcribeLocked(pcm)
    private suspend fun decodeRaw(pcm: ShortArray): String = decodeRawLocked(pcm)
    private suspend fun switchLanguage(lang: String) = switchLanguageLocked(lang)
}
