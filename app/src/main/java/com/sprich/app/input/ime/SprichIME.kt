package com.sprich.app.input.ime

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.sprich.app.core.audio.AudioCapture
import com.sprich.app.diagnostics.Diagnostics
import com.sprich.app.core.perf.LatencyTracker
import com.sprich.app.core.vad.Vad
import com.sprich.app.input.commands.SpokenEditingParser
import com.sprich.app.input.composition.CompositionManager
import com.sprich.app.input.lifecycle.DictationSession
import com.sprich.app.input.lifecycle.FieldSessionController
import com.sprich.app.input.lifecycle.SessionState
import com.sprich.app.input.lifecycle.StopReason
import com.sprich.app.input.lifecycle.UtteranceToken
import com.sprich.app.speech.api.EngineType
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.api.TranscriptionTask
import com.sprich.app.SprichApp
import com.sprich.app.core.perf.ThermalMonitor
import com.sprich.app.core.audio.UtteranceAudioCollector
import com.sprich.app.speech.LocalAsrRoute
import com.sprich.app.speech.LocalTranscriptionCoordinator
import com.sprich.app.speech.ResolvedUtteranceLanguage
import com.sprich.app.speech.canary.CanaryEngine
import com.sprich.app.speech.TranscriptionMode
import com.sprich.app.speech.UtterancePlan
import com.sprich.app.speech.TranscriptionPlan
import com.sprich.app.speech.RefinementPlan
import com.sprich.app.speech.LanguagePolicy
import com.sprich.app.speech.TranscriptionResult
import com.sprich.app.speech.TranscriptionSourceId
import com.sprich.app.speech.TranscriptionCoordinator
import com.sprich.app.speech.remote.RemoteSttConfig
import com.sprich.app.speech.remote.RemoteSttProvider
import com.sprich.app.speech.remote.OpenAiCompatibleSttProvider
import com.sprich.app.speech.remote.MockRemoteSttProvider
import com.sprich.app.speech.remote.DeadlinePolicy
import com.sprich.app.speech.refinement.RefinementMode
import com.sprich.app.speech.refinement.RefinementConfig
import com.sprich.app.speech.refinement.TranscriptRefinementProvider
import com.sprich.app.speech.refinement.OpenAiCompatibleRefinementProvider
import com.sprich.app.speech.refinement.RefinementValidator
import com.sprich.app.speech.refinement.MockRefinementProvider
import com.sprich.app.storage.ApiSecretStore
import com.sprich.app.storage.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import com.sprich.app.diagnostics.ReplayHarness
import com.sprich.app.input.typography.TypographyNormalizer

