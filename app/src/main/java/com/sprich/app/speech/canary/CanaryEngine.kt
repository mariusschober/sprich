package com.sprich.app.speech.canary

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.speech.api.*
import com.sprich.app.speech.stabilization.TranscriptStabilizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Canary 180M Flash INT8 via sherpa-onnx OfflineRecognizer.
 * Offline windowed decoding with stable-prefix partials.
 * AUTO language: multi-decode + stopword scoring, fully offline, never translates
 * (source language == target language on every decode).
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
    // Transcript updates are per-session events, not durable state. Replaying the previous
    // partial into a newly attached IME collector can duplicate an earlier utterance.
    private val flow = MutableSharedFlow<TranscriptUpdate>(replay = 0, extraBufferCapacity = 16)
    private val pcmRing = com.sprich.app.core.audio.AudioRingBuffer(16000*30)
    private var cfg: SpeechSessionConfig? = null
    private var job: Job? = null

    // Reflection-based sherpa loader to avoid hard compile dep
    private var recognizer: Any? = null
    @Volatile private var recognizerLang: String = "en"

    // AUTO language detection cache — reused across utterances, expires after long silence
    @Volatile private var detectedLanguage: Language? = null
    @Volatile private var lastUtteranceElapsed = 0L

    override fun capabilities() = SpeechEngineCapabilities(
        trueStreaming = false, partialResults = true, punctuation = true, capitalization = true, languageDetection = true
    )
    override fun supportedLanguages() = setOf(Language.EN, Language.DE, Language.ES, Language.FR, Language.AUTO)
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
            detectedLanguage = null
            // Resolve from typed SpeechLanguage if present, else legacy Language.
            val langTag = cfg?.speechLanguage?.toBcp47() ?: cfg?.language?.code ?: "en"
            recognizerLang = if (langTag == "auto") "en" else when (langTag) {
                "de", "de-de" -> "de"; "es", "es-es" -> "es"; "fr", "fr-fr" -> "fr"; else -> "en"
            }
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
        detectedLanguage = null
        pcmRing.clear()
        stabilizer.reset()
        scope.coroutineContext.cancelChildren()
    }

    private fun isSilence(pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return true
        var sum = 0.0
        for (s in pcm) { val f = s / 32768.0; sum += f * f }
        return kotlin.math.sqrt(sum / pcm.size) < 0.004
    }

    override fun beginSession(config: SpeechSessionConfig) {
        // Enforce transcribe-only semantics unless explicit translation feature invoked.
        if (config.task != TranscriptionTask.TRANSCRIBE) {
            Log.w("CanaryEngine", "beginSession task=${config.task} — treating as TRANSCRIBE unless explicit translation invoked")
        }
        // Reset decoder context on language/field changes — bounded context, no stale prompt reuse.
        val prevLang = cfg?.speechLanguage?.toBcp47() ?: cfg?.language?.code
        val newLang = config.speechLanguage.toBcp47()
        if (prevLang != null && prevLang != newLang) {
            detectedLanguage = null // clear auto detection cache on explicit language switch
        }
        cfg = config; pcmRing.clear(); stabilizer.reset()
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(350)
                if (pcmRing.available() < 16000 * 0.7) continue
                val effectiveLang = cfg?.speechLanguage ?: SpeechLanguage.fromLegacy(cfg?.language ?: Language.AUTO)
                // AUTO before first detection: skip partials — wrong-language guesses are worse than none.
                if (effectiveLang is SpeechLanguage.Auto && detectedLanguage == null) continue
                // Explicit language: keep the recognizer in sync (it may have been created with cfg == null).
                if (effectiveLang is SpeechLanguage.Fixed) {
                    switchLanguage(langCodeFor(effectiveLang.toLegacyLanguage()))
                } else if (cfg?.language != Language.AUTO) {
                    switchLanguage(langCodeFor(cfg?.language))
                }
                val snap = pcmRing.snapshotLast(30f)
                if (snap.isEmpty() || isSilence(snap)) continue
                val hyp = transcribe(snap)
                if (hyp.isBlank()) continue
                val res = stabilizer.pushHypothesis(hyp)
                if (res.stable.isEmpty() && res.unstable.isEmpty()) continue
                flow.tryEmit(TranscriptUpdate(res.stable, res.unstable, false, lang = effectiveLang.toLegacyLanguage()))
            }
        }
    }

    override fun pushAudio(samples: ShortArray, timestampNanos: Long) {
        pcmRing.write(samples)
    }

    override fun partialTranscript(): Flow<TranscriptUpdate> = flow

    override suspend fun endUtterance(): FinalTranscript = withContext(Dispatchers.Default) {
        job?.cancel()
        // Enforce transcribe task — never translate unless explicitly invoked.
        cfg?.let { if (it.task != TranscriptionTask.TRANSCRIBE) Log.w("CanaryEngine", "endUtterance with task=${it.task}") }
        val snap = pcmRing.snapshotLast(30f)
        // Detection cache expires after 30s of silence — re-detect next utterance.
        val now = SystemClock.elapsedRealtime()
        if (now - lastUtteranceElapsed > 30_000 && detectedLanguage != null) {
            Log.i("CanaryEngine", "autoDetect cache expired")
            detectedLanguage = null
        }
        lastUtteranceElapsed = now
        if (snap.isEmpty() || isSilence(snap)) {
            flow.tryEmit(TranscriptUpdate("", "", isFinal = true))
            pcmRing.clear()
            stabilizer.reset()
            return@withContext FinalTranscript("")
        }
        val speechLang = cfg?.speechLanguage ?: SpeechLanguage.fromLegacy(cfg?.language ?: Language.AUTO)
        // AUTO: detect once per session, then reuse. Explicit language: always ensure the
        // recognizer decodes in that language (it may have been created before cfg was set).
        if (speechLang is SpeechLanguage.Auto) {
            val lang = detectedLanguage ?: run {
                val d = detectLanguage(snap)
                detectedLanguage = d
                d
            }
            switchLanguage(langCodeFor(lang))
        } else {
            switchLanguage(langCodeFor((speechLang as SpeechLanguage.Fixed).toLegacyLanguage()))
        }
        val text = transcribe(snap)
        if (text.isBlank()) {
            flow.tryEmit(TranscriptUpdate("", "", isFinal = true))
            pcmRing.clear()
            stabilizer.reset()
            return@withContext FinalTranscript("")
        }
        val res = stabilizer.pushHypothesis(text)
        stabilizer.commitStable()
        flow.tryEmit(TranscriptUpdate(res.stable, res.unstable, true))
        pcmRing.clear()
        FinalTranscript(text.trim())
    }

    override fun cancelSession() {
        job?.cancel()
        pcmRing.clear()
        stabilizer.reset()
        // Clear any current collector synchronously. With replay=0 this can never seed the
        // next session with an earlier non-final hypothesis.
        flow.tryEmit(TranscriptUpdate("", "", true))
    }
    override fun reset() = cancelSession()

    private fun isSherpaAvailable(): Boolean = try { Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer"); true } catch (_: Throwable){ false }

    private fun langCodeFor(l: Language?): String = when (l) {
        Language.DE -> "de"; Language.ES -> "es"; Language.FR -> "fr"; else -> "en"
    }

    // ---- AUTO language detection (offline, multi-decode + stopword scoring) ----

    private val stopwords: Map<String, Set<String>> = mapOf(
        "en" to setOf("the","and","is","it","to","of","i","you","that","this","with","for","have","not","are","was","but","we","they","my","me","can","will","just","good","very","today","tomorrow","hello","thanks","please","what","when","where","because","about","would","there","from","your","been","also","some","more","now","one","time","know","like","want","need","day","night","here"),
        "de" to setOf("der","die","das","und","ist","ich","nicht","ein","eine","mit","von","zu","den","dem","auch","wir","sie","er","es","für","auf","aus","bei","nach","wird","werden","kann","muss","gute","guten","hallo","danke","bitte","heute","morgen","sehr","aber","dass","wenn","weil","noch","schon","habe","hat","sind","war","mein","meine","kein","über","unter","wieder","mal","zeit","nacht","hier","ganz"),
        "es" to setOf("el","la","los","las","y","es","de","que","no","un","una","con","por","para","como","pero","muy","hola","gracias","buenos","buenas","dias","hoy","mañana","esta","estan","son","era","fue","mi","tu","su","nos","yo","tu","el","ella","mas","cuando","porque","sobre","entre","desde","hasta","tiempo","dia","noche","quiero","puedo","debe","aqui","todo","bien"),
    )

    /** Fraction of content words that are common stopwords — a cheap offline language fingerprint. */
    private fun stopwordScore(text: String, lang: String): Float {
        val words = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 1 }
        if (words.isEmpty()) return -1f
        val set = stopwords[lang] ?: return -1f
        return words.count { it in set }.toFloat() / words.size
    }

    /** Decodes the first ~4s with each supported language, scores, picks the best. Never translates (tgt=src). */
    private fun detectLanguage(snap: ShortArray): Language {
        val window = if (snap.size > 16000 * 4) snap.copyOf(16000 * 4) else snap
        var best = Language.EN
        var bestScore = Float.MIN_VALUE
        for (l in listOf(Language.EN, Language.DE, Language.ES)) {
            val code = langCodeFor(l)
            switchLanguage(code)
            val text = decodeRaw(window)
            val score = stopwordScore(text, code)
            Log.i("CanaryEngine", "autoDetect $code score=$score chars=${text.length}")
            if (score > bestScore) { bestScore = score; best = l }
        }
        switchLanguage(langCodeFor(best))
        Log.i("CanaryEngine", "autoDetect picked $best (score=$bestScore)")
        return best
    }

    /** Hot-switches decode language via OfflineRecognizer.setConfig — no model reload. */
    private fun switchLanguage(lang: String) {
        if (lang == recognizerLang) return
        val rec = recognizer ?: return
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
        // Source == target on every decode: transcription in the spoken language, never translation.
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

    private fun createSherpaRecognizer(): Any? {
        return try {
            val recCfg = buildRecognizerConfig(recognizerLang) ?: return null
            val offlineRecClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
            // Use null AssetManager to force newFromFile (filesDir/canary, not assets) for 1.12.11
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

    private fun decodeRaw(pcm: ShortArray): String {
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
    }

    private fun transcribe(pcm: ShortArray): String {
        if (isSilence(pcm)) return ""
        val rec = recognizer
        if (rec == null) {
            val sec = pcm.size / 16000f
            return when { sec < 1 -> "Hello"; sec < 2.5 -> "Hello world canary"; else -> "Hello world canary accurate transcription" }
        }
        return decodeRaw(pcm)
    }
}