class SprichIME : InputMethodService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var prefs: Preferences
    private lateinit var latency: LatencyTracker
    private lateinit var session: DictationSession
    private lateinit var composition: CompositionManager
    private lateinit var audio: AudioCapture
    private lateinit var vad: Vad

    private lateinit var engine: CanaryEngine
    // Neutral authoritative PCM collector — single canonical source, independent of ASR engine.
    private val utteranceAudio = UtteranceAudioCollector(maxSamples = 16000 * 30)
    private var localCoordinator: LocalTranscriptionCoordinator? = null
    // Diagnostics for Auto-without-Canary gate
    private val canaryLoadAttempts = java.util.concurrent.atomic.AtomicLong(0)
    private val fastLoadAttempts = java.util.concurrent.atomic.AtomicLong(0)
    private val lidLoadAttempts = java.util.concurrent.atomic.AtomicLong(0)
    private var startJob: Job? = null
    private var engineJob: Job? = null
    @Volatile private var endpointJob: Job? = null
    private val sessionGeneration = AtomicLong(0L)
    private val utteranceActive = AtomicBoolean(false)
    private val endpointPending = AtomicBoolean(false)
    @Volatile private var lastVadState = Vad.State.SILENCE
    private var activeConfig = SpeechSessionConfig()
    @Volatile private var lastPartialText = ""

    // Single authoritative coordinator — owns field/session/utterance finalization
    private lateinit var fieldController: FieldSessionController
    private var currentFieldId: String? = null
    private val fieldGeneration = AtomicLong(0L)
    private val utteranceIdCounter = AtomicLong(0L)
    @Volatile private var currentUtteranceToken: UtteranceToken? = null
    // Bounded exactly-once history: keep last 128 utterance IDs to prevent unbounded growth while retaining safety for recent duplicates
    private val finalizedUtterances: MutableSet<Long> = java.util.Collections.synchronizedSet(object : LinkedHashSet<Long>() {
        override fun add(element: Long): Boolean {
            val added = super.add(element)
            // Evict oldest if over bound (128)
            if (size > 128) {
                val it = iterator()
                if (it.hasNext()) { it.next(); it.remove() }
            }
            return added
        }
    })
    @Volatile private var currentFieldTokenIcHash: Int = 0

    // Per-utterance PCM capture — engine-independent authoritative collector.
    // AudioCapture -> UtteranceAudioCollector -> immutable PendingUtterance.pcm -> transcription route
    // One utterance has one canonical frozen PCM snapshot independent of chosen ASR engine.
    // Kept for legacy fallback isolation checks; primary source is utteranceAudio.
    @Volatile private var frozenUtterancePcm: ShortArray? = null

    // --- Phase 0A+2: immutable active utterance descriptor — frozen at onset, Settings changes apply to NEXT utterance only.
    // Once speech onset occurs, route / language configuration / transcription mode / provider config revision / refinement mode must not change for that utterance.
    data class ActiveUtterance(
        val token: UtteranceToken,
        val localRoute: LocalAsrRoute,
        val speechConfig: SpeechSessionConfig,
        val plan: UtterancePlan,
    )
    @Volatile private var activeUtterance: ActiveUtterance? = null

    // Prepared final action — command vs text separation (P0-7: refinement must never gain command authority)
    sealed interface PreparedFinalAction {
        data class Text(val text: String, val resolved: ResolvedUtteranceLanguage) : PreparedFinalAction
        data class DeleteLast(val token: UtteranceToken) : PreparedFinalAction
        data class DeleteSentence(val token: UtteranceToken) : PreparedFinalAction
    }

    // Overlapping utterance queue — immutable per-utterance snapshots, serialized finalization actor
    // Invariant: one spoken utterance → one immutable PCM snapshot → one final decode → one deterministic post-processing pass → one editor commit
    // Active capture and pending finalizations are distinct: utterance N+1 can be captured while N decodes without mutation.
    data class PendingUtterance(
        val token: UtteranceToken,
        val pcm: ShortArray,
        val config: SpeechSessionConfig,
        val route: LocalAsrRoute,
        val plan: UtterancePlan,
        val pushedSamples: Long,
        val reason: StopReason,
        val endpointTimestampNanos: Long,
    ) {
        // Legacy constructor for tests that still use 5-arg shape (route/config only)
        constructor(
            token: UtteranceToken,
            pcm: ShortArray,
            config: SpeechSessionConfig,
            route: LocalAsrRoute,
            pushedSamples: Long,
            reason: StopReason,
            endpointTimestampNanos: Long,
        ) : this(token, pcm, config, route, UtterancePlan(TranscriptionPlan.Local(route), RefinementPlan.Off, config), pushedSamples, reason, endpointTimestampNanos)
    }
    // Single authoritative long-lived finalization actor — exactly one consumer, FIFO, no worker start/stop race, genuinely bounded.
    // Bounded capacity 4 ensures PCM retention bounded (max ~4 utterances). Overload is explicit via rejected/suppressed counters, not silent loss.
    private val maxPendingQueueDepth = 4
    private val pendingChannel = Channel<PendingUtterance>(capacity = maxPendingQueueDepth)
    private val queueDepth = AtomicInteger(0)
    private val pendingQueuePeak = AtomicLong(0)
    private val finalizationQueueOverflows = AtomicLong(0)
    private val catchingUpSuppressedOnsets = AtomicLong(0)
    private val catchingUpRejectedOnsets = AtomicLong(0)
    @Volatile var catchingUp = false
        private set
    private var finalizationActorJob: Job? = null
    @Volatile var lastQueueDepth: Int = 0
        private set
    // USER_STOP serialization — freeze and enqueue after earlier, stop after queue drains (FIFO lane)
    @Volatile private var stopRequested = false
    @Volatile private var stopRequestedGeneration: Long = 0L
    // Backpressure: speech episode suppression — mark entire VAD episode suppressed while unavailable
    @Volatile private var suppressEpisode = false

    // Exactly-once diagnostic counters (debug/test builds, transcript-free)
    @Volatile var finalizationClaims: Long = 0
        private set
    @Volatile var finalCommitCount: Long = 0
        private set
    @Volatile var staleCallbackDrops: Long = 0
        private set
    @Volatile var nativeDecodeStarts: Long = 0
        private set
    // Truthful metrics separated by path (P0-12)
    @Volatile var localNativeDecodeStarts: Long = 0
        private set
    @Volatile var remoteTranscriptionStarts: Long = 0
        private set
    @Volatile var refinementStarts: Long = 0
        private set
    // Privacy-safe pipeline counters for physical-device triage (no audio/transcript).
    private var pipelineChunkCount = 0L
    private var pipelineSampleCount = 0L
    private var pipelinePushedSampleCount = 0L
    private var pipelineStartElapsed = 0L
    private var instantMode: Boolean = false
    // Single canonical language state — SpeechLanguage is authoritative; Language is derived synchronously.
    // Collecting both Language and SpeechLanguage independently causes split-brain (DE vs EN). Only collect SpeechLanguage.
    private var speechLanguage: SpeechLanguage = SpeechLanguage.Auto
    private val language: Language get() = speechLanguage.toLegacyLanguage()
    private var commandsEnabled: Boolean = true
    private var isPasswordField: Boolean = false
    private lateinit var vocabRepo: com.sprich.app.vocab.VocabRepository
    private val vocabStore get() = if (::vocabRepo.isInitialized) vocabRepo.store() else com.sprich.app.vocab.PersonalVocabStore()
    private var hapticsEnabled: Boolean = true
    // New product modes
    private var transcriptionMode: TranscriptionMode = TranscriptionMode.ON_DEVICE
    private var refinementMode: RefinementMode = RefinementMode.OFF
    private var sttProviderId: String = "openai-compatible"
    private var sttBaseUrlState: String = ""
    private var sttModelState: String = "whisper-large-v3"
    private var sttDeadlineMsState: Long = 3500L
    private var refinementBaseUrlState: String = ""
    private var refinementModelState: String = ""
    private var refinementDeadlineMsState: Long = 1000L
    private var personalVocabHintEnabled: Boolean = false
    // In-memory snapshots for audio-hot-path non-blocking plan construction (P1-15)
    private var sttCredentialRefState: String = "stt_default"
    private var refinementProviderIdState: String = "openai-compatible"
    private var refinementCredentialRefState: String = "refine_default"
    private val apiSecretStore by lazy { ApiSecretStore(this) }
    private val sharedHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
    private var transcriptionCoordinator: TranscriptionCoordinator? = null
    private var refinementProvider: TranscriptRefinementProvider? = null
    private val thermalMonitor = ThermalMonitor { temp -> Log.w("SprichIME", "thermal throttle $temp°C") }
    // Swipe-to-delete state (right-to-left deletes words, hold repeats accelerating; left-to-right undoes)
    private var downX = 0f
    private var downY = 0f
    private var isSwipeDelete = false
    private var isSwipeUndo = false
    private var deleteRepeatJob: Job? = null
    private val undoStack = ArrayDeque<String>(10)
    private val touchSlop by lazy { android.view.ViewConfiguration.get(this).scaledTouchSlop }
    // Liquid visual state driven by mic RMS
    @Volatile private var lastRms = 0f
    private var lastVisualUpdateElapsed = 0L
    private var liquidBg: android.graphics.drawable.GradientDrawable? = null
    private var glowView: View? = null
    private var glowBg: android.graphics.drawable.GradientDrawable? = null
    private var auraView: View? = null
    private var pillBgRef: android.graphics.drawable.GradientDrawable? = null
    // Production Automatic: Tiny LID (Whisper Tiny 98M, per-utterance SLID) → FastConformer CTC 126M (implicit EN-DE-ES-FR)
    // Accurate explicit: Canary 180M Flash INT8 (fixed EN/DE/ES/FR)
    private val lidEngine by lazy {
        com.sprich.app.speech.lid.WhisperLidEngine(this, (application as SprichApp).let { com.sprich.app.models.manager.ModelManager(it) })
    }
    // FastConformer CTC EN-DE-ES-FR 14288 INT8 — primary for Automatic, not fallback
    private val fastConformerEngine by lazy {
        com.sprich.app.speech.fastconformer.FastConformerEngine(this)
    }

    // Views — native View IME, full-bar tappable magical
    private var statusText: TextView? = null
    private var dotView: View? = null
    private var micContainer: View? = null
    private var rootView: View? = null
    private var waveform: LinearLayout? = null
    private var waveformBars: List<View> = emptyList()
    private var waveformJob: Job? = null
    private var pulseAnimator: android.animation.ValueAnimator? = null

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = Preferences(this)
            vocabRepo = com.sprich.app.vocab.VocabRepository(this, prefs)
            latency = LatencyTracker()
            session = DictationSession(latency)
            composition = CompositionManager()
            fieldController = FieldSessionController(session, composition)
            audio = AudioCapture(ringSeconds = 30)
            vad = Vad()
            engine = (application as SprichApp).fastEngine
            // Neutral collector requires no engine ownership; coordinator will be created after engines lazy init
            scope.launch { try { vocabRepo.load() } catch (_: Exception) {} }

            // Observe prefs — catch DataStore IOException via prefs flows already handle it
            scope.launch {
                try { prefs.instantMode.collect { instantMode = it } } catch (e: Exception) { Log.w("SprichIME", "instantMode collect fail", e) }
            }
            // Single canonical collector — SpeechLanguage only. Language derived via toLegacyLanguage().
            scope.launch {
                try { prefs.speechLanguage.collect { speechLanguage = it } } catch (e: Exception) { Log.w("SprichIME", "speechLanguage collect fail", e) }
            }
            scope.launch {
                try { prefs.commands.collect { commandsEnabled = it } } catch (e: Exception) { Log.w("SprichIME", "commands collect fail", e) }
            }
            scope.launch {
                try { prefs.haptics.collect { hapticsEnabled = it } } catch (e: Exception) { Log.w("SprichIME", "haptics collect fail", e) }
            }
            scope.launch {
                try {
                    prefs.engineType.collect { requested ->
                        Log.i("SprichIME", "engineType observed requested=$requested (no force, route determined by speechLanguage)")
                    }
                } catch (e: Exception) { Log.w("SprichIME", "engineType collect fail", e) }
            }
            // Observe new typed product modes — in-memory snapshots for non-blocking plan construction (P1-15)
            scope.launch { try { prefs.transcriptionMode.collect { transcriptionMode = it; Log.i("SprichIME", "transcriptionMode=$it") } } catch (e: Exception) { Log.w("SprichIME", "transcriptionMode collect fail", e) } }
            scope.launch { try { prefs.refinementMode.collect { refinementMode = it } } catch (e: Exception) { Log.w("SprichIME", "refinementMode collect fail", e) } }
            scope.launch { try { prefs.sttProviderId.collect { sttProviderId = it } } catch (e: Exception) {} }
            scope.launch { try { prefs.sttBaseUrl.collect { sttBaseUrlState = it } } catch (e: Exception) {} }
            scope.launch { try { prefs.sttModel.collect { sttModelState = it } } catch (e: Exception) {} }
            scope.launch { try { prefs.sttDeadlineMs.collect { sttDeadlineMsState = it } } catch (e: Exception) {} }
            scope.launch { try { prefs.sttCredentialRef.collect { sttCredentialRefState = it } } catch (e: Exception) {} }
            scope.launch { try { prefs.refinementProviderId.collect { refinementProviderIdState = it } } catch (e: Exception) {} }
            scope.launch { try { prefs.refinementCredentialRef.collect { refinementCredentialRefState = it } } catch (e: Exception) {} }
            scope.launch { try { prefs.refinementBaseUrl.collect { refinementBaseUrlState = it } } catch (e: Exception) {} }
            scope.launch { try { prefs.refinementModel.collect { refinementModelState = it } } catch (e: Exception) {} }
            scope.launch { try { prefs.refinementDeadlineMs.collect { refinementDeadlineMsState = it } } catch (e: Exception) {} }
            scope.launch { try { prefs.personalVocabHintEnabled.collect { personalVocabHintEnabled = it } } catch (e: Exception) {} }
            // One-time legacy credential migration (P0-3 fail-closed)
            scope.launch { try { com.sprich.app.storage.LegacyApiCredentialMigrator.migrateIfNeeded(prefs, ApiSecretStore(this@SprichIME)) } catch (e: Exception) { Log.w("SprichIME", "legacy migration failed", e) } }
            // Preload: keep onCreate cheap — only warm local engine AFTER mode is resolved. API_PRIMARY stays local-cold until remote failure.
            // Do not load any native model here if API_PRIMARY, even before first field focus. Wait for mode resolution.
            scope.launch {
                try {
                    val mm = try { com.sprich.app.models.manager.ModelManager(this@SprichIME) } catch (_: Exception) { null }
                    // Wait for persisted mode — cheap suspend, no blocking DataStore on audio path
                    val persistedMode = try { prefs.transcriptionMode.first() } catch (_: Exception) { TranscriptionMode.ON_DEVICE }
                    if (persistedMode == TranscriptionMode.API_PRIMARY) {
                        Log.i("SprichIME", "onCreate: API_PRIMARY persisted — staying local-cold (0 loads) until remote failure")
                        if (localCoordinator == null) {
                            localCoordinator = LocalTranscriptionCoordinator(lidEngine, fastConformerEngine, engine)
                        }
                        return@launch
                    }
                    val st = try { prefs.speechLanguage.first() } catch (_: Exception) { com.sprich.app.speech.api.SpeechLanguage.Auto }
                    val route = determineRoute(st)
                    when (route) {
                        is LocalAsrRoute.AutomaticFastConformer -> {
                            if (mm?.isAutomaticReady() == true) {
                                val lidRes = lidEngine.load().also { lidLoadAttempts.incrementAndGet() }
                                if (lidRes.isSuccess) Log.i("SprichIME", "Auto preload after mode resolved: Tiny LID success")
                                val fastRes = fastConformerEngine.load().also { fastLoadAttempts.incrementAndGet() }
                                if (fastRes.isSuccess) Log.i("SprichIME", "Auto preload after mode resolved: FastConformer success")
                            } else {
                                Log.i("SprichIME", "Auto preload skipped — Automatic not ready (needs Tiny LID + FastConformer)")
                            }
                        }
                        is LocalAsrRoute.AccurateCanary -> {
                            val res = engine.load().also { canaryLoadAttempts.incrementAndGet() }
                            if (res.isSuccess) Log.i("SprichIME", "Accurate preload after mode resolved: Canary success for ${route.language}")
                            else Log.i("SprichIME", "Accurate preload not ready for ${route.language}")
                        }
                    }
                    // Init coordinator (lazy engines already available)
                    if (localCoordinator == null) {
                        localCoordinator = LocalTranscriptionCoordinator(lidEngine, fastConformerEngine, engine)
                    }
                } catch (e: Exception) { Log.w("SprichIME", "selective preload failed", e) }
            }
            // Start single long-lived finalization actor (no lost-wakeup race)
            startFinalizationActor()

            // Observe session for UI — update dot/text without Compose
            scope.launch {
                session.state.collect { state ->
                    val active = state is SessionState.Preparing || state is SessionState.Listening ||
                        state is SessionState.Speech || state is SessionState.Finalizing
                    when (state) {
                        is SessionState.Preparing -> {
                            updateImeUi(true)
                            statusText?.text = "Preparing…"
                            (statusText?.tag as? TextView)?.text = "Loading the local speech model"
                        }
                        is SessionState.Finalizing -> {
                            updateImeUi(true)
                            statusText?.text = "Transcribing…"
                            (statusText?.tag as? TextView)?.text = "Words will appear at the cursor"
                        }
                        is SessionState.Error -> Unit // failSession owns the actionable message.
                        else -> updateImeUi(active)
                    }
                    if (active) { try { thermalMonitor.start() } catch (_: Exception) {} }
                    else { try { thermalMonitor.stop() } catch (_: Exception) {} }
                }
            }
        } catch (e: Exception) {
            Log.e("SprichIME", "onCreate failed", e)
        }
    }

    private fun isDark(): Boolean = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    override fun onCreateInputView(): View {
        return try {
            val dark = isDark()
            // Outer container — transparent, handles gesture insets, centers liquid pill away from corners
            val outer = FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                // Handle navigation bar / gesture inset so pill never sits on system bar
                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                    val navBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
                    val imeBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                    val bottomInset = maxOf(navBottom, imeBottom)
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottomInset + dp(12))
                    insets
                }
                setPadding(dp(0), dp(8), dp(0), dp(12))
            }

            // Liquid pill — smaller, centered, away from corners, prevents cut-off in small apps
            val pillBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                val fill = if (dark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
                setColor(fill)
                setStroke(dp(1), if (dark) Color.parseColor("#2A2A2A") else Color.parseColor("#E8E8E8"))
            }
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = pillBg
                elevation = dp(4).toFloat()
                setPadding(dp(16), dp(8), dp(16), dp(8))
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                    gravity = Gravity.CENTER
                    marginStart = dp(24)
                    marginEnd = dp(24)
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { toggleDictation() }
            }

            // Minimal bar — no mic/keyboard icons per user request; just centered text + passionate liquid animation
            val textBlock = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                isClickable = false
            }
            val statusColor = if (dark) Color.parseColor("#F5F5F3") else Color.parseColor("#111111")
            val hintColor = if (dark) Color.parseColor("#A0A0A0") else Color.parseColor("#8A8A8A")
            val status = TextView(this).apply {
                text = "Tap to speak"
                setTextColor(statusColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
                isSingleLine = false
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val hint = TextView(this).apply {
                text = "Words appear at cursor"
                setTextColor(hintColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                gravity = Gravity.CENTER
                isSingleLine = false
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                alpha = 0.9f
            }
            // Passionate liquid bar — small 36dp gradient line + glow layer, colorful by RMS
            val wave = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                visibility = View.INVISIBLE
                clipChildren = false
                clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)).apply {
                    topMargin = dp(6)
                }
            }
            // Glow: same gradient behind the bar, scaled up — neon halo, no blur cost
            val glow = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(3)).apply {
                    gravity = Gravity.CENTER
                }
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(2).toFloat()
                    colors = gradientForRms(0f)
                    orientation = GradientDrawable.Orientation.LEFT_RIGHT
                }
                background = bg
                alpha = 0f
                pivotX = 0.5f
                pivotY = 0.5f
            }
            glowBg = glow.background as? GradientDrawable
            val liquidBar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(3)).apply {
                    gravity = Gravity.CENTER
                }
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(2).toFloat()
                    colors = gradientForRms(0f)
                    orientation = GradientDrawable.Orientation.LEFT_RIGHT
                }
                background = bg
                alpha = 0.95f
                scaleX = 1f
            }
            liquidBg = liquidBar.background as? GradientDrawable
            glowView = glow
            wave.addView(glow)
            wave.addView(liquidBar)
            textBlock.addView(status)
            textBlock.addView(hint)
            textBlock.addView(wave)

            // Aura: soft radial halo behind the whole pill, breathes with mic energy
            val aura = View(this).apply {
                val bg = GradientDrawable().apply {
                    gradientType = GradientDrawable.RADIAL_GRADIENT
                    setGradientCenter(0.5f, 0.5f)
                    colors = intArrayOf(0x59FF4D76, 0x00FF4D76)
                    setGradientRadius(320f)
                }
                background = bg
                alpha = 0f
                pivotX = 0.5f
                pivotY = 0.5f
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                    gravity = Gravity.CENTER
                    marginStart = dp(16)
                    marginEnd = dp(16)
                }
            }
            auraView = aura

            // Touch: tap toggles dictation, swipe right-to-left deletes words (hold repeats), left-to-right undoes.
            pill.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = ev.x; downY = ev.y
                        isSwipeDelete = false; isSwipeUndo = false
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = ev.x - downX
                        if (!isSwipeDelete && !isSwipeUndo && dx <= -touchSlop) {
                            isSwipeDelete = true
                            vibrateHeavy()
                            deleteLastWord()
                            startDeleteRepeat()
                        } else if (!isSwipeDelete && !isSwipeUndo && dx >= touchSlop && undoStack.isNotEmpty()) {
                            isSwipeUndo = true
                            vibrateTick()
                            undoLastDelete()
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        stopDeleteRepeat()
                        val wasSwipe = isSwipeDelete || isSwipeUndo
                        isSwipeDelete = false; isSwipeUndo = false
                        if (!wasSwipe) toggleDictation()
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        stopDeleteRepeat()
                        isSwipeDelete = false; isSwipeUndo = false
                        true
                    }
                    else -> false
                }
            }

            pillBgRef = pillBg
            // Allow glow/aura to draw beyond bounds
            outer.clipChildren = false
            outer.clipToPadding = false
            pill.clipChildren = false
            pill.clipToPadding = false
            textBlock.clipChildren = false
            textBlock.clipToPadding = false
            pill.addView(textBlock)
            outer.addView(aura)   // behind the pill
            outer.addView(pill)

            // keep refs for updates — mic/keyboard removed per request; keep dummy refs for updateImeUi compatibility
            statusText = status
            statusText?.tag = hint
            dotView = liquidBar
            micContainer = pill
            rootView = outer
            waveform = wave
            waveformBars = listOf(liquidBar)

            updateImeUi(false)

            outer
        } catch (e: Exception) {
            Log.e("SprichIME", "onCreateInputView failed, fallback", e)
            TextView(this).apply {
                text = "Sprich — tap to speak"
                setPadding(dp(16), dp(20), dp(16), dp(24))
                gravity = Gravity.CENTER
                setOnClickListener { toggleDictation() }
            }
        }
    }

    private fun updateImeUi(isListening: Boolean) {
        try {
            val dark = isDark()
            val hint = statusText?.tag as? TextView
            val statusColor = if (dark) Color.parseColor("#F5F5F3") else Color.parseColor("#111111")
            if (isListening) {
                statusText?.text = "Listening…"
                hint?.text = "Tap to stop"
                statusText?.setTextColor(statusColor)
                micContainer?.contentDescription = "Stop listening"
                micContainer?.animate()?.cancel()
                micContainer?.scaleX = 1f; micContainer?.scaleY = 1f
                // Extremely subtle liquid press — 2% scale, no overshoot, no wobble
                micContainer?.animate()?.scaleX(1.02f)?.scaleY(1.02f)?.setDuration(140)?.setInterpolator(android.view.animation.DecelerateInterpolator())?.withEndAction {
                    micContainer?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(180)?.start()
                }?.start()
                waveform?.visibility = View.VISIBLE
                waveform?.alpha = 0.95f
                // Glow fades in softly when listening starts
                glowView?.animate()?.cancel()
                glowView?.animate()?.alpha(0.12f)?.setDuration(300)?.start()
                startWaveform()
                pulseAnimator?.cancel()
                pulseAnimator = android.animation.ValueAnimator.ofFloat(0.9f, 1f).apply {
                    duration = 1100
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    addUpdateListener { anim -> waveform?.alpha = anim.animatedValue as Float }
                    start()
                }
            } else {
                statusText?.text = "Tap to speak"
                hint?.text = "Words appear at cursor"
                statusText?.setTextColor(statusColor)
                micContainer?.contentDescription = "Tap to speak"
                micContainer?.animate()?.cancel()
                micContainer?.scaleX = 1f; micContainer?.scaleY = 1f
                waveform?.visibility = View.INVISIBLE
                waveform?.alpha = 0f
                // Reset glow, aura and stroke to calm state
                glowView?.animate()?.cancel()
                glowView?.alpha = 0f
                glowView?.scaleX = 1.6f; glowView?.scaleY = 1.6f
                auraView?.animate()?.cancel()
                auraView?.alpha = 0f
                lastRms = 0f
                pillBgRef?.setStroke(dp(1), if (dark) Color.parseColor("#2A2A2A") else Color.parseColor("#E8E8E8"))
                pulseAnimator?.cancel()
                stopWaveform()
            }
        } catch (_: Exception) {}
    }

    private fun startWaveform() {
        waveformJob?.cancel()
        waveformJob = scope.launch {
            val bars = waveformBars
            // Base breath; live speech energy overrides via updateLiquidVisual (RMS-driven wow).
            while (isActive) {
                try {
                    if (lastRms > 0.0012f) {
                        updateLiquidVisual(lastRms)
                    } else {
                        bars.forEach { bar ->
                            val targetScale = 0.75f + (Math.random().toFloat() * 0.5f)
                            bar.animate().cancel()
                            bar.animate().scaleX(targetScale.coerceIn(0.7f, 1.4f)).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                            bar.alpha = 0.85f + (Math.random().toFloat() * 0.15f)
                        }
                        // Glow breathes softly with the bar while listening in silence
                        glowView?.let { g ->
                            g.animate().cancel()
                            g.alpha = 0.07f + (Math.random().toFloat() * 0.09f)
                        }
                    }
                    delay(220)
                } catch (_: Exception) { delay(220) }
            }
        }
    }
    private fun stopWaveform() {
        try { waveformJob?.cancel() } catch (_: Exception) {}
        waveformJob = null
        try {
            waveformBars.forEach { bar ->
                bar.animate().cancel()
                bar.scaleY = 1f
                bar.alpha = 0.85f
            }
        } catch (_: Exception) {}
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        try {
            super.onStartInput(info, restarting)
            // Field ownership: every focus change gets a new generation; InputConnection from previous field becomes stale.
            if (restarting) {
                // INPUT_RESTARTED must not later insert old text into the restarted field.
                if (isDictationRunning()) stopDictation(StopReason.INPUT_RESTARTED)
            } else {
                if (isDictationRunning()) stopDictation(StopReason.FIELD_LOST)
                // New field — bump field generation and inform coordinator. Coordinator owns sessionId.
                val newFieldId = "field_${fieldGeneration.incrementAndGet()}_${info?.packageName ?: "unk"}"
                currentFieldId = newFieldId
                try {
                    val selStart = try { info?.initialSelStart ?: -1 } catch (_: Exception) { -1 }
                    val selEnd = try { info?.initialSelEnd ?: -1 } catch (_: Exception) { -1 }
                    fieldController.onFieldFocused(newFieldId, selStart, selEnd)
                } catch (_: Exception) {}
                currentFieldTokenIcHash = try { currentInputConnection?.hashCode() ?: 0 } catch (_: Exception) { 0 }
            }
            isPasswordField = isPassword(info)
            latency.mark("onStartInput")
            scope.launch {
                try {
                    // P0-9: API_PRIMARY must NOT preload local ASR on field focus
                    if (transcriptionMode == TranscriptionMode.API_PRIMARY) {
                        // If local models were resident from prior local mode, unload when idle
                        if (queueDepth.get() == 0) {
                            try { if (engine.isLoaded()) engine.unload() } catch (_: Exception) {}
                            try { if (fastConformerEngine.isLoaded()) fastConformerEngine.unload() } catch (_: Exception) {}
                            try { if (lidEngine.isLoaded()) lidEngine.unload() } catch (_: Exception) {}
                        }
                        return@launch
                    }
                    val mm = try { com.sprich.app.models.manager.ModelManager(this@SprichIME) } catch (_: Exception) { null }
                    val route = determineRoute(speechLanguage)
                    when (route) {
                        is LocalAsrRoute.AutomaticFastConformer -> {
                            if (mm?.isAutomaticReady() == true) {
                                lidEngine.load()
                                fastConformerEngine.load()
                            }
                        }
                        is LocalAsrRoute.AccurateCanary -> {
                            val r = engine.load(); if (r.isSuccess) canaryLoadAttempts.incrementAndGet()
                        }
                    }
                } catch (_: Exception) {}
            }
            if (isPasswordField) {
                Log.i("SprichIME", "password field, silent")
                stopDictation(StopReason.PASSWORD_FIELD)
                return
            }
            composition.reset()
            frozenUtterancePcm = null
            try { utteranceAudio.clear() } catch (_: Exception) {}
            try { engine.clearUtteranceCapture() } catch (_: Exception) {}
            // Keep engine ring clear for partials when in Accurate mode, but collector is authoritative
            try { fastConformerEngine.clearUtteranceCapture() } catch (_: Exception) {}
            currentUtteranceToken = null
            activeUtterance = null
            utteranceActive.set(false)
            endpointPending.set(false)
            undoStack.clear()
            if (instantMode) {
                startJob?.cancel()
                startJob = scope.launch {
                    try { delay(40); startDictationIfNeeded() } catch (e: CancellationException) { throw e }
                    catch (t: Throwable) { Log.e("SprichIME", "instant start failed", t) }
                }
            }
        } catch (e: Exception) {
            Log.e("SprichIME", "onStartInput failed", e)
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        scope.launch {
            try {
                if (transcriptionMode == TranscriptionMode.API_PRIMARY) {
                    if (queueDepth.get() == 0) {
                        try { if (engine.isLoaded()) engine.unload() } catch (_: Exception) {}
                        try { if (fastConformerEngine.isLoaded()) fastConformerEngine.unload() } catch (_: Exception) {}
                        try { if (lidEngine.isLoaded()) lidEngine.unload() } catch (_: Exception) {}
                    }
                    return@launch
                }
                val mm = try { com.sprich.app.models.manager.ModelManager(this@SprichIME) } catch (_: Exception) { null }
                val route = determineRoute(speechLanguage)
                when (route) {
                    is LocalAsrRoute.AutomaticFastConformer -> {
                        if (mm?.isAutomaticReady() == true) {
                            lidEngine.load()
                            fastConformerEngine.load()
                        }
                    }
                    is LocalAsrRoute.AccurateCanary -> {
                        engine.load().also { if (it.isSuccess) canaryLoadAttempts.incrementAndGet() }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        try { stopDictation(StopReason.WINDOW_HIDDEN) } catch (_: Exception) {}
        try { thermalMonitor.stop() } catch (_: Exception) {}
    }

    override fun onFinishInput() {
        try {
            // FIELD_LOST must discard speculative partial — never commit it
            try { composition.discardPartial(currentInputConnection) } catch (_: Exception) {}
            // FIELD_LOST must make every old callback permanently unable to insert.
            try { currentFieldId?.let { fieldController.onFieldLost(it) } } catch (_: Exception) {}
            currentFieldId = null
            stopDictation(StopReason.FIELD_LOST)
            super.onFinishInput()
        } catch (e: Exception) {
            Log.e("SprichIME", "onFinishInput failed", e)
        }
    }

    override fun onDestroy() {
        val t0 = android.os.SystemClock.elapsedRealtime()
        // Synchronous cheap invalidation — no runBlocking on main, ≈ negligible wall time
        try { startJob?.cancel() } catch (_: Exception) {}
        startJob = null
        try { engineJob?.cancel() } catch (_: Exception) {}
        engineJob = null
        // Invalidate all generations to make stale callbacks drop immediately
        try { sessionGeneration.incrementAndGet() } catch (_: Exception) {}
        try { fieldGeneration.incrementAndGet() } catch (_: Exception) {}
        // Stop accepting new finalizations
        try { pendingChannel.close() } catch (_: Exception) {}
        // Signal audio stop synchronously without join (join moved off-main)
        try { audio.requestStop() } catch (_: Exception) {}
        // Cancel network/work without blocking
        try { scope.coroutineContext.cancelChildren() } catch (_: Exception) {}

        // Off-main deterministic cleanup — never blocks main, no use-after-free
        val cleanupScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        cleanupScope.launch {
            val ct0 = android.os.SystemClock.elapsedRealtime()
            try { kotlinx.coroutines.withTimeoutOrNull(800) { finalizationActorJob?.cancelAndJoin() } } catch (_: Exception) {}
            try { audio.awaitStop(300) } catch (_: Exception) {}
            try { audio.release() } catch (_: Exception) {}
            try { kotlinx.coroutines.withTimeoutOrNull(1000) { lidEngine.unload() } } catch (_: Exception) {}
            try { kotlinx.coroutines.withTimeoutOrNull(1000) { fastConformerEngine.unload() } } catch (_: Exception) {}
            try { kotlinx.coroutines.withTimeoutOrNull(800) { engine.unload() } } catch (_: Exception) {}
            try { utteranceAudio.clear() } catch (_: Exception) {}
            try { composition.discardPartial(null) } catch (_: Exception) {}
            try { fieldController.cancelActive() } catch (_: Exception) {}
            try { scope.cancel() } catch (_: Exception) {}
            android.util.Log.i("SprichIME", "off-main cleanup complete wall=${android.os.SystemClock.elapsedRealtime()-ct0}ms total=${android.os.SystemClock.elapsedRealtime()-t0}ms")
        }
        android.util.Log.i("SprichIME", "onDestroy main-thread wall=${android.os.SystemClock.elapsedRealtime()-t0}ms (cleanup off-main)")
        super.onDestroy()
    }

    private fun isPassword(info: EditorInfo?): Boolean {
        if (info == null) return false
        val t = info.inputType
        val variation = t and EditorInfo.TYPE_MASK_VARIATION
        return variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            (t and EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_NUMBER && variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun currentInputInfo(): EditorInfo? = try { currentInputEditorInfo } catch (_: Exception) { null }

    private fun toggleDictation() {
        try {
            if (isDictationRunning()) {
                stopDictation(StopReason.USER_STOP)
            } else {
                startJob?.cancel()
                startJob = scope.launch { startDictationIfNeeded() }
            }
        } catch (e: Exception) { Log.e("SprichIME", "toggle failed", e) }
    }

    private fun isDictationRunning(): Boolean = when (session.state.value) {
        is SessionState.Preparing,
        is SessionState.Listening,
        is SessionState.Speech,
        is SessionState.Finalizing -> true
        else -> false
    }

    private suspend fun startDictationIfNeeded() {
        try {
            if (isPasswordField) {
                statusText?.text = "Password field"
                (statusText?.tag as? TextView)?.text = "Dictation disabled here"
                return
            }
            // Language handling: Automatic = Tiny LID + FastConformer (no Canary), Accurate = Canary explicit
            // Automatic requires BOTH Tiny LID and FastConformer (isAutomaticReady) when ON_DEVICE or LOCAL_API_FALLBACK.
            // API_PRIMARY must NOT require local models when provider supports Automatic (phase 4).
            val requiresLocalForAuto = transcriptionMode == TranscriptionMode.ON_DEVICE || transcriptionMode == TranscriptionMode.LOCAL_API_FALLBACK
            if (speechLanguage is SpeechLanguage.Auto && requiresLocalForAuto) {
                // Winner 2026-09-02: Automatic = Tiny LID (98M) + FastConformer 126M (Architecture B). Both required.
                val mmForCheck = try { com.sprich.app.models.manager.ModelManager(this) } catch (_: Exception) { null }
                val lidReady = try { mmForCheck?.isWhisperTinyReady() == true } catch (_: Exception) { false }
                val fastReady = try { mmForCheck?.isFastConformerReady() == true } catch (_: Exception) { false }
                if (!lidReady) {
                    Log.w("SprichIME", "Auto language requested but Tiny LID not downloaded — explicit selection required.")
                    try { session.error("language auto not supported without LID") } catch (_: Exception) {}
                    statusText?.text = "Tap to choose language"
                    (statusText?.tag as? TextView)?.text = "Open Sprich app → Settings → Language (EN/DE/ES/FR) or download Tiny LID (98M)"
                    try { vibrateTick() } catch (_: Exception) {}
                    writeDiagnostics("blocked Auto (no LID) resolved=${activeConfig.resolvedLanguageTag()} speechLanguage=$speechLanguage")
                    return
                }
                if (!fastReady) {
                    Log.w("SprichIME", "Auto (winner FastConformer) requested but FastConformer 126M not downloaded — download required.")
                    try { session.error("auto not supported without FastConformer") } catch (_: Exception) {}
                    statusText?.text = "Tap to choose language"
                    (statusText?.tag as? TextView)?.text = "Open Sprich app → Settings → Download FastConformer 126M (or Accurate Canary)"
                    try { vibrateTick() } catch (_: Exception) {}
                    writeDiagnostics("blocked Auto (no FastConformer) speechLanguage=$speechLanguage")
                    return
                }
                Log.i("SprichIME", "Auto via Tiny LID per-utterance + FastConformer 126M (winner) — proceeding, LID will detect EN/DE/ES/FR, FastConformer will transcribe (RTF 0.038)")
                try { lidEngine.load() } catch (_: Exception) {}
                try { fastConformerEngine.load() } catch (_: Exception) {}
            } else if (speechLanguage is SpeechLanguage.Auto && transcriptionMode == TranscriptionMode.API_PRIMARY) {
                Log.i("SprichIME", "API_PRIMARY with Auto — skipping local LID/FastConformer gate, provider handles language (Phase 4 independence)")
                // Local fallback will be loaded lazily on remote failure if available
            }
            if (session.state.value is SessionState.Listening || session.state.value is SessionState.Speech) return
            val permissionGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.i("SprichIME", "permission RECORD_AUDIO granted=$permissionGranted inputType=${currentInputConnection?.let { try { currentInputInfo()?.inputType } catch (_: Exception) { -1 } } ?: -1}")
            if (!permissionGranted) {
                Log.w("SprichIME", "RECORD_AUDIO not granted, abort dictation")
                try { session.error("mic permission") } catch (_: Exception) {}
                statusText?.text = "Mic permission needed"
                (statusText?.tag as? TextView)?.text = "Grant in Sprich app → Settings"
                // Vibrate error
                try { vibrateTick() } catch (_: Exception) {}
                return
            }
            val generation = sessionGeneration.incrementAndGet()
            pipelineChunkCount = 0L; pipelineSampleCount = 0L; pipelinePushedSampleCount = 0L; pipelineStartElapsed = android.os.SystemClock.elapsedRealtime()
            Log.i("SprichIME", "startDictation generation=$generation isLoaded=${engine.isLoaded()} state=${session.state.value::class.simpleName} sessionId=${session.sessionId} field=$currentFieldId")
            // Single authoritative session creation via FieldSessionController. Do NOT call session.start() directly
            // here if a field session already exists — that would desync FieldSessionController's currentSessionId.
            if (!session.requireActive() || !fieldController.isCurrentSession(session.sessionId)) {
                val fieldId = currentFieldId ?: run {
                    val newId = "field_${fieldGeneration.incrementAndGet()}_auto"
                    currentFieldId = newId
                    newId
                }
                val sid = try { fieldController.onFieldFocused(fieldId, -1, -1) } catch (_: Exception) { session.start() }
                Log.i("SprichIME", "field session ensured sid=$sid gen=$generation field=$fieldId")
            } else {
                Log.i("SprichIME", "reusing field session sid=${session.sessionId} gen=$generation")
            }
            statusText?.text = "Loading speech model…"
            (statusText?.tag as? TextView)?.text = "First start can take a moment"

            // Route-aware loading — only load required engines, do NOT load Canary for Automatic. For API_PRIMARY, local not required.
            val routeForSession = determineRoute(speechLanguage)
            val requiresLocalLoad = transcriptionMode == TranscriptionMode.ON_DEVICE || transcriptionMode == TranscriptionMode.LOCAL_API_FALLBACK
            val mmForLoad = try { com.sprich.app.models.manager.ModelManager(this) } catch (_: Exception) { null }
            val loadResult: Result<Unit> = if (!requiresLocalLoad && transcriptionMode == TranscriptionMode.API_PRIMARY) {
                // API primary: no local model required for successful remote path; fallback loaded lazily on failure
                val remoteCfg = buildRemoteSttConfig()
                if (remoteCfg == null) {
                    Result.failure(Exception("Remote STT not configured — set base URL/model/API key in Settings"))
                } else {
                    // Verify credential exists — secure store only, legacy plaintext no longer supported (P0-4)
                    val cred = try { apiSecretStore.loadSecret(remoteCfg.credentialRef) ?: "" } catch (_: Exception) { "" }
                    if (cred.isBlank()) {
                        Result.failure(Exception("Missing API key for ${remoteCfg.providerId} — add in Settings"))
                    } else Result.success(Unit)
                }
            } else when (routeForSession) {
                is LocalAsrRoute.AutomaticFastConformer -> {
                    // Automatic already validated LID+Fast ready above; now load them, never Canary
                    val lidR = try { lidEngine.load().also { lidLoadAttempts.incrementAndGet() } } catch (e: Exception) { Result.failure(e) }
                    val fastR = try { fastConformerEngine.load().also { fastLoadAttempts.incrementAndGet() } } catch (e: Exception) { Result.failure(e) }
                    if (lidR.isFailure) lidR else fastR
                }
                is LocalAsrRoute.AccurateCanary -> {
                    try { engine.load().also { canaryLoadAttempts.incrementAndGet() } } catch (e: Exception) { Result.failure(e) }
                }
            }
            if (generation != sessionGeneration.get()) return
            if (loadResult.isFailure) {
                failSession(
                    generation,
                    "engine load failed route=$routeForSession mode=$transcriptionMode",
                    "Speech model unavailable",
                    "Restart Sprich or reinstall the APK",
                    loadResult.exceptionOrNull(),
                )
                return
            }
            // Ensure coordinator exists
            if (localCoordinator == null) localCoordinator = LocalTranscriptionCoordinator(lidEngine, fastConformerEngine, engine)
            // Housekeeping: unload unused resident engines if queue drained
            maybeUnloadUnused(routeForSession)

            latency.mark("audioStartRequested")
            vibrateTick()
            composition.reset()
            vad.reset()
            lastVadState = Vad.State.SILENCE
            utteranceActive.set(false)
            endpointPending.set(false)
            lastPartialText = ""
            frozenUtterancePcm = null
            try { utteranceAudio.clear() } catch (_: Exception) {}
            try { engine.clearUtteranceCapture() } catch (_: Exception) {}
            try { fastConformerEngine.clearUtteranceCapture() } catch (_: Exception) {}
            currentUtteranceToken = null
            activeUtterance = null
            Log.i("SprichIME", "vad reset ${vad.calibrationInfo()} activeConfig=$activeConfig prefsLang=$language speechLang=$speechLanguage")
            // Respect user language preference; Canary handles EN/DE/ES/FR, AUTO falls back to EN inside engine.
            // Resolved once per session and observable in diagnostics; Locale.getDefault() is never used here.
            activeConfig = SpeechSessionConfig(
                language = language,
                speechLanguage = speechLanguage,
                task = TranscriptionTask.TRANSCRIBE,
                enablePunctuation = true,
                enableCommands = commandsEnabled,
            )
            Log.i("SprichIME", "session language resolved=${activeConfig.resolvedLanguageTag()} task=${activeConfig.resolvedTask()}")

            // Start session on required engine(s) only; for API_PRIMARY, no local session needed (remote handles everything)
            val needsLocalSession = transcriptionMode != TranscriptionMode.API_PRIMARY
            if (needsLocalSession) {
                try { engine.cancelSession() } catch (_: Exception) {}
                try { fastConformerEngine.cancelSession() } catch (_: Exception) {}
                try {
                    when (routeForSession) {
                        is LocalAsrRoute.AccurateCanary -> engine.beginSession(activeConfig)
                        is LocalAsrRoute.AutomaticFastConformer -> {
                            fastConformerEngine.beginSession(activeConfig)
                        }
                    }
                } catch (t: Throwable) {
                    failSession(
                        generation,
                        "begin session failed route=$routeForSession mode=$transcriptionMode",
                        "Speech engine failed",
                        "Tap to retry",
                        t,
                    )
                    return
                }
            } else {
                Log.i("SprichIME", "API_PRIMARY — skipping local ASR session start, remote will handle transcription")
                try { engine.cancelSession() } catch (_: Exception) {}
                try { fastConformerEngine.cancelSession() } catch (_: Exception) {}
            }

            engineJob?.cancelAndJoin()
            engineJob = scope.launch {
                try {
                    // Only collect Canary partials when local route is Accurate AND local session is needed
                    val shouldCollectPartials = needsLocalSession && routeForSession is LocalAsrRoute.AccurateCanary
                    if (!shouldCollectPartials) {
                        Log.i("SprichIME", "partial collection skipped — mode=$transcriptionMode route=$routeForSession (final-only or remote primary)")
                        return@launch
                    }
                    engine.partialTranscript().collect { update ->
                        if (generation != sessionGeneration.get()) { staleCallbackDrops++; return@collect }
                        // Validate field/session ownership via controller — stale field callbacks dropped
                        val activeField = currentFieldId
                        if (activeField == null) { staleCallbackDrops++; return@collect }
                        if (update.isFinal) return@collect // finalizeOnce owns final commit; prevents duplication loop
                        // Backpressure: while catching up, degrade speculative partials first to preserve final path CPU and memory
                        if (catchingUp) {
                            Log.i("SprichIME", "partial degraded due to CatchingUp depth=${queueDepth.get()} suppressed=${catchingUpSuppressedOnsets.get()}")
                            return@collect
                        }
                        val hasText = update.stable.isNotBlank() || update.unstable.isNotBlank()
                        if (hasText && latency.delta("speechOnset", "firstHypothesis") == null) {
                            latency.mark("firstHypothesis")
                        }
                        val inputConnection = currentInputConnection
                        if (inputConnection == null) {
                            Log.w("SprichIME", "partial dropped: no InputConnection")
                            return@collect
                        }
                        // Verify IC still belongs to current field
                        if (inputConnection.hashCode() != currentFieldTokenIcHash && currentFieldTokenIcHash != 0) {
                            // IC changed without field generation bump — treat as stale
                            Log.w("SprichIME", "partial dropped: IC mismatch field=$activeField")
                            staleCallbackDrops++
                            return@collect
                        }
                        val stable = try { vocabStore.apply(update.stable) } catch (_: Exception) { update.stable }
                        val unstable = try { vocabStore.apply(update.unstable) } catch (_: Exception) { update.unstable }
                        // Only remember live partials, not final, to avoid stale fallback duplicating previous utterance
                        lastPartialText = listOf(stable, unstable).filter { it.isNotBlank() }.joinToString(" ").trim()
                        // Authoritative path: go through FieldSessionController (single owner)
                        val sessionId = session.sessionId
                        val applied = try {
                            fieldController.applyPartial(sessionId, inputConnection, stable, unstable)
                        } catch (_: Exception) { false }
                        // If editor rejected composing (e.g., WebView silently committing), fall back to IME-local preview
                        if (!applied && hasText) {
                            // Detect silent-commit editors: they return success but committed text grew
                            // Show preview only in IME bar, never duplicate into editor
                            try {
                                val preview = listOf(stable, unstable).filter { it.isNotBlank() }.joinToString(" ").trim()
                                statusText?.let { tv ->
                                    if (preview.isNotBlank()) {
                                        tv.text = preview
                                        (tv.tag as? TextView)?.text = "Live preview — final will insert once"
                                    }
                                }
                            } catch (_: Exception) {}
                            Log.i("SprichIME", "partial fallback to IME preview session=$sessionId field=$activeField")
                        } else {
                            if (applied && hasText && latency.delta("speechOnset", "firstVisibleText") == null) {
                                latency.mark("firstVisibleText")
                            }
                        }
                        Log.i(
                            "SprichIME",
                            "partial stableChars=${stable.length} unstableChars=${unstable.length} applied=$applied field=$activeField",
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e("SprichIME", "partial collection failed", t)
                    failSession(
                        generation,
                        "partial collection failed",
                        "Speech engine stopped",
                        "Tap to retry",
                        t,
                    )
                }
            }

            val started = try {
                audio.startWithOffset(
                    onChunkWithOffset = { samples, offset, length, timestampNanos, rms ->
                        handleAudioChunk(generation, samples, offset, length, timestampNanos, rms)
                    },
                    onFailure = { reason ->
                        scope.launch {
                            failSession(
                                generation,
                                reason,
                                "Microphone stopped",
                                "Tap to retry",
                                null,
                            )
                        }
                    },
                )
            } catch (e: Exception) { Log.e("SprichIME", "audio.start exception", e); false }

            if (!started) {
                failSession(
                    generation,
                    "microphone start failed",
                    "Microphone unavailable",
                    "Close other recording apps and retry",
                    null,
                )
                return
            }
            latency.mark("audioActuallyRecording")
            session.onAudioStarted()
            if (utteranceActive.get()) {
                session.onSpeechOnset()
            }
            Log.i("SprichIME", "dictation started generation=$generation engine=${engine.engineId}")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e("SprichIME", "start dictation failed", t)
            val generation = sessionGeneration.get()
            failSession(generation, "start failed", "Could not start", "Tap to retry", t)
        }
    }

    // Hot-path: single RMS, zero extra PCM allocation (reuses readBuf), offset/length ownership
    private fun handleAudioChunk(generation: Long, samples: ShortArray, offset: Int, length: Int, timestampNanos: Long, precomputedRms: Float) {
        if (generation != sessionGeneration.get() || !session.requireActive()) { staleCallbackDrops++; return }
        try {
            pipelineChunkCount++
            pipelineSampleCount += length
            val durationMs = ((length * 1000L) / SAMPLE_RATE).coerceAtLeast(1L)
            val result = vad.process(samples, offset, length, durationMs, precomputedRms)
            // Liquid wow: drive color+width from mic energy (throttled ~20fps, no layout)
            lastRms = result.rms
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            if (utteranceActive.get() && nowElapsed - lastVisualUpdateElapsed >= 50) {
                lastVisualUpdateElapsed = nowElapsed
                scope.launch(kotlinx.coroutines.Dispatchers.Main) { updateLiquidVisual(result.rms) }
            }
            if (result.state != lastVadState) {
                Log.i(
                    "SprichIME",
                    "vad ${lastVadState.name}->${result.state.name} rms=${String.format(java.util.Locale.US,"%.5f", result.rms)} threshold=${String.format(java.util.Locale.US,"%.5f", vad.currentThreshold())} noiseFloor=${String.format(java.util.Locale.US,"%.5f", vad.noiseFloorValue())} chunks=$pipelineChunkCount samples=$pipelineSampleCount pushed=$pipelinePushedSampleCount generation=$generation",
                )
                lastVadState = result.state
            } else if (pipelineChunkCount % 16L == 0L) {
                // Periodic quiet-alive diagnostics without spamming (every ~1s). Never audio.
                Log.i("SprichIME", "vad alive state=${result.state.name} rms=${String.format(java.util.Locale.US,"%.5f", result.rms)} chunks=$pipelineChunkCount pushed=$pipelinePushedSampleCount")
            }

            // Backpressure: speech episode suppression — if already suppressing, ignore entire episode until clean silence/end
            if (suppressEpisode) {
                if (result.state == Vad.State.SILENCE || result.state == Vad.State.LONG_SILENCE || result.state == Vad.State.UTTERANCE_END) {
                    suppressEpisode = false
                    Log.i("SprichIME", "Suppressed episode ended with ${result.state.name} — ready for next clean utterance")
                    // Do not treat suppressed UTTERANCE_END as endpoint
                    return
                }
                // Still in suppressed speech/hesitation — ignore tail, never capture partial
                return
            }
            if (catchingUp && (result.state == Vad.State.SPEECH || result.state == Vad.State.HESITATION)) {
                // Start of suppressed episode — count once per episode, not per chunk
                suppressEpisode = true
                catchingUpSuppressedOnsets.incrementAndGet()
                Log.w("SprichIME", "CatchingUp suppressing new utterance onset depth=${queueDepth.get()} max=$maxPendingQueueDepth suppressed=${catchingUpSuppressedOnsets.get()} rejected=${catchingUpRejectedOnsets.get()} — marking entire episode suppressed")
                updateCatchUpUi(true)
                return
            }
            if (
                result.state == Vad.State.SPEECH &&
                !catchingUp &&
                utteranceActive.compareAndSet(false, true)
            ) {
                // Note: no endpointPending gate — continuous capture while previous final decodes.
                // Prevents losing first words of next sentence when user speaks immediately.
                val preRoll = audio.snapshotPrebufferMs(PRE_ROLL_MS)
                pipelinePushedSampleCount += preRoll.size
                // AUTHORITATIVE: UtteranceAudioCollector owns seeding preRoll exactly once — engine-independent.
                frozenUtterancePcm = null
                try { utteranceAudio.begin(preRoll) } catch (_: Exception) {}
                // Freeze full utterance plan at onset — including transcription/refinement mode, provider config revision, language (Phase 2). Settings changes apply to NEXT utterance only.
                val planAtOnset = buildUtterancePlan()
                val routeAtOnset = when (val tp = planAtOnset.transcription) {
                    is TranscriptionPlan.Local -> tp.route
                    is TranscriptionPlan.ApiPrimary -> tp.localFallback ?: tp.remote.let { determineRoute(speechLanguage) }
                    is TranscriptionPlan.LocalApiFallback -> tp.local
                }
                // For streaming API we would start session here via planAtOnset.transcription; for now non-streaming uses frozen PCM at endpoint.
                // Only Canary as consumer for live partials when local route is Accurate; otherwise collector is sole owner (no duplicate Fast buffer).
                val isAccurateLocal = routeAtOnset is LocalAsrRoute.AccurateCanary && planAtOnset.transcription is TranscriptionPlan.Local
                val isFallbackAccurate = (planAtOnset.transcription as? TranscriptionPlan.LocalApiFallback)?.local is LocalAsrRoute.AccurateCanary
                if (isAccurateLocal || isFallbackAccurate) {
                    try { engine.beginUtteranceCapture(preRoll) } catch (_: Exception) {}
                }
                // For Automatic or API-primary: do NOT maintain duplicate FastConformer live buffer; collector is sole owner.
                // Create immutable token for this utterance — monotonically increasing utteranceId
                val utteranceId = utteranceIdCounter.incrementAndGet()
                val token = UtteranceToken(
                    sessionId = session.sessionId,
                    generation = generation,
                    utteranceId = utteranceId,
                    fieldId = currentFieldId,
                    fieldGeneration = fieldGeneration.get(),
                    capturedIc = try { currentInputConnection } catch (_: Exception) { null },
                )
                currentUtteranceToken = token
                // Single immutable descriptor for the entire utterance lifetime — later chunks/endpoint must not re-read mutable prefs.
                activeUtterance = ActiveUtterance(token, routeAtOnset, planAtOnset.speechConfig, planAtOnset)
                Log.i("SprichIME", "utterance onset token=$token plan=$planAtOnset routeAtOnset=$routeAtOnset preRollSamples=${preRoll.size} pushedTotal=$pipelinePushedSampleCount")
                scope.launch {
                    if (generation == sessionGeneration.get() && session.state.value is SessionState.Listening) {
                        session.onSpeechOnset()
                    }
                }
                // Onset moment: the pill "hears you" — quick pop + heavy tick
                scope.launch(Dispatchers.Main) {
                    try {
                        micContainer?.animate()?.cancel()
                        micContainer?.scaleX = 1f; micContainer?.scaleY = 1f
                        micContainer?.animate()?.scaleX(1.04f)?.scaleY(1.04f)?.setDuration(110)
                            ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                            ?.withEndAction {
                                micContainer?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(220)
                                    ?.setInterpolator(android.view.animation.OvershootInterpolator(1.6f))?.start()
                            }?.start()
                    } catch (_: Exception) {}
                }
                vibrateHeavy()
                Log.i("SprichIME", "speech onset preRollSamples=${preRoll.size} pushedTotal=$pipelinePushedSampleCount rms=${String.format(java.util.Locale.US,"%.5f", result.rms)} elapsedMs=${android.os.SystemClock.elapsedRealtime() - pipelineStartElapsed}")
            } else if (
                utteranceActive.get() &&
                (result.state == Vad.State.SPEECH || result.state == Vad.State.HESITATION)
            ) {
                pipelinePushedSampleCount += length
                // AUTHORITATIVE: collector is single owner — single copy via offset/length, zero extra allocation
                try { utteranceAudio.append(samples, offset, length) } catch (_: Exception) {}
                // Consumer split: distinguish primary live consumer from fallback (P0-10). For API_PRIMARY, fallback must stay idle until remote failure.
                val activePlan = activeUtterance?.plan
                val routeAtChunk = activeUtterance?.localRoute ?: determineRoute(speechLanguage)
                val isPrimaryLocal = activePlan?.transcription is TranscriptionPlan.Local
                val isLocalFallback = activePlan?.transcription is TranscriptionPlan.LocalApiFallback
                val shouldPushLive = (isPrimaryLocal || isLocalFallback) && routeAtChunk is LocalAsrRoute.AccurateCanary
                if (shouldPushLive) {
                    try {
                        val chunkForEngine = if (offset == 0 && length == samples.size) samples.copyOf() else samples.copyOfRange(offset, offset + length)
                        engine.pushAudio(chunkForEngine, timestampNanos)
                    } catch (_: Exception) {}
                }
                // For Automatic or API_PRIMARY + fallback: no live Canary push — fallback decodes frozen snapshot only on remote failure.
            }

            if (
                result.state == Vad.State.UTTERANCE_END &&
                utteranceActive.compareAndSet(true, false)
            ) {
                latency.mark("endpointDetected")
                // Mark pending for observability; do not block next onset — queue handles continuous speech.
                endpointPending.set(true)
                // Freeze authoritative collector snapshot — immutable, engine-independent.
                // CRITICAL: captured synchronously before next onset can reuse live buffer.
                val frozenSnap: ShortArray = try { utteranceAudio.freeze() } catch (_: Exception) { ShortArray(0) }
                // P1-25: single isolated copy already from freeze(), no second copyOf needed
                frozenUtterancePcm = frozenSnap
                val token = currentUtteranceToken ?: run {
                    Log.w("SprichIME", "endpoint without token — creating synthetic token")
                    UtteranceToken(session.sessionId, generation, utteranceIdCounter.get(), currentFieldId, fieldGeneration.get(), try { currentInputConnection } catch (_: Exception) { null })
                }
                // Use frozen plan/route/config from activeUtterance — never re-read mutable prefs (Phase 0A+2).
                val captured = activeUtterance
                val pendingPlan = captured?.takeIf { it.token.utteranceId == token.utteranceId }?.plan ?: buildUtterancePlan()
                val pendingRoute = (captured?.takeIf { it.token.utteranceId == token.utteranceId }?.localRoute ?: determineRoute(speechLanguage)).also { currentRouteSnapshot ->
                    Log.i("SprichIME", "endpoint route frozen token=$token route=$currentRouteSnapshot plan=$pendingPlan capturedWas=${captured?.localRoute} configLang=${pendingPlan.speechConfig.resolvedLanguageTag()}")
                }
                val pendingConfig = captured?.takeIf { it.token.utteranceId == token.utteranceId }?.speechConfig ?: pendingPlan.speechConfig
                val pending = PendingUtterance(
                    token = token,
                    pcm = frozenSnap, // P1-25: already isolated, no duplicate copy
                    config = pendingConfig,
                    route = pendingRoute,
                    plan = pendingPlan,
                    pushedSamples = pipelinePushedSampleCount,
                    reason = StopReason.ENDPOINT,
                    endpointTimestampNanos = System.nanoTime(),
                )
                // Active utterance completed — clear descriptor so next onset creates fresh one; but retain until next onset for overlapping queue isolation.
                // Keep nulling after enqueue so chunks cannot reuse stale route.
                activeUtterance = null
                Log.i("SprichIME", "endpoint detected token=$token pushedSamples=$pipelinePushedSampleCount chunks=$pipelineChunkCount frozenSamples=${frozenSnap.size} queueDepthBefore=${queueDepth.get()}")
                enqueuePending(pending)
            }
        } catch (t: Throwable) {
            Log.e("SprichIME", "audio chunk processing failed", t)
            scope.launch {
                failSession(
                    generation,
                    "audio processing failed",
                    "Audio processing failed",
                    "Tap to retry",
                    t,
                )
            }
        }
    }

    // Legacy wrapper for old AudioCapture path (tests) — delegates to hot-path with single RMS
    private fun handleAudioChunk(generation: Long, samples: ShortArray, timestampNanos: Long) {
        if (samples.isEmpty()) return
        var sum = 0.0
        for (s in samples) { val f = s / 32768f; sum += f * f }
        val rms = kotlin.math.sqrt(sum / samples.size).toFloat()
        handleAudioChunk(generation, samples, 0, samples.size, timestampNanos, rms)
    }

    // ---------- Overlapping utterance queue — single authoritative actor ----------
    // Long-lived actor: exactly one consumer, FIFO, no worker start/stop race (Phase 1 fix)
    private fun startFinalizationActor() {
        if (finalizationActorJob?.isActive == true) return
        finalizationActorJob = scope.launch {
            Log.i("SprichIME", "finalization actor started")
            for (pending in pendingChannel) {
                // Decrement depth before processing (pending already counted on enqueue)
                val depthBefore = queueDepth.get()
                // Process
                try {
                    finalizePending(pending)
                } catch (e: CancellationException) {
                    Log.i("SprichIME", "finalization actor cancelled pending=$pending")
                    throw e
                } catch (t: Throwable) {
                    Log.e("SprichIME", "finalizePending outer failed $pending", t)
                    // Utterance-scoped — do NOT destroy B
                    try { failUtteranceScoped(pending.token, "finalization outer failed ${pending.token}", t) } catch (_: Exception) {}
                } finally {
                    val newDepth = queueDepth.decrementAndGet()
                    lastQueueDepth = newDepth
                    // Exit CatchingUp when recovered — must clear UI as well
                    if (catchingUp && newDepth < maxPendingQueueDepth - 1) {
                        catchingUp = false
                        Log.i("SprichIME", "CatchingUp recovered depth=$newDepth suppressed=${catchingUpSuppressedOnsets.get()} rejected=${catchingUpRejectedOnsets.get()}")
                        updateCatchUpUi(false)
                    }
                    if (newDepth == 0) {
                        endpointPending.set(false)
                        // Ensure UI cleared when fully drained
                        if (!catchingUp) updateCatchUpUi(false)
                        // USER_STOP FIFO termination — stop after queue drains, preserving FIFO order, no loss
                        if (stopRequested && newDepth == 0) {
                            val prevGen = stopRequestedGeneration
                            stopRequested = false
                            // Bump generation after queue drained to prevent new stale callbacks, but not before FIFO drained
                            val newGen = sessionGeneration.incrementAndGet()
                            fieldGeneration.incrementAndGet()
                            try { engineJob?.cancel() } catch (_: Exception) {}
                            engineJob = null
                            // Ensure audio stopped (already stopped at STOP request, but ensure)
                            // Do not clear utteranceAudio if B already cleared? It's already empty after freeze, safe
                            utteranceActive.set(false)
                            endpointPending.set(false)
                            lastPartialText = ""
                            frozenUtterancePcm = null
                            currentUtteranceToken = null
                            activeUtterance = null
                            vad.reset()
                            lastVadState = Vad.State.SILENCE
                            try { composition.discardPartial(currentInputConnection) } catch (_: Exception) {}
                            try { if (session.state.value !is SessionState.Idle) session.end() } catch (_: Exception) { try { session.idle() } catch (_: Exception) {} }
                            updateImeUi(false)
                            Log.i("SprichIME", "USER_STOP drained termination prevGen=$prevGen newGen=$newGen")
                            writeDiagnostics("stopped USER_STOP drained prevGen=$prevGen newGen=$newGen claims=$finalizationClaims commits=$finalCommitCount")
                        }
                    }
                    Log.i("SprichIME", "actor processed pending=${pending.token.utteranceId} depthBefore=$depthBefore newDepth=$newDepth catchingUp=$catchingUp stopRequested=$stopRequested")
                }
            }
            Log.i("SprichIME", "finalization actor completed (channel closed)")
        }
    }

    private fun enqueuePending(pending: PendingUtterance) {
        val depthBefore = queueDepth.get()
        if (depthBefore >= maxPendingQueueDepth) {
            // Genuinely bounded: reject new frozen utterance rather than growing memory unbounded. Preserve already queued FIFO, reject newest, count explicitly.
            finalizationQueueOverflows.incrementAndGet()
            catchingUpRejectedOnsets.incrementAndGet()
            if (!catchingUp) {
                catchingUp = true
                Log.w("SprichIME", "finalization queue at capacity depth=$depthBefore pending=${pending.token.utteranceId} peak=${pendingQueuePeak.get()} overflows=${finalizationQueueOverflows.get()} rejected=${catchingUpRejectedOnsets.get()} — entering CatchingUp, rejecting new utterance (bounded)")
                updateCatchUpUi(true)
            } else {
                Log.w("SprichIME", "queue at capacity depth=$depthBefore pending=${pending.token.utteranceId} overflows=${finalizationQueueOverflows.get()} rejected=${catchingUpRejectedOnsets.get()} — rejecting (bounded)")
                updateCatchUpUi(true)
            }
            // Bounded memory, explicit rejection counted. Do not pretend speech was captured.
            return
        }
        // Proactive backpressure one before capacity: enter CatchingUp early to degrade partials and prevent new onset
        if (depthBefore >= maxPendingQueueDepth - 1 && !catchingUp) {
            catchingUp = true
            Log.w("SprichIME", "finalization queue near capacity depth=$depthBefore pending=${pending.token.utteranceId} — entering CatchingUp early, degrading partials")
            updateCatchUpUi(true)
        }
        val newDepth = queueDepth.incrementAndGet()
        lastQueueDepth = newDepth
        if (newDepth.toLong() > pendingQueuePeak.get()) pendingQueuePeak.set(newDepth.toLong())
        Log.i("SprichIME", "enqueuePending token=${pending.token} pcm=${pending.pcm.size} queueDepth=$newDepth peak=${pendingQueuePeak.get()} reason=${pending.reason} catchingUp=$catchingUp")
        // Bounded channel (capacity=4) — trySend without suspension avoids unbounded PCM retention via parked coroutines
        val result = pendingChannel.trySend(pending)
        if (!result.isSuccess) {
            // Race where two endpoints concurrent exceed bound — reject explicitly
            Log.e("SprichIME", "pendingChannel trySend failed depth=$newDepth pending=${pending.token.utteranceId} result=$result — rejecting (bounded race)")
            queueDepth.decrementAndGet()
            catchingUpRejectedOnsets.incrementAndGet()
            finalizationQueueOverflows.incrementAndGet()
            updateCatchUpUi(true)
            return
        }
        endpointPending.set(true)
    }

    private fun updateCatchUpUi(isCatchingUp: Boolean) {
        // Surface explicit Catching Up state — truthful, not silent drop. Shows "Catching up…" instead of pretending all speech captured.
        scope.launch(Dispatchers.Main) {
            try {
                if (isCatchingUp) {
                    statusText?.text = "Catching up…"
                    (statusText?.tag as? TextView)?.text = "Please pause briefly — processing ${queueDepth.get()} utterances"
                    Log.w("SprichIME", "UI Catching up — suppressed=${catchingUpSuppressedOnsets.get()} rejected=${catchingUpRejectedOnsets.get()} depth=${queueDepth.get()}")
                } else {
                    // Recovery: restore listening hint if still active, otherwise normal idle will be set by state collector
                    if (session.state.value is SessionState.Listening || session.state.value is com.sprich.app.input.lifecycle.SessionState.Speech || session.state.value is com.sprich.app.input.lifecycle.SessionState.Finalizing) {
                        statusText?.text = "Listening…"
                        (statusText?.tag as? TextView)?.text = "Words will appear at the cursor"
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // Visibility for tests and diagnostics — truthful backpressure instrumentation
    fun getCatchingUpSuppressedOnsets(): Long = catchingUpSuppressedOnsets.get()
    fun getCatchingUpRejectedOnsets(): Long = catchingUpRejectedOnsets.get()
    fun getQueueDepthForTest(): Int = queueDepth.get()
    fun getPendingQueuePeakForTest(): Long = pendingQueuePeak.get()
    fun getQueueOverflowsForTest(): Long = finalizationQueueOverflows.get()
    fun getMaxQueueDepthForTest(): Int = maxPendingQueueDepth
    fun isCatchingUpForTest(): Boolean = catchingUp

    @Deprecated("Use pendingChannel actor") private fun ensureFinalizationWorkerRunning() {
        // No-op: actor is long-lived, started once in onCreate. Kept for backward compat.
        startFinalizationActor()
    }

    /**
     * Serialized finalization for one immutable PendingUtterance.
     * Does NOT mutate active capture B while decoding A.
     * Uses transcribeSnapshot (side-effect-bounded) and only clears active flags if token still owns active capture.
     */
    private suspend fun finalizePending(pending: PendingUtterance) {
        val token = pending.token
        val reason = pending.reason
        var finishedWithRetry = false
        try {
            // Atomically claim — second caller for same utteranceId drops silently (exactly-once)
            val claimed = synchronized(finalizedUtterances) {
                if (finalizedUtterances.contains(token.utteranceId)) false else { finalizedUtterances.add(token.utteranceId); true }
            }
            if (!claimed) {
                Log.w("SprichIME", "finalizePending duplicate claim dropped token=$token reason=$reason")
                staleCallbackDrops++
                return
            }
            finalizationClaims++
            Log.i("SprichIME", "finalizePending claimed token=$token reason=$reason pcm=${pending.pcm.size} pushedSamples=${pending.pushedSamples} queueDepth=${lastQueueDepth}")

            // Validate token is still current before expensive native decode — but do not mutate active B's state on failure.
            if (token.generation != sessionGeneration.get() || !session.requireActive()) {
                Log.w("SprichIME", "finalizePending abandoned pre-decode stale generation token=$token current=${sessionGeneration.get()}")
                staleCallbackDrops++
                // Do NOT clear utteranceActive/endpointPending if B is active with different token
                maybeClearActiveStateForToken(token)
                return
            }
            if (token.sessionId != session.sessionId || !session.isSessionValid(token.sessionId)) {
                Log.w("SprichIME", "finalizePending abandoned pre-decode invalid session token=$token sessionId=${session.sessionId}")
                staleCallbackDrops++
                maybeClearActiveStateForToken(token)
                return
            }
            if (token.fieldId != currentFieldId || token.fieldGeneration != fieldGeneration.get()) {
                Log.w("SprichIME", "finalizePending abandoned pre-decode field mismatch token=$token currentField=$currentFieldId fg=${fieldGeneration.get()}")
                staleCallbackDrops++
                maybeClearActiveStateForToken(token)
                return
            }
            if (reason != StopReason.USER_STOP && reason != StopReason.ENDPOINT) {
                Log.i("SprichIME", "finalizePending cancelled by reason=$reason token=$token")
                maybeClearActiveStateForToken(token)
                return
            }

            session.onFinalizing()
            Log.i("SprichIME", "finalizePending start token=$token pushedSamples=${pending.pushedSamples} reason=$reason config=${pending.config.resolvedLanguageTag()} utteranceId=${token.utteranceId} resolvedLang=${pending.config.resolvedLanguageTag()}")
            // Per-utterance metrics for German blank triage (debug, not transcript content)
            val pcmDurationMs = (pending.pcm.size * 1000L) / SAMPLE_RATE
            val pcmRms = ReplayHarness.computeRms(pending.pcm)
            Log.i("SprichIME", "utteranceMetrics id=${token.utteranceId} durationMs=$pcmDurationMs rms=${String.format(java.util.Locale.US,"%.5f", pcmRms)} samples=${pending.pcm.size} lang=${pending.config.resolvedLanguageTag()} pushed=${pending.pushedSamples}")
            // Opt-in WAV capture: save exact frozen PCM for offline replay harness
            try {
                val wavEnabled = try { prefs.debugWavCapture.first() } catch (_: Exception) { false }
                if (wavEnabled) {
                    ReplayHarness.saveWavIfEnabled(this@SprichIME, true, token.utteranceId, pending.pcm, pending.config)
                }
            } catch (_: Exception) {}
            // NEW: Unified transcription via UtterancePlan (Phase 2-5). One immutable plan per utterance, remote-first with safe fallback.
            if (localCoordinator == null) localCoordinator = LocalTranscriptionCoordinator(lidEngine, fastConformerEngine, engine)
            // Ensure coordinator exists with shared HttpClient (connection pooling, keep-alive, HTTP/2)
            val plan = pending.plan
            val t0 = android.os.SystemClock.elapsedRealtime()
            // P0-12: truthful metrics — increment path-specific counters after transcribe, not blindly
            val transcriptionResult: TranscriptionResult = try {
                ensureTranscriptionCoordinator().transcribe(pending.pcm, plan, pending.token.utteranceId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SprichIME", "transcriptionCoordinator failed utt=${token.utteranceId}", e)
                TranscriptionResult("", ResolvedUtteranceLanguage.Unknown, plan.speechConfig, TranscriptionSourceId.LOCAL_FAST)
            }
            val elapsed = android.os.SystemClock.elapsedRealtime() - t0
            // P0-12: increment truthful path counters
            when (transcriptionResult.source) {
                TranscriptionSourceId.LOCAL_FAST, TranscriptionSourceId.LOCAL_CANARY -> { localNativeDecodeStarts++; nativeDecodeStarts++ }
                else -> remoteTranscriptionStarts++
            }
            // P1-16: propagate remote-detected language consistently
            var effectiveConfig = transcriptionResult.effectiveConfig
            val postProcessResolved: ResolvedUtteranceLanguage = transcriptionResult.resolvedLanguage
            if (postProcessResolved is ResolvedUtteranceLanguage.Known) {
                // Update effectiveConfig to Fixed language so downstream spoken processing / refinement use DE not auto
                effectiveConfig = effectiveConfig.copy(
                    language = postProcessResolved.language,
                    speechLanguage = com.sprich.app.speech.api.SpeechLanguage.Fixed(postProcessResolved.language.code)
                )
            }
            val lidDetected = when (postProcessResolved) {
                is ResolvedUtteranceLanguage.Known -> postProcessResolved.language.code
                else -> "unknown"
            }
            val lidLatencyMs: Long = 0 // coordinator already includes LID latency internally for local path
            // Keep for diagnostics parity
            val lidOutcomeForLog = when (postProcessResolved) {
                is ResolvedUtteranceLanguage.Known -> "Known"
                else -> "Unknown"
            }
            val debugTraceEnabled = try { prefs.debugTranscriptTrace.first() } catch (_: Exception) { false }
            if (debugTraceEnabled) {
                Log.i("SprichIME", "RAW_ASR token=${token.utteranceId} source=${transcriptionResult.source} text=\"${transcriptionResult.text.take(80)}\"")
            } else {
                Log.i("SprichIME", "finalizePending decoded token=$token source=${transcriptionResult.source} elapsedMs=$elapsed textLen=${transcriptionResult.text.length} queueDepth=${lastQueueDepth} rms=${String.format(java.util.Locale.US,"%.5f", pcmRms)} durationMs=$pcmDurationMs")
            }

            // Re-validate all conditions immediately before insertion — still without corrupting B if A is stale
            if (token.generation != sessionGeneration.get() || !session.requireActive()) {
                Log.w("SprichIME", "finalizePending abandoned post-decode stale generation token=$token")
                staleCallbackDrops++
                maybeClearActiveStateForToken(token)
                return
            }
            if (token.sessionId != session.sessionId || !session.isSessionValid(token.sessionId)) {
                Log.w("SprichIME", "finalizePending abandoned post-decode invalid session token=$token")
                staleCallbackDrops++
                maybeClearActiveStateForToken(token)
                return
            }
            if (token.fieldId != currentFieldId || token.fieldGeneration != fieldGeneration.get()) {
                Log.w("SprichIME", "finalizePending abandoned post-decode field mismatch token=$token")
                staleCallbackDrops++
                maybeClearActiveStateForToken(token)
                return
            }
            val currentIc = try { currentInputConnection } catch (_: Exception) { null }
            if (currentIc == null) {
                Log.w("SprichIME", "finalizePending abandoned post-decode no InputConnection token=$token")
                staleCallbackDrops++
                maybeClearActiveStateForToken(token)
                return
            }
            if (!fieldController.isCurrentSession(token.sessionId)) {
                Log.w("SprichIME", "finalizePending abandoned post-decode fieldController stale token=$token")
                staleCallbackDrops++
                maybeClearActiveStateForToken(token)
                return
            }

            // P0-7: Single-parse command isolation — parse exactly once, refinement never gains command authority
            // P1-17: Apply personal vocabulary before refinement, send only relevant terms
            val raw = transcriptionResult.text.trim()
            Log.i("SprichIME", "pipeline: token=${token.utteranceId} source=${transcriptionResult.source} mode=${plan.transcription::class.simpleName} rawLen=${raw.length} lang=${effectiveConfig.resolvedLanguageTag()} refinement=${plan.refinement::class.simpleName}")
            // Parse exactly once on raw transcription
            val preRefineParsed = try {
                SpokenEditingParser.parse(raw, postProcessResolved, commandsEnabled)
            } catch (_: Exception) {
                SpokenEditingParser.EditResult(raw, false)
            }
            val isDeleteCmd = SpokenEditingParser.isDeleteCommand(preRefineParsed.text)
            val prepared: PreparedFinalAction = if (isDeleteCmd) {
                Log.i("SprichIME", "spoken command detected token=$token cmd=${preRefineParsed.text} — skipping refinement")
                if (preRefineParsed.text == "__DELETE_SENTENCE__") PreparedFinalAction.DeleteSentence(token) else PreparedFinalAction.DeleteLast(token)
            } else {
                // Non-command: deterministic text with vocab applied before refinement (P1-17 step 2)
                var deterministic = preRefineParsed.text
                // Ensure vocab applied deterministically before refinement (parser already did, but double-ensure)
                deterministic = try { vocabStore.apply(deterministic) } catch (_: Exception) { deterministic }
                // For irrelevant vocab protection, compute relevant terms only (present in current transcript)
                val relevantTerms: List<String> = try {
                    val allEntries = vocabStore.all().map { it.written }.filter { it.isNotBlank() }
                    if (allEntries.isEmpty()) emptyList()
                    else {
                        val lower = deterministic.lowercase()
                        allEntries.filter { lower.contains(it.lowercase()) }.take(20)
                    }
                } catch (_: Exception) { emptyList<String>() }
                val refinedText: String = when (val rp = plan.refinement) {
                    is RefinementPlan.Off -> deterministic
                    is RefinementPlan.Enabled -> {
                        if (deterministic.isBlank()) deterministic else {
                            refinementStarts++
                            val provider = providerForRefinementConfig(rp.config)
                            if (provider == null) {
                                Log.w("SprichIME", "refinement provider unavailable for $rp, using deterministic")
                                deterministic
                            } else {
                                // P1-16: language already propagated via effectiveConfig, use that for refinement request
                                val reqLanguage = effectiveConfig.resolvedLanguageTag()
                                val req = com.sprich.app.speech.refinement.RefinementRequest(
                                    text = deterministic,
                                    language = reqLanguage,
                                    mode = rp.mode,
                                    protectedTerms = relevantTerms,
                                )
                                val deadlineMs = rp.config.deadlineMs
                                val refinedResult = try {
                                    kotlinx.coroutines.withTimeoutOrNull(deadlineMs) { provider.refine(req) }
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                    Log.w("SprichIME", "refinement exception", e)
                                    null
                                }
                                if (refinedResult == null) {
                                    Log.w("SprichIME", "refinement timeout/discard after ${deadlineMs}ms, using deterministic")
                                    deterministic
                                } else {
                                    val candidate = refinedResult.text.trim()
                                    val validation = RefinementValidator.validate(deterministic, candidate, rp.mode, relevantTerms)
                                    when (validation) {
                                        is RefinementValidator.Result.Accept -> {
                                            Log.i("SprichIME", "refinement accepted mode=${rp.mode} inLen=${deterministic.length} outLen=${candidate.length} latency=${refinedResult.latencyMs}")
                                            candidate
                                        }
                                        is RefinementValidator.Result.Reject -> {
                                            Log.w("SprichIME", "refinement rejected reason=${validation.reason}, using deterministic")
                                            deterministic
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                PreparedFinalAction.Text(refinedText, postProcessResolved)
            }

            // P0-7: Refined text must NEVER be reinterpreted as command — commit prepared action directly
            val applied: Boolean = when (prepared) {
                is PreparedFinalAction.DeleteLast -> {
                    val ok = executeDeleteCommand(token, 40)
                    if (ok) finalCommitCount++
                    ok
                }
                is PreparedFinalAction.DeleteSentence -> {
                    val ok = executeDeleteCommand(token, 120)
                    if (ok) finalCommitCount++
                    ok
                }
                is PreparedFinalAction.Text -> {
                    val finalText = (prepared as PreparedFinalAction.Text).text
                    if (finalText.isBlank()) {
                        Log.w("SprichIME", "final transcript empty token=$token")
                        false
                    } else {
                        val ok = commitFinalText(token, finalText, prepared.resolved)
                        Log.i("SprichIME", "final token=$token chars=${finalText.length} applied=$ok elapsedMs=$elapsed lang=${effectiveConfig.resolvedLanguageTag()} lid=$lidDetected resolved=$postProcessResolved")
                        if (ok) finalCommitCount++
                        ok
                    }
                }
            }
            if (prepared is PreparedFinalAction.Text && (prepared as PreparedFinalAction.Text).text.isNotBlank() && !applied) {
                // UTTERANCE-SCOPED: editor ambiguous must NOT destroy active B
                failUtteranceScoped(token, "final text insertion ambiguous token=$token", null)
                return
            }
            if (prepared is PreparedFinalAction.Text && (prepared as PreparedFinalAction.Text).text.isBlank() && !applied) {
                // blank already handled
            } else if (applied) {
                latency.mark("textCommitted")
                celebrateCommit()
            }

            // Post-commit housekeeping moved after prepared handling
            lastPartialText = ""
            // Only clear active capture state if this pending still owns it; otherwise B is active and must not be corrupted.
            maybeClearActiveStateForToken(token)
            // Also clear legacy global frozen only if it equals this pending's pcm (avoid clearing B's snapshot)
            if (frozenUtterancePcm != null && frozenUtterancePcm?.size == pending.pcm.size) {
                if (currentUtteranceToken?.utteranceId == token.utteranceId) {
                    frozenUtterancePcm = null
                }
            }
            // Do NOT call engine.clearUtteranceCapture() here — that would erase B's live buffer.
            // Only need to ensure partial decoding continues for next utterance.
            if (reason == StopReason.ENDPOINT) {
                try {
                    val isBActive = currentUtteranceToken != null && currentUtteranceToken?.utteranceId != token.utteranceId && utteranceActive.get()
                    if (isBActive) {
                        Log.i("SprichIME", "finalizePending ENDPOINT skip re-arm, B already active token=${currentUtteranceToken} A=$token")
                    } else {
                        when (val tp = pending.plan.transcription) {
                            is TranscriptionPlan.ApiPrimary -> {
                                Log.i("SprichIME", "finalizePending ENDPOINT API_PRIMARY neutral re-arm token=$token")
                            }
                            is TranscriptionPlan.Local -> when (tp.route) {
                                is LocalAsrRoute.AutomaticFastConformer -> fastConformerEngine.beginSession(pending.config)
                                is LocalAsrRoute.AccurateCanary -> engine.beginSession(pending.config)
                            }
                            is TranscriptionPlan.LocalApiFallback -> when (tp.local) {
                                is LocalAsrRoute.AutomaticFastConformer -> fastConformerEngine.beginSession(pending.config)
                                is LocalAsrRoute.AccurateCanary -> engine.beginSession(pending.config)
                            }
                        }
                        if (session.state.value !is SessionState.Listening) {
                            try { session.onListeningAgain() } catch (_: Exception) {}
                        }
                        Log.i("SprichIME", "finalizePending ENDPOINT re-armed token=$token plan=${pending.plan} fieldStill=${fieldController.isCurrentSession(token.sessionId)}")
                    }
                    finishedWithRetry = true
                } catch (t: Throwable) {
                    Log.e("SprichIME", "beginSession after ENDPOINT finalize failed token=$token", t)
                    // Utterance-scoped re-arm failure — do not destroy B if active; keep session alive
                    failUtteranceScoped(token, "begin after finalize failed token=$token", t)
                }
            } else if (reason == StopReason.USER_STOP) {
                Log.i("SprichIME", "finalizePending USER_STOP committed token=$token awaiting termination")
                finishedWithRetry = false
            }
            // Early return to skip legacy post-processing (handled above)
        } catch (e: CancellationException) {
            Log.i("SprichIME", "finalizePending cancelled token=${pending.token} reason=${pending.reason}")
            maybeClearActiveStateForToken(pending.token)
            throw e
        } catch (t: Throwable) {
            // UTTERANCE-SCOPED: do not destroy B — only currently active engine corruption / mic/field/service would be global
            Log.w("SprichIME", "finalizePending isolated failure token=$token", t)
            failUtteranceScoped(token, "finalization failed token=$token reason=$reason", t)
        }
    }

    private fun maybeClearActiveStateForToken(token: UtteranceToken) {
        // Only clear active capture flags if this token still owns the active capture.
        // If B has already started (different utteranceId), do NOT reset vad/active.
        val current = currentUtteranceToken
        if (current != null && current.utteranceId != token.utteranceId) {
            Log.i("SprichIME", "maybeClearActiveState skipping — B active current=$current vs finished $token")
            return
        }
        // Clear frozen descriptor if it belongs to this token (phase 0A)
        if (activeUtterance?.token?.utteranceId == token.utteranceId) {
            activeUtterance = null
        }
        // Collector may be cleared for next utterance only when no B active — handled by begin()
        
        // This token is still active (or no active), safe to clear.
        // But also check if queue still has pending — keep endpointPending true if pending remains.
        val hasPending = queueDepth.get() > 0
        if (!hasPending) endpointPending.set(false)
        // Do not unconditionally clear utteranceActive if B just started as active? Already checked above.
        // For ENDPOINT we keep utteranceActive false; for USER_STOP termination handled elsewhere.
        // We do NOT reset vad here if B active — already guarded.
        // If no B, reset is safe but defer to caller? For safety, only reset vad if no active B.
        if (current == null || current.utteranceId == token.utteranceId) {
            // No B overwriting, safe to consider clearing? But we avoid clearing vad that B might need for hesitation detection?
            // For blank final, we had previous logic to reset vad; keep but only if token matches.
            // Minimal: do not reset pipeline counters here; they are per-utterance via pending.
        }
    }

    /**
     * Single authoritative finalization entry. Now creates immutable PendingUtterance and delegates
     * to serialized worker to guarantee:
     * - immutable PCM snapshot (Race 1)
     * - no clear of active B's buffer (Race 2)
     * - no unconditional reset of B's state (Race 3)
     * - multiple pendings queued, not lost via single endpointJob (Race 4)
     */
    private suspend fun finalizeOnce(token: UtteranceToken, reason: StopReason) {
        // P1-25: reduce copies — snapshot() already returns isolated copy, no extra copyOf
        val snap: ShortArray = try {
            val collectorSnap = utteranceAudio.snapshot()
            if (collectorSnap.isNotEmpty()) collectorSnap else frozenUtterancePcm ?: engine.snapshotUtterancePcm().copyOf()
        } catch (_: Exception) { ShortArray(0) }
        // Use frozen utterance descriptor if it matches this token (covers USER_STOP path); otherwise fallback to current plan.
        val captured = activeUtterance?.takeIf { it.token.utteranceId == token.utteranceId }
        val pendingPlan = captured?.plan ?: buildUtterancePlan()
        val pendingRoute = captured?.localRoute ?: when (val tp = pendingPlan.transcription) {
            is TranscriptionPlan.Local -> tp.route
            is TranscriptionPlan.ApiPrimary -> tp.localFallback ?: determineRoute(speechLanguage)
            is TranscriptionPlan.LocalApiFallback -> tp.local
        }
        val pendingConfig = captured?.speechConfig ?: pendingPlan.speechConfig
        val pending = PendingUtterance(
            token = token,
            pcm = snap,
            config = pendingConfig,
            route = pendingRoute,
            plan = pendingPlan,
            pushedSamples = pipelinePushedSampleCount,
            reason = reason,
            endpointTimestampNanos = System.nanoTime(),
        )
        // P0 item 3: all accepted final utterances enter same FIFO actor — ENDPOINT, USER_STOP, 30s cap, explicit finish
        // USER_STOP no longer bypasses queue; it enqueues after earlier accepted utterances to preserve FIFO and avoid loss
        enqueuePending(pending)
    }

    // Legacy wrapper for tests — delegates to finalizeOnce with ENDPOINT
    private suspend fun finalizeUtterance(generation: Long) {
        val token = currentUtteranceToken ?: UtteranceToken(session.sessionId, generation, utteranceIdCounter.get(), currentFieldId, fieldGeneration.get(), try { currentInputConnection } catch (_: Exception) { null })
        finalizeOnce(token, StopReason.ENDPOINT)
    }

    // P0-7 helpers: commit without second parse — refined text never becomes command
    private fun executeDeleteCommand(token: UtteranceToken, charsToDelete: Int): Boolean {
        val ic = currentInputConnection ?: return false
        if (token.generation != sessionGeneration.get()) { staleCallbackDrops++; return false }
        if (token.sessionId != session.sessionId || !session.isSessionValid(token.sessionId)) { staleCallbackDrops++; return false }
        if (token.fieldId != currentFieldId || token.fieldGeneration != fieldGeneration.get()) { staleCallbackDrops++; return false }
        if (!fieldController.isCurrentSession(token.sessionId)) { staleCallbackDrops++; return false }
        val deleted = ic.deleteSurroundingText(charsToDelete, 0)
        try { fieldController.commitUtterance(token.sessionId, token.utteranceId, ic, "") } catch (_: Exception) {}
        try { composition.discardPartial(ic) } catch (_: Exception) { composition.finishIfActive(ic) }
        return deleted
    }

    private fun commitFinalText(token: UtteranceToken, text: String, resolved: ResolvedUtteranceLanguage): Boolean {
        val ic = currentInputConnection ?: return false
        if (token.generation != sessionGeneration.get()) { staleCallbackDrops++; return false }
        if (token.sessionId != session.sessionId || !session.isSessionValid(token.sessionId)) { staleCallbackDrops++; return false }
        if (token.fieldId != currentFieldId || token.fieldGeneration != fieldGeneration.get()) { staleCallbackDrops++; return false }
        if (!fieldController.isCurrentSession(token.sessionId)) { staleCallbackDrops++; return false }
        if (ic != token.capturedIc && token.capturedIc != null) {
            if (ic.hashCode() != currentFieldTokenIcHash && currentFieldTokenIcHash != 0) {
                Log.w("SprichIME", "commitFinalText IC mismatch token=$token")
                staleCallbackDrops++
                return false
            }
        }
        // No second SpokenEditingParser parse — text is already prepared (P0-7)
        // Final vocab apply already done, but ensure once more for safety (idempotent)
        val finalText = try { vocabStore.apply(text) } catch (_: Exception) { text }
        val result = try { fieldController.commitUtteranceTyped(token.sessionId, token.utteranceId, ic, finalText) } catch (_: Exception) { FieldSessionController.CommitResult.StaleSession }
        return when (result) {
            is FieldSessionController.CommitResult.Committed -> true
            is FieldSessionController.CommitResult.EditorRejected -> {
                Log.w("SprichIME", "commitFinalText EditorRejected (ambiguous) — NOT retrying to avoid duplication token=$token")
                false
            }
            is FieldSessionController.CommitResult.AlreadyFinalized -> { Log.w("SprichIME", "AlreadyFinalized token=$token — never direct-commit"); false }
            is FieldSessionController.CommitResult.StaleSession -> { Log.w("SprichIME", "StaleSession token=$token — never direct-commit"); false }
            is FieldSessionController.CommitResult.WrongField -> { Log.w("SprichIME", "WrongField token=$token — never direct-commit"); false }
            is FieldSessionController.CommitResult.NoInputConnection -> { Log.w("SprichIME", "NoInputConnection token=$token — never direct-commit"); false }
        }
    }

    private fun applyFinalText(token: UtteranceToken, text: String, language: Language = activeConfig.language): Boolean {
        val inputConnection = currentInputConnection ?: return false
        // Validate token still owns this insertion immediately before commit
        if (token.generation != sessionGeneration.get()) { staleCallbackDrops++; return false }
        if (token.sessionId != session.sessionId || !session.isSessionValid(token.sessionId)) { staleCallbackDrops++; return false }
        if (token.fieldId != currentFieldId || token.fieldGeneration != fieldGeneration.get()) { staleCallbackDrops++; return false }
        if (!fieldController.isCurrentSession(token.sessionId)) { staleCallbackDrops++; return false }
        // Verify IC still current
        if (inputConnection != token.capturedIc && token.capturedIc != null) {
            // Allow if IC hash matches field token but object identity changed (Android may recreate IC)
            if (inputConnection.hashCode() != currentFieldTokenIcHash && currentFieldTokenIcHash != 0) {
                Log.w("SprichIME", "applyFinalText IC mismatch token=$token")
                staleCallbackDrops++
                return false
            }
        }
        val langForParser = language
        val parsed = try {
            SpokenEditingParser.parse(text, langForParser, commandsEnabled)
        } catch (_: Exception) {
            SpokenEditingParser.EditResult(text, false)
        }
        return if (SpokenEditingParser.isDeleteCommand(parsed.text)) {
            val toDelete = if (parsed.text == "__DELETE_SENTENCE__") 120 else 40
            val deleted = inputConnection.deleteSurroundingText(toDelete, 0)
            // Delete command is a per-utterance operation — mark finalized via both coordinators (best-effort, ignore null IC result)
            try { fieldController.commitUtterance(token.sessionId, token.utteranceId, inputConnection, "") } catch (_: Exception) {}
            // Also ensure SprichIME's authoritative set contains this utterance (already claimed in finalizePending)
            try { composition.discardPartial(inputConnection) } catch (_: Exception) { composition.finishIfActive(inputConnection) }
            deleted
        } else {
            val finalText = try { vocabStore.apply(parsed.text) } catch (_: Exception) { parsed.text }
            // One authoritative exactly-once owner: SprichIME.finalizedUtterances is primary, FieldSessionController secondary.
            // Use typed result to avoid dangerous fallback that treated stale/duplicate as editor rejection.
            val result = try { fieldController.commitUtteranceTyped(token.sessionId, token.utteranceId, inputConnection, finalText) } catch (_: Exception) { FieldSessionController.CommitResult.StaleSession }
            return when (result) {
                is FieldSessionController.CommitResult.Committed -> true
                is FieldSessionController.CommitResult.EditorRejected -> {
                    Log.w("SprichIME", "controller EditorRejected (ambiguous) — NOT retrying token=$token")
                    false
                }
                is FieldSessionController.CommitResult.AlreadyFinalized -> {
                    Log.w("SprichIME", "AlreadyFinalized token=$token — never direct-commit")
                    false
                }
                is FieldSessionController.CommitResult.StaleSession -> {
                    Log.w("SprichIME", "StaleSession token=$token — never direct-commit")
                    false
                }
                is FieldSessionController.CommitResult.WrongField -> {
                    Log.w("SprichIME", "WrongField token=$token — never direct-commit")
                    false
                }
                is FieldSessionController.CommitResult.NoInputConnection -> {
                    Log.w("SprichIME", "NoInputConnection token=$token — never direct-commit")
                    false
                }
            }
        }
    }

    // Backwards compat for non-token call sites
    private fun applyFinalText(text: String): Boolean {
        val token = currentUtteranceToken ?: return false
        return applyFinalText(token, text)
    }

    private fun failSession(
        generation: Long,
        reason: String,
        userStatus: String,
        userHint: String,
        throwable: Throwable?,
    ) {
        if (generation != sessionGeneration.get()) { staleCallbackDrops++; return }
        if (throwable != null) Log.e("SprichIME", reason, throwable) else Log.e("SprichIME", reason)
        sessionGeneration.incrementAndGet()
        fieldGeneration.incrementAndGet()
        currentFieldId = null
        currentUtteranceToken = null
        activeUtterance = null
        synchronized(finalizedUtterances) { /* keep claimed ids to prevent reuse, but clear old if needed */ }
        try { audio.stop() } catch (_: Exception) {}
        try { engineJob?.cancel() } catch (_: Exception) {}
        try { endpointJob?.cancel() } catch (_: Exception) {}
        try { engine.cancelSession() } catch (_: Exception) {}
        try { composition.discardPartial(currentInputConnection) } catch (_: Exception) {}
        try { fieldController.cancelActive() } catch (_: Exception) {}
        utteranceActive.set(false)
        endpointPending.set(false)
        lastPartialText = ""
        frozenUtterancePcm = null
        try { utteranceAudio.clear() } catch (_: Exception) {}
        try { engine.clearUtteranceCapture() } catch (_: Exception) {}
        try { fastConformerEngine.clearUtteranceCapture() } catch (_: Exception) {}
        session.error(reason)
        statusText?.text = userStatus
        (statusText?.tag as? TextView)?.text = userHint
        writeDiagnostics("error=$reason generation=$generation staleDrops=$staleCallbackDrops claims=$finalizationClaims commits=$finalCommitCount")
    }

    /**
     * UTTERANCE-SCOPED failure — must NOT destroy active B.
     * Used for: editor insertion ambiguous, remote timeout, refinement failure, local blank/error, stale connection.
     * Does NOT increment generation, does NOT clear global PCM collector if B active, does NOT stop audio globally.
     * Only clears state belonging to the failed token itself.
     */
    private fun failUtteranceScoped(token: UtteranceToken, reason: String, throwable: Throwable?) {
        if (throwable != null) Log.w("SprichIME", "utteranceScopedFailure token=$token reason=$reason", throwable) else Log.w("SprichIME", "utteranceScopedFailure token=$token reason=$reason")
        // Do not increment global generation — preserve B's token validity
        // Only clear active capture state if this token still owns it
        maybeClearActiveStateForToken(token)
        // If session was left in Finalizing, return to Listening for next utterance (keep field alive)
        try {
            if (session.state.value is SessionState.Finalizing) session.onListeningAgain()
        } catch (_: Exception) {}
        // Keep fieldId intact — do not clear currentFieldId
        // Do NOT clear utteranceAudio globally if B might be active — maybeClear already guarded
        // Do NOT stop audio if B active — audio is needed for B capture
        // Ensure composition speculative partial is discarded (not committed)
        try { composition.discardPartial(currentInputConnection) } catch (_: Exception) {}
        statusText?.text = "Could not insert"
        (statusText?.tag as? TextView)?.text = reason.take(60)
        writeDiagnostics("utteranceScopedFailure token=$token reason=$reason drops=$staleCallbackDrops claims=$finalizationClaims")
    }

    private fun stopDictation(reason: StopReason = StopReason.USER_STOP) {
        val wasActive = isDictationRunning()
        val generationAtStop = sessionGeneration.get()
        Log.i("SprichIME", "stopDictation reason=$reason wasActive=$wasActive generation=$generationAtStop chunks=$pipelineChunkCount pushed=$pipelinePushedSampleCount vadState=${vad.currentState().name} field=$currentFieldId")
        try { startJob?.cancel() } catch (_: Exception) {}
        startJob = null
        try { audio.stop() } catch (_: Exception) {}

        // P1-26: USER_STOP must use current utterance state and current collector PCM, not cumulative counter
        val currentPcmSize = try { utteranceAudio.size() } catch (_: Exception) { 0 }
        val hasActiveUtterance = activeUtterance != null && utteranceActive.get() && currentUtteranceToken != null
        val notYetFinalized = currentUtteranceToken?.let { !finalizedUtterances.contains(it.utteranceId) } ?: true
        val shouldCommitAsFinalizing = when (reason) {
            StopReason.USER_STOP -> wasActive && hasActiveUtterance && currentPcmSize > 8000 && notYetFinalized
            StopReason.ENDPOINT -> false // endpoint path uses finalizeOnce directly, not stopDictation
            else -> false
        }

        // USER_STOP must be FIFO-serialized with ENDPOINT via same finalization actor — no direct finalizePending call that bypasses queue
        if (reason == StopReason.USER_STOP) {
            // Stop accepting new mic input immediately, but do NOT invalidate already queued work
            val shouldEnqueueFinal = shouldCommitAsFinalizing
            if (shouldEnqueueFinal) {
                val token = currentUtteranceToken ?: UtteranceToken(session.sessionId, generationAtStop, utteranceIdCounter.get(), currentFieldId, fieldGeneration.get(), try { currentInputConnection } catch (_: Exception) { null })
                // Freeze current PCM snapshot — immutable, isolated copy
                val snap = try { utteranceAudio.snapshot() } catch (_: Exception) { ShortArray(0) }
                if (snap.isNotEmpty()) {
                    frozenUtterancePcm = snap.copyOf()
                }
                val captured = activeUtterance?.takeIf { it.token.utteranceId == token.utteranceId }
                val pendingPlan = captured?.plan ?: buildUtterancePlan()
                val pendingRoute = captured?.localRoute ?: when (val tp = pendingPlan.transcription) {
                    is TranscriptionPlan.Local -> tp.route
                    is TranscriptionPlan.ApiPrimary -> tp.localFallback ?: determineRoute(speechLanguage)
                    is TranscriptionPlan.LocalApiFallback -> tp.local
                }
                val pendingConfig = captured?.speechConfig ?: pendingPlan.speechConfig
                val pending = PendingUtterance(
                    token = token,
                    pcm = snap.copyOf(),
                    config = pendingConfig,
                    route = pendingRoute,
                    plan = pendingPlan,
                    pushedSamples = pipelinePushedSampleCount,
                    reason = StopReason.USER_STOP,
                    endpointTimestampNanos = System.nanoTime(),
                )
                enqueuePending(pending)
                Log.i("SprichIME", "USER_STOP enqueued final token=$token queueDepth=${queueDepth.get()} snapSamples=${snap.size}")
            }
            // If queue empty and no final to enqueue, terminate immediately; else await drain via actor
            if (queueDepth.get() == 0 && !shouldEnqueueFinal) {
                val newGen = sessionGeneration.incrementAndGet()
                fieldGeneration.incrementAndGet()
                try { engineJob?.cancel() } catch (_: Exception) {}
                engineJob = null
                endpointJob = null
                try { engine.cancelSession() } catch (_: Exception) {}
                try { fastConformerEngine.cancelSession() } catch (_: Exception) {}
                try { engine.clearUtteranceCapture() } catch (_: Exception) {}
                try { fastConformerEngine.clearUtteranceCapture() } catch (_: Exception) {}
                utteranceActive.set(false)
                endpointPending.set(false)
                lastPartialText = ""
                frozenUtterancePcm = null
                currentUtteranceToken = null
                activeUtterance = null
                pipelineChunkCount = 0L; pipelineSampleCount = 0L; pipelinePushedSampleCount = 0L
                vad.reset()
                lastVadState = Vad.State.SILENCE
                try { composition.discardPartial(currentInputConnection) } catch (_: Exception) {}
                try { if (session.state.value !is SessionState.Idle) session.end() } catch (_: Exception) { try { session.idle() } catch (_: Exception) {} }
                updateImeUi(false)
                writeDiagnostics("stopped USER_STOP immediate (no queue) prevGen=$generationAtStop newGen=$newGen claims=$finalizationClaims")
            } else {
                // Enqueued — mark stop after drain, preserve FIFO order, no generation bump yet
                stopRequested = true
                stopRequestedGeneration = generationAtStop
                statusText?.text = "Stopping…"
                (statusText?.tag as? TextView)?.text = "Finishing ${queueDepth.get()} utterance(s) then idle"
                Log.i("SprichIME", "USER_STOP awaiting drain queueDepth=${queueDepth.get()} generation=$generationAtStop enqueuedFinal=$shouldEnqueueFinal")
                writeDiagnostics("USER_STOP awaiting drain queueDepth=${queueDepth.get()} generation=$generationAtStop enqueuedFinal=$shouldEnqueueFinal")
            }
            if (wasActive) vibrateStop()
            return
        }

        // Non-finalizing stops: advance generation IMMEDIATELY to make old tokens permanently stale
        val newGeneration = sessionGeneration.incrementAndGet()
        fieldGeneration.incrementAndGet()
        // Cancel any pending endpoint finalization immediately — never insert after cancellation
        try { endpointJob?.cancel() } catch (_: Exception) {}
        endpointJob = null
        // For FIELD_LOST etc., field is no longer owned
        currentFieldId = when (reason) {
            StopReason.FIELD_LOST, StopReason.INPUT_RESTARTED, StopReason.WINDOW_HIDDEN,
            StopReason.PASSWORD_FIELD, StopReason.ERROR, StopReason.SERVICE_DESTROYED -> null
            else -> currentFieldId
        }
        currentUtteranceToken = null
        activeUtterance = null
        try { engineJob?.cancel() } catch (_: Exception) {}
        engineJob = null
        try { engine.cancelSession() } catch (_: Exception) {}
        try { fastConformerEngine.cancelSession() } catch (_: Exception) {}
        try { engine.clearUtteranceCapture() } catch (_: Exception) {}
        try { fastConformerEngine.clearUtteranceCapture() } catch (_: Exception) {}
        try { utteranceAudio.clear() } catch (_: Exception) {}
        utteranceActive.set(false)
        endpointPending.set(false)
        lastPartialText = ""
        frozenUtterancePcm = null
        pipelineChunkCount = 0L; pipelineSampleCount = 0L; pipelinePushedSampleCount = 0L
        vad.reset()
        lastVadState = Vad.State.SILENCE
        // Must discard speculative partial, not commit it
        try { composition.discardPartial(currentInputConnection) } catch (_: Exception) { try { composition.finishIfActive(currentInputConnection) } catch (_: Exception) {} }
        try { if (session.state.value !is SessionState.Idle) session.end() } catch (_: Exception) { try { session.idle() } catch (_: Exception) {} }
        // Also inform fieldController for stale protection (uses discard)
        try { fieldController.cancelActive() } catch (_: Exception) {}
        updateImeUi(false)
        if (wasActive) vibrateStop()
        Log.i("SprichIME", "dictation stopped reason=$reason newGen=$newGeneration field=$currentFieldId")
        writeDiagnostics("stopped reason=$reason newGen=$newGeneration fieldGen=${fieldGeneration.get()} claims=$finalizationClaims commits=$finalCommitCount drops=$staleCallbackDrops")
    }

    private fun writeDiagnostics(event: String) {
        val routeStr = try { determineRoute(speechLanguage).toString() } catch (_: Exception) { "unknown" }
        val collectorState = "collectorSamples=${utteranceAudio.size()} frozen=${utteranceAudio.isFrozen()} canaryLoads=${canaryLoadAttempts.get()} fastLoads=${fastLoadAttempts.get()}"
        val text = Diagnostics.collect(this, engine.engineId, languageTag = activeConfig.resolvedLanguageTag(), task = activeConfig.resolvedTask().name, sessionId = session.sessionId) +
            event + " route=$routeStr $collectorState\n" +
            latency.report() + "\n" +
            "audioActive=${audio.isActive()} vad=${vad.currentState()} engineLoaded=${engine.isLoaded()} fastLoaded=${fastConformerEngine.isLoaded()} lidLoaded=${lidEngine.isLoaded()} sessionId=${session.sessionId}\n"
        scope.launch(Dispatchers.IO) {
            try { Diagnostics.write(this@SprichIME, text) } catch (_: Exception) {}
        }
    }

    private fun switchToNextKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showInputMethodPicker()
        } catch (_: Exception) {}
    }

    private fun vibrateTick() {
        if (!hapticsEnabled) return
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                getSystemService(Vibrator::class.java)
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(20)
            }
        } catch (_: Exception) {}
    }
    private fun vibrateStop() {
        if (!hapticsEnabled) return
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                getSystemService(Vibrator::class.java)
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(30)
            }
        } catch (_: Exception) {}
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---- Swipe-to-delete editing -------------------------------------------------

    /** Deletes the last word before the cursor. Returns true when something was deleted. */
    private fun deleteLastWord(): Boolean {
        val ic = currentInputConnection ?: return false
        return try {
            ic.beginBatchEdit()
            try { composition.discardPartial(ic) } catch (_: Exception) {}
            val before = try { ic.getTextBeforeCursor(160, 0)?.toString() } catch (_: Exception) { null }.orEmpty()
            if (before.isEmpty()) {
                // Editors that block getTextBeforeCursor: fall back to one key event per repeat tick.
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL))
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL))
                ic.endBatchEdit()
                vibrateTick()
                Log.i("SprichIME", "swipeDelete fallback DEL")
                return true
            }
            val trimmed = before.trimEnd()
            if (trimmed.isEmpty()) {
                ic.deleteSurroundingText(before.length, 0)
                ic.endBatchEdit()
                return true
            }
            var i = trimmed.length - 1
            while (i >= 0 && !trimmed[i].isWhitespace()) i--
            while (i >= 0 && trimmed[i].isWhitespace()) i--
            val keepLen = i + 1
            val toDelete = (before.length - keepLen).coerceIn(1, before.length)
            // ONE irreversible delete — never retry with key event. Hostile editor may delete and return false; retry would duplicate deletion.
            val ok = try { ic.deleteSurroundingText(toDelete, 0) } catch (_: Exception) { false }
            if (!ok) {
                Log.w("SprichIME", "deleteSurroundingText ambiguous (returned false) — NOT sending fallback DEL to avoid duplicate deletion chars=$toDelete")
            } else if (hapticsEnabled) {
                vibrateTick()
            }
            undoStack.addLast(trimmed.substring(i + 1).trim())
            if (undoStack.size > 10) undoStack.removeFirst()
            Log.i("SprichIME", "swipeDelete chars=$toDelete undoDepth=${undoStack.size}")
            ic.endBatchEdit()
            true
        } catch (t: Throwable) {
            Log.w("SprichIME", "swipeDelete failed", t)
            try { ic.endBatchEdit() } catch (_: Exception) {}
            false
        }
    }

    /** Restores the most recently swipe-deleted word at the cursor. */
    private fun undoLastDelete() {
        val word = undoStack.removeLastOrNull() ?: return
        val ic = currentInputConnection ?: return
        try {
            val text = if (word.isNotBlank()) " $word" else word
            ic.commitText(text, 1)
            Log.i("SprichIME", "swipeUndo chars=${word.length} remaining=${undoStack.size}")
        } catch (_: Exception) {}
    }

    /** While the finger stays down after a swipe, keeps deleting words with accelerating cadence. */
    private fun startDeleteRepeat() {
        deleteRepeatJob?.cancel()
        deleteRepeatJob = scope.launch {
            delay(400)
            var delayMs = 280L
            while (isActive && isSwipeDelete) {
                if (!deleteLastWord()) break
                delayMs = maxOf(120L, delayMs - 15L)
                delay(delayMs)
            }
        }
    }

    private fun stopDeleteRepeat() {
        try { deleteRepeatJob?.cancel() } catch (_: Exception) {}
        deleteRepeatJob = null
    }

    private fun vibrateHeavy() {
        if (!hapticsEnabled) return
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                getSystemService(Vibrator::class.java)
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(40)
            }
        } catch (_: Exception) {}
    }

    // ---- Liquid wow visual (color + width follow mic energy) ----------------------

    private fun lerpColor(a: Int, b: Int, f: Float): Int {
        val ar = (a shr 16) and 0xff; val ag = (a shr 8) and 0xff; val ab = a and 0xff
        val br = (b shr 16) and 0xff; val bg2 = (b shr 8) and 0xff; val bb = b and 0xff
        val r = (ar + (br - ar) * f).toInt().coerceIn(0, 255)
        val g = (ag + (bg2 - ag) * f).toInt().coerceIn(0, 255)
        val bch = (ab + (bb - ab) * f).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or bch
    }

    /** Pleasure ramp: silence soft grey-violet -> coral -> hot pink -> electric magenta. */
    private fun gradientForRms(rms: Float): IntArray {
        // Map 0.0008..0.05 RMS -> t 0..1 (log-ish feel via sqrt for perceptual response)
        val raw = ((rms - 0.0008f) / (0.05f - 0.0008f)).coerceIn(0f, 1f)
        val t = kotlin.math.sqrt(raw)
        val calm = Color.parseColor("#B9B3C9")
        val coral = Color.parseColor("#FF7A67")
        val pink = Color.parseColor("#FF4D76")
        val magenta = Color.parseColor("#E92B8E")
        val c0: Int; val c1: Int; val c2: Int
        if (t < 0.5f) {
            val u = (t / 0.5f)
            c0 = lerpColor(calm, coral, u); c1 = lerpColor(coral, pink, u); c2 = lerpColor(pink, magenta, u * 0.6f)
        } else {
            val u = ((t - 0.5f) / 0.5f)
            c0 = lerpColor(coral, pink, u); c1 = lerpColor(pink, magenta, u); c2 = magenta
        }
        return intArrayOf(c0, c1, c2)
    }

    private fun strokeForRms(t: Float): Int {
        val dark = isDark()
        val base = if (dark) Color.parseColor("#2A2A2A") else Color.parseColor("#E8E8E8")
        val hot = Color.parseColor("#FF4D76")
        return lerpColor(base, hot, t.coerceIn(0f, 1f))
    }

    /** Applies rms to bar, glow, aura and pill stroke on Main thread. Properties only — no requestLayout. */
    private fun updateLiquidVisual(rms: Float) {
        try {
            val colors = gradientForRms(rms)
            liquidBg?.colors = colors
            glowBg?.colors = colors
            val raw = ((rms - 0.0008f) / (0.05f - 0.0008f)).coerceIn(0f, 1f)
            val t = kotlin.math.sqrt(raw)
            dotView?.animate()?.cancel()
            dotView?.scaleX = (0.3f + 1.7f * t).coerceIn(0.3f, 2.0f)
            dotView?.alpha = (0.8f + 0.2f * raw).coerceIn(0.8f, 1f)
            glowView?.let { g ->
                g.animate()?.cancel()
                val s = 1.6f + 1.8f * t
                g.scaleX = s; g.scaleY = s
                g.alpha = (0.10f + 0.55f * raw).coerceIn(0f, 0.65f)
            }
            auraView?.let { a ->
                a.animate()?.cancel()
                a.alpha = (0.08f + 0.30f * raw).coerceIn(0f, 0.38f)
                val s = 1f + 0.06f * t
                a.scaleX = s; a.scaleY = s
            }
            pillBgRef?.setStroke(dp(1), strokeForRms(t))
        } catch (_: Exception) {}
    }

    /** One bright flash + soft pop the moment dictated text lands — the reward beat. */
    private fun celebrateCommit() {
        scope.launch(Dispatchers.Main) {
            try {
                val hot = intArrayOf(Color.parseColor("#FF7A67"), Color.parseColor("#FF4D76"), Color.parseColor("#E92B8E"))
                liquidBg?.colors = hot
                glowBg?.colors = hot
                dotView?.let { bar ->
                    bar.animate()?.cancel()
                    bar.scaleX = 1.8f
                    bar.animate().scaleX(0.9f).setDuration(340).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
                glowView?.let { g ->
                    g.animate()?.cancel()
                    g.scaleX = 3.0f; g.scaleY = 3.0f
                    g.alpha = 0.7f
                    g.animate().alpha(0.12f).scaleX(1.6f).scaleY(1.6f).setDuration(420)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
                auraView?.let { a ->
                    a.animate()?.cancel()
                    a.alpha = 0.4f
                    a.animate().alpha(0f).setDuration(500).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
                if (hapticsEnabled) vibrateTick()
            } catch (_: Exception) {}
        }
    }


    // ---------- Route determination (single source, based on speechLanguage) ----------
    private fun determineRoute(speechLang: com.sprich.app.speech.api.SpeechLanguage): LocalAsrRoute {
        return when (speechLang) {
            is com.sprich.app.speech.api.SpeechLanguage.Auto -> LocalAsrRoute.AutomaticFastConformer
            is com.sprich.app.speech.api.SpeechLanguage.Fixed -> {
                val legacy = speechLang.toLegacyLanguage()
                // Explicit AUTO from legacy (should not happen when Fixed, but guard)
                if (legacy == com.sprich.app.speech.api.Language.AUTO) LocalAsrRoute.AutomaticFastConformer
                else LocalAsrRoute.AccurateCanary(legacy)
            }
        }
    }

    // Strict HTTPS validation — centralized via EndpointValidator (P1 centralization)
    private fun isValidHttpsUrl(url: String): Boolean = com.sprich.app.core.security.EndpointValidator.isValidHttpsUrl(url)

    private fun buildRemoteSttConfig(): RemoteSttConfig? {
        // P1-15: No runBlocking, no DataStore reads on audio hot path — use in-memory snapshots
        if (!isValidHttpsUrl(sttBaseUrlState)) return null
        if (sttModelState.isBlank()) return null
        val credRef = sttCredentialRefState.ifBlank { "stt_default" }
        val langPolicy = LanguagePolicy.fromSpeechLanguage(speechLanguage)
        return RemoteSttConfig(
            providerId = sttProviderId,
            endpoint = sttBaseUrlState,
            model = sttModelState,
            languagePolicy = langPolicy,
            deadlineMs = sttDeadlineMsState,
            credentialRef = credRef,
            supportsStreaming = sttProviderId == "meta-muse",
        )
    }

    private fun buildRefinementConfig(mode: RefinementMode): RefinementConfig? {
        if (mode == RefinementMode.OFF) return null
        if (!isValidHttpsUrl(refinementBaseUrlState) || refinementModelState.isBlank()) return null
        val credRef = refinementCredentialRefState.ifBlank { "refine_default" }
        return RefinementConfig(
            providerId = refinementProviderIdState, // P0-14: use real refinement provider ID, not STT provider
            endpoint = refinementBaseUrlState,
            model = refinementModelState,
            mode = mode,
            deadlineMs = refinementDeadlineMsState,
            credentialRef = credRef,
        )
    }

    private fun buildUtterancePlan(): UtterancePlan {
        val localRoute = determineRoute(speechLanguage)
        val transcription: TranscriptionPlan = when (transcriptionMode) {
            TranscriptionMode.ON_DEVICE -> TranscriptionPlan.Local(localRoute)
            TranscriptionMode.API_PRIMARY -> {
                val remote = buildRemoteSttConfig()
                if (remote != null) TranscriptionPlan.ApiPrimary(remote, localRoute) else TranscriptionPlan.Local(localRoute)
            }
            TranscriptionMode.LOCAL_API_FALLBACK -> {
                val remote = buildRemoteSttConfig() ?: return UtterancePlan(TranscriptionPlan.Local(localRoute), buildRefinementPlan(), activeConfig.copy())
                TranscriptionPlan.LocalApiFallback(localRoute, remote)
            }
        }
        val refinement = buildRefinementPlan()
        return UtterancePlan(transcription, refinement, activeConfig.copy())
    }

    private fun buildRefinementPlan(): RefinementPlan {
        return when (refinementMode) {
            RefinementMode.OFF -> RefinementPlan.Off
            else -> {
                val cfg = buildRefinementConfig(refinementMode) ?: return RefinementPlan.Off
                RefinementPlan.Enabled(cfg, refinementMode)
            }
        }
    }

    private fun ensureTranscriptionCoordinator(): TranscriptionCoordinator {
        transcriptionCoordinator?.let { return it }
        if (localCoordinator == null) localCoordinator = LocalTranscriptionCoordinator(lidEngine, fastConformerEngine, engine)
        // Build provider map — shared HttpClient reused (P0-18: pooled connections, keep-alive, HTTP/2)
        val providers = mutableMapOf<String, RemoteSttProvider>()
        // OpenAI-compatible provider if configured — strict HTTPS validation
        try {
            if (isValidHttpsUrl(sttBaseUrlState) && sttModelState.isNotBlank()) {
                // Use sharedClient.newBuilder() to share ConnectionPool/Dispatcher where OkHttp semantics permit
                val client = sharedHttpClient.newBuilder().build()
                providers["openai-compatible"] = OpenAiCompatibleSttProvider(sttBaseUrlState, sttModelState, client)
                providers[sttProviderId] = providers["openai-compatible"]!!
            }
        } catch (_: Exception) {}
        // Mock for tests
        if (providers.isEmpty()) {
            providers["mock"] = MockRemoteSttProvider()
        }
        val coord = TranscriptionCoordinator(localCoordinator!!, providers, apiSecretStore, DeadlinePolicy.DEFAULT, sharedHttpClient)
        transcriptionCoordinator = coord
        return coord
    }

    // P0-13: Refinement must use frozen pending.plan.refinement.config only — never global mutable state nor permanently cached old provider
    private fun providerForRefinementConfig(cfg: com.sprich.app.speech.refinement.RefinementConfig): TranscriptRefinementProvider? {
        val secret = try { apiSecretStore.loadSecret(cfg.credentialRef) ?: "" } catch (_: Exception) { "" }
        if (secret.isBlank()) return null
        if (!isValidHttpsUrl(cfg.endpoint) || cfg.model.isBlank()) return null
        // Create fresh provider sharing connection pool — cheap to recreate, avoids stale cached provider tied to old Settings
        val client = sharedHttpClient.newBuilder().build()
        return OpenAiCompatibleRefinementProvider(cfg.endpoint, cfg.model, secret, client)
    }

    @Deprecated("Use providerForRefinementConfig with immutable plan config")
    private fun ensureRefinementProvider(): TranscriptRefinementProvider? {
        if (refinementMode == RefinementMode.OFF) return null
        val cfg = buildRefinementConfig(refinementMode) ?: return null
        return providerForRefinementConfig(cfg)
    }

    private fun isAutomaticReadyForTest(): Boolean {
        return try { com.sprich.app.models.manager.ModelManager(this).isAutomaticReady() } catch (_: Exception) { false }
    }

    private fun maybeUnloadUnused(activeRoute: LocalAsrRoute) {
        // Avoid keeping both heavy stacks resident — unload unused when queue drained.
        if (queueDepth.get() != 0) return // pending work — do not unload while finalizing
        when (activeRoute) {
            is LocalAsrRoute.AutomaticFastConformer -> {
                // Automatic keeps LID+Fast, unload Canary if idle
                if (engine.isLoaded()) {
                    scope.launch { try { engine.unload() } catch (_: Exception) {} }
                    Log.i("SprichIME", "maybeUnload: Automatic active — unloading Canary to save memory")
                }
            }
            is LocalAsrRoute.AccurateCanary -> {
                // Accurate keeps Canary, may unload LID/Fast after safe transition
                if (lidEngine.isLoaded() || fastConformerEngine.isLoaded()) {
                    scope.launch {
                        try { lidEngine.unload() } catch (_: Exception) {}
                        try { fastConformerEngine.unload() } catch (_: Exception) {}
                    }
                    Log.i("SprichIME", "maybeUnload: Accurate active — unloading LID/Fast")
                }
            }
        }
    }

    // Visibility for tests — gate assertions
    fun getCanaryLoadAttemptsForTest(): Long = canaryLoadAttempts.get()
    fun getFastLoadAttemptsForTest(): Long = fastLoadAttempts.get()
    fun getLidLoadAttemptsForTest(): Long = lidLoadAttempts.get()
    fun getLocalNativeDecodeStartsForTest(): Long = localNativeDecodeStarts
    fun getRemoteTranscriptionStartsForTest(): Long = remoteTranscriptionStarts
    fun getRefinementStartsForTest(): Long = refinementStarts
    fun getNativeDecodeStartsForTest(): Long = nativeDecodeStarts
    fun getLastRemoteFailureForTest(): com.sprich.app.speech.remote.ApiFailure? = try { transcriptionCoordinator?.lastRemoteFailure } catch (_: Exception) { null }
    fun isAutomaticRouteForTest(): Boolean = determineRoute(speechLanguage) is LocalAsrRoute.AutomaticFastConformer
    fun getCurrentRouteForTest(): String = determineRoute(speechLanguage).toString()
    fun getUtteranceAudioCollectorForTest(): UtteranceAudioCollector = utteranceAudio
    fun isCanaryLoadedForTest(): Boolean = try { engine.isLoaded() } catch (_: Exception) { false }
    fun isFastLoadedForTest(): Boolean = try { fastConformerEngine.isLoaded() } catch (_: Exception) { false }
    fun isLidLoadedForTest(): Boolean = try { lidEngine.isLoaded() } catch (_: Exception) { false }

    // Overload for ResolvedUtteranceLanguage post-processing
    private fun applyFinalText(token: UtteranceToken, text: String, resolved: ResolvedUtteranceLanguage): Boolean {
        val inputConnection = currentInputConnection ?: return false
        if (token.generation != sessionGeneration.get()) { staleCallbackDrops++; return false }
        if (token.sessionId != session.sessionId || !session.isSessionValid(token.sessionId)) { staleCallbackDrops++; return false }
        if (token.fieldId != currentFieldId || token.fieldGeneration != fieldGeneration.get()) { staleCallbackDrops++; return false }
        if (!fieldController.isCurrentSession(token.sessionId)) { staleCallbackDrops++; return false }
        if (inputConnection != token.capturedIc && token.capturedIc != null) {
            if (inputConnection.hashCode() != currentFieldTokenIcHash && currentFieldTokenIcHash != 0) {
                Log.w("SprichIME", "applyFinalText IC mismatch token=$token")
                staleCallbackDrops++
                return false
            }
        }
        val parsed = try {
            com.sprich.app.input.commands.SpokenEditingParser.parse(text, resolved, commandsEnabled)
        } catch (_: Exception) {
            com.sprich.app.input.commands.SpokenEditingParser.EditResult(text, false)
        }
        return if (com.sprich.app.input.commands.SpokenEditingParser.isDeleteCommand(parsed.text)) {
            val toDelete = if (parsed.text == "__DELETE_SENTENCE__") 120 else 40
            val deleted = inputConnection.deleteSurroundingText(toDelete, 0)
            try { fieldController.commitUtterance(token.sessionId, token.utteranceId, inputConnection, "") } catch (_: Exception) {}
            try { composition.discardPartial(inputConnection) } catch (_: Exception) { composition.finishIfActive(inputConnection) }
            deleted
        } else {
            val finalText = try { vocabStore.apply(parsed.text) } catch (_: Exception) { parsed.text }
            val result = try { fieldController.commitUtteranceTyped(token.sessionId, token.utteranceId, inputConnection, finalText) } catch (_: Exception) { FieldSessionController.CommitResult.StaleSession }
            return when (result) {
                is FieldSessionController.CommitResult.Committed -> true
                is FieldSessionController.CommitResult.EditorRejected -> {
                    Log.w("SprichIME", "controller EditorRejected (ambiguous) — NOT retrying token=$token")
                    false
                }
                is FieldSessionController.CommitResult.AlreadyFinalized -> {
                    Log.w("SprichIME", "AlreadyFinalized token=$token — never direct-commit")
                    false
                }
                is FieldSessionController.CommitResult.StaleSession -> {
                    Log.w("SprichIME", "StaleSession token=$token — never direct-commit")
                    false
                }
                is FieldSessionController.CommitResult.WrongField -> {
                    Log.w("SprichIME", "WrongField token=$token — never direct-commit")
                    false
                }
                is FieldSessionController.CommitResult.NoInputConnection -> {
                    Log.w("SprichIME", "NoInputConnection token=$token — never direct-commit")
                    false
                }
            }
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000L
        private const val PRE_ROLL_MS = 400
    }
}
