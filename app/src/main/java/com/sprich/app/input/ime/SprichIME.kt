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
import com.sprich.app.speech.remote.DeadlinePolicy
import com.sprich.app.speech.refinement.RefinementMode
import com.sprich.app.speech.refinement.RefinementConfig
import com.sprich.app.speech.refinement.TranscriptRefinementProvider
import com.sprich.app.speech.refinement.OpenAiCompatibleRefinementProvider
import com.sprich.app.speech.refinement.RefinementValidator
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
    private lateinit var speechPresence: com.sprich.app.core.vad.SpeechPresence

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
    )
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
    private val instantMode get() = runtimeConfig?.instantMode ?: false
    // Single canonical language state — SpeechLanguage is authoritative; Language is derived synchronously.
    // Collecting both Language and SpeechLanguage independently causes split-brain (DE vs EN). Only collect SpeechLanguage.
    private val speechLanguage get() = runtimeConfig?.speechLanguage ?: SpeechLanguage.Auto
    private val language: Language get() = speechLanguage.toLegacyLanguage()
    private val commandsEnabled get() = runtimeConfig?.commandsEnabled ?: false
    private var isPasswordField: Boolean = false
    private lateinit var vocabRepo: com.sprich.app.vocab.VocabRepository
    private val vocabStore get() = if (::vocabRepo.isInitialized) vocabRepo.store() else com.sprich.app.vocab.PersonalVocabStore()
    private val hapticsEnabled get() = runtimeConfig?.hapticsEnabled ?: true
    // Single atomic runtime config — one DataStore emission → one immutable snapshot, StateFlow for readiness gate
    @Volatile private var runtimeConfig: com.sprich.app.storage.RuntimeConfigSnapshot? = null
    private val runtimeConfigFlow = kotlinx.coroutines.flow.MutableStateFlow<com.sprich.app.storage.RuntimeConfigSnapshot?>(null)
    // Derived legacy mirrors for minimal diff — always reflect runtimeConfig when present
    private val transcriptionMode: TranscriptionMode
        get() = runtimeConfig?.transcriptionMode ?: TranscriptionMode.ON_DEVICE
    private val refinementMode: RefinementMode
        get() = runtimeConfig?.refinementMode ?: RefinementMode.OFF
    private val sttProviderId: String
        get() = runtimeConfig?.sttProviderId ?: "meta-muse-voice-transcribe"
    private val sttBaseUrlState: String
        get() = runtimeConfig?.sttBaseUrl ?: ""
    private val personalVocabHintEnabled: Boolean
        get() = runtimeConfig?.personalVocabHintEnabled ?: false
    // Editor action owner — single authority for delete/undo, spoken delete, history, password policy
    private val editorActionController = EditorActionController()
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
    // Swipe editing state: axis-locked, thresholds, one mutation per gesture (minimal 3-gesture release)
    private var downX = 0f
    private var downY = 0f
    private var isSwipeDelete = false
    private var isSwipeUndo = false
    private var touchAxisLocked: String? = null // "h" or "v"
    private val touchSlop by lazy { android.view.ViewConfiguration.get(this).scaledTouchSlop }
    private val swipeDeleteThreshold by lazy { (48 * resources.displayMetrics.density) } // 48dp per closure sprint
    private val swipeSwitchThreshold by lazy { (56 * resources.displayMetrics.density) } // 56dp, empty-area only
    private var outerViewRef: View? = null
    private var pillViewRef: View? = null
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
            audio = AudioCapture(ringSeconds = 1)
            vad = Vad()
            speechPresence = com.sprich.app.core.vad.SpeechPresence(assets)
            engine = CanaryEngine(this, com.sprich.app.models.manager.ModelManager(this))
            // Neutral collector requires no engine ownership; coordinator will be created after engines lazy init
            scope.launch { try { vocabRepo.load() } catch (_: Exception) {} }

            // Single atomic runtime config — one DataStore emission → one immutable snapshot, no combine of async fields
            scope.launch {
                try {
                    prefs.runtimeConfigSnapshot.collect { snap ->
                        runtimeConfig = snap
                        runtimeConfigFlow.value = snap
                        Log.i("SprichIME", "runtimeConfig snapshot $snap")
                    }
                } catch (e: Exception) { Log.w("SprichIME", "runtimeConfig collect fail", e) }
            }
            // One-time legacy credential migration (P0-3 fail-closed)
            scope.launch { try { com.sprich.app.storage.LegacyApiCredentialMigrator.migrateIfNeeded(prefs, ApiSecretStore(this@SprichIME)) } catch (e: Exception) { Log.w("SprichIME", "legacy migration failed", e) } }
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
                            statusText?.text = getString(com.sprich.app.R.string.ime_preparing)
                            (statusText?.tag as? TextView)?.text = getString(com.sprich.app.R.string.ime_loading_hint)
                        }
                        is SessionState.Finalizing -> {
                            updateImeUi(true)
                            statusText?.text = getString(com.sprich.app.R.string.ime_transcribing)
                            (statusText?.tag as? TextView)?.text = getString(com.sprich.app.R.string.ime_writing_hint)
                        }
                        is SessionState.Error -> Unit // failSession owns the actionable message.
                        else -> updateImeUi(active)
                    }
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
            val imeBackground = Color.parseColor(if (dark) "#121212" else "#F7F7F7")
            window?.window?.let { imeWindow ->
                imeWindow.navigationBarColor = imeBackground
                androidx.core.view.WindowInsetsControllerCompat(imeWindow, imeWindow.decorView)
                    .isAppearanceLightNavigationBars = !dark
            }
            // A known background keeps system keyboard/hide controls readable over any editor.
            val outer = FrameLayout(this).apply {
                setBackgroundColor(imeBackground)
                // Handle navigation bar / gesture inset so pill never sits on system bar
                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                    val navBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
                    val bottomInset = navBottom
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
                minimumHeight = dp(72)
                setPadding(dp(12), dp(12), dp(4), dp(12))
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER
                    marginStart = dp(16)
                    marginEnd = dp(16)
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
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                isClickable = false
            }
            val statusColor = if (dark) Color.parseColor("#F5F5F3") else Color.parseColor("#111111")
            val hintColor = if (dark) Color.parseColor("#A0A0A0") else Color.parseColor("#595959")
            val status = TextView(this).apply {
                text = getString(com.sprich.app.R.string.ime_start)
                setTextColor(statusColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
                isSingleLine = false
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val hint = TextView(this).apply {
                text = getString(com.sprich.app.R.string.ime_idle_hint)
                setTextColor(hintColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                isSingleLine = false
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
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
            // Glow: same gradient behind the bar, scaled up — neon halo, no blur cost (palette-aware init)
            val initIsApi = isApiPalette()
            val glow = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(3)).apply {
                    gravity = Gravity.CENTER
                }
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(2).toFloat()
                    colors = gradientForRms(0f, initIsApi)
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
                    colors = gradientForRms(0f, initIsApi)
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

            // Aura: soft radial halo behind the whole pill, breathes with mic energy (palette-aware)
            val auraIsApi = isApiPalette()
            val aura = View(this).apply {
                val bg = GradientDrawable().apply {
                    gradientType = GradientDrawable.RADIAL_GRADIENT
                    setGradientCenter(0.5f, 0.5f)
                    colors = auraColorsFor(auraIsApi)
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

            // Minimal gesture language: pill swipe left→delete word, right→undo, tap→dictation. One mutation per gesture, reversible.
            // Outer empty-area swipe up (outside pill, above nav inset) → previous keyboard then next fallback.
            outerViewRef = outer
            pillViewRef = pill
            // Pill: only horizontal delete/undo + tap, no vertical, no repeat, no newline
            var pillTouchActive = false
            pill.setOnTouchListener { v, ev ->
                if (getSystemService(android.view.accessibility.AccessibilityManager::class.java).isTouchExplorationEnabled) return@setOnTouchListener false
                if (ev.pointerCount != 1 || ev.actionMasked == android.view.MotionEvent.ACTION_POINTER_UP) {
                    pillTouchActive = false
                    touchAxisLocked = null
                    return@setOnTouchListener true
                }
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        pillTouchActive = true
                        downX = ev.x; downY = ev.y
                        isSwipeDelete = false; isSwipeUndo = false
                        touchAxisLocked = null
                        // Immediate non-destructive press visual allowed
                        try {
                            v.animate().cancel()
                            v.scaleX = 1f; v.scaleY = 1f
                            v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(80).setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction {
                                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                            }.start()
                        } catch (_: Exception) {}
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        val absDx = kotlin.math.abs(dx); val absDy = kotlin.math.abs(dy)
                        if (touchAxisLocked == null && (absDx > touchSlop || absDy > touchSlop)) {
                            touchAxisLocked = if (absDx > absDy * 1.4f) "h" else if (absDy > absDx * 1.4f) "v" else null
                        }
                        // No irreversible mutation on MOVE — only lock axis
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val accepted = pillTouchActive
                        pillTouchActive = false
                        if (!accepted) return@setOnTouchListener true
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        val absDx = kotlin.math.abs(dx); val absDy = kotlin.math.abs(dy)
                        val axis = touchAxisLocked
                        var handled = false
                        // One exactly one action if threshold/axis passes
                        if (axis == "h") {
                            if (dx <= -swipeDeleteThreshold && absDx >= 1.4f * absDy) {
                                isSwipeDelete = true
                                val ok = deleteLastWord() // controller handles password + safe read + haptic only on success
                                if (ok) {
                                    try {
                                        pill.animate().cancel()
                                        pill.scaleX = 0.97f; pill.scaleY = 0.97f
                                        pill.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                                    } catch (_: Exception) {}
                                }
                                handled = true
                            } else if (dx >= swipeDeleteThreshold && absDx >= 1.4f * absDy) {
                                isSwipeUndo = true
                                val ok = undoLastDelete()
                                handled = true
                            }
                        }
                        // Reset state
                        isSwipeDelete = false; isSwipeUndo = false; touchAxisLocked = null
                        if (handled) {
                            true
                        } else {
                            // Check if was tap (no axis or small movement)
                            if (absDx < touchSlop && absDy < touchSlop) {
                                try { v.performClick() } catch (_: Exception) {}
                            }
                            // Swipe-up on pill itself is intentionally NOT handled — must be outside pill
                            true
                        }
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        pillTouchActive = false
                        isSwipeDelete = false; isSwipeUndo = false; touchAxisLocked = null
                        true
                    }
                    else -> false
                }
            }
            // Outer empty-area swipe up: must start inside Sprich's usable IME view, outside central pill, above navigation inset
            var outerDownX = 0f
            var outerDownY = 0f
            var outerAxis: String? = null
            var outerIsSwitchCandidate = false
            outer.setOnTouchListener { _, ev ->
                if (getSystemService(android.view.accessibility.AccessibilityManager::class.java).isTouchExplorationEnabled) return@setOnTouchListener false
                if (ev.pointerCount != 1 || ev.actionMasked == android.view.MotionEvent.ACTION_POINTER_UP) {
                    outerIsSwitchCandidate = false
                    outerAxis = null
                    return@setOnTouchListener false
                }
                // If touch started inside pill bounds, let pill handle it — do not also treat as outer switch
                val pillBounds = android.graphics.Rect()
                try { pill.getHitRect(pillBounds) } catch (_: Exception) {}
                val isInsidePill = { x: Float, y: Float ->
                    try {
                        // Transform pill rect to outer coordinates: pill is child of outer, so its hit rect is in outer's coord
                        pillBounds.contains(x.toInt(), y.toInt())
                    } catch (_: Exception) { false }
                }
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        // Only consider if start outside pill and above navigation inset
                        if (isInsidePill(ev.x, ev.y)) {
                            outerIsSwitchCandidate = false
                            outerAxis = null
                            false // let pill get it
                        } else {
                            // Check above navigation inset — use window insets bottom
                            val navInset = try {
                                androidx.core.view.ViewCompat.getRootWindowInsets(outer)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
                            } catch (_: Exception) { 0 }
                            // Require start at least navInset+12dp above bottom (pill padding already accounts, but empty area must be usable)
                            // Simplified: if y is within 48dp of bottom edge (nav area), ignore
                            val thresholdFromBottom = navInset + dp(12)
                            val outerHeight = outer.height.takeIf { it > 0 } ?: 200
                            if (ev.y > outerHeight - thresholdFromBottom) {
                                outerIsSwitchCandidate = false
                                false
                            } else {
                                outerDownX = ev.x; outerDownY = ev.y
                                outerAxis = null
                                outerIsSwitchCandidate = true
                                true
                            }
                        }
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (!outerIsSwitchCandidate) return@setOnTouchListener false
                        val dx = ev.x - outerDownX
                        val dy = ev.y - outerDownY
                        val absDx = kotlin.math.abs(dx); val absDy = kotlin.math.abs(dy)
                        if (outerAxis == null && (absDx > touchSlop || absDy > touchSlop)) {
                            outerAxis = if (absDx > absDy * 1.4f) "h" else if (absDy > absDx * 1.4f) "v" else null
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!outerIsSwitchCandidate) return@setOnTouchListener false
                        val dx = ev.x - outerDownX
                        val dy = ev.y - outerDownY
                        val absDx = kotlin.math.abs(dx); val absDy = kotlin.math.abs(dy)
                        val wasSwitch = outerAxis == "v" && dy <= -swipeSwitchThreshold && absDy >= 1.4f * absDx
                        outerIsSwitchCandidate = false
                        outerAxis = null
                        if (wasSwitch) {
                            // One irreversible switch — prefer previous IME, then next fallback with token
                            val switched = try { switchToPreviousKeyboard() } catch (_: Exception) { false }
                            val ok = if (switched) true else try { switchToNextKeyboardCompat() } catch (_: Exception) { false }
                            if (!ok) {
                                // No other keyboard enabled — no mutation, show hint
                                try {
                                    android.widget.Toast.makeText(this@SprichIME, getString(com.sprich.app.R.string.ime_no_keyboard), android.widget.Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {}
                            } else {
                                // Haptic only after succeeds, one per action
                                vibrateTick()
                                try {
                                    pill.animate().cancel()
                                    pill.scaleX = 0.98f; pill.scaleY = 0.98f
                                    pill.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                                } catch (_: Exception) {}
                            }
                            true
                        } else {
                            false
                        }
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        outerIsSwitchCandidate = false
                        outerAxis = null
                        false
                    }
                    else -> false
                }
            }
            // TalkBack receives the current state and one state-correct click action.
            androidx.core.view.ViewCompat.setAccessibilityDelegate(pill, object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Button"
                    info.contentDescription = "${status.text}. ${hint.text}"
                    info.removeAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
                    if (!isPasswordField) {
                        info.addAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
                            getString(if (isDictationRunning()) com.sprich.app.R.string.ime_stop_action else com.sprich.app.R.string.ime_start_action)))
                        info.addAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat(com.sprich.app.R.id.action_delete_word, getString(com.sprich.app.R.string.ime_delete_action)))
                        if (editorActionController.historySize() > 0) info.addAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat(com.sprich.app.R.id.action_undo_delete, getString(com.sprich.app.R.string.ime_undo_action)))
                    }
                }
                override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean = when (action) {
                    com.sprich.app.R.id.action_delete_word -> deleteLastWord()
                    com.sprich.app.R.id.action_undo_delete -> undoLastDelete()
                    else -> super.performAccessibilityAction(host, action, args)
                }
            })
            val switchButton = android.widget.ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setImageResource(com.sprich.app.R.drawable.ic_keyboard_switch)
                imageTintList = android.content.res.ColorStateList.valueOf(statusColor)
                val backgroundAttr = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, backgroundAttr, true)
                setBackgroundResource(backgroundAttr.resourceId)
                contentDescription = getString(com.sprich.app.R.string.switch_keyboard)
                setOnClickListener {
                    stopDictation(StopReason.WINDOW_HIDDEN)
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
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
            pill.addView(switchButton)
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

            updateImeUi(isDictationRunning())

            outer
        } catch (e: Exception) {
            Log.e("SprichIME", "onCreateInputView failed, fallback", e)
            TextView(this).apply {
                text = getString(com.sprich.app.R.string.ime_fallback)
                setPadding(dp(16), dp(20), dp(16), dp(24))
                gravity = Gravity.CENTER
                setOnClickListener { toggleDictation() }
            }
        }
    }

    // Single coalesced visual lane — one frame callback, no ValueAnimator infinite, no Math.random
    @Volatile private var visualRms: Float = 0f
    private var visualFrameCallback: android.view.Choreographer.FrameCallback? = null
    private var visualRunning = false

    private fun updateImeUi(isListening: Boolean) {
        try {
            val dark = isDark()
            val hint = statusText?.tag as? TextView
            val statusColor = if (dark) Color.parseColor("#F5F5F3") else Color.parseColor("#111111")
            // Hint reflects active pipeline: API vs Local — adds quiet trust signal without clutter
            val apiHint = isApiPalette()
            if (isPasswordField) {
                stopVisualLane()
                statusText?.text = getString(com.sprich.app.R.string.ime_password)
                hint?.text = getString(com.sprich.app.R.string.ime_password_hint)
                waveform?.visibility = View.INVISIBLE
                micContainer?.contentDescription = getString(com.sprich.app.R.string.ime_password)
                return
            }
            if (isListening) {
                statusText?.text = getString(com.sprich.app.R.string.ime_listening)
                hint?.text = if (apiHint) getString(com.sprich.app.R.string.ime_cloud_listening) else getString(com.sprich.app.R.string.ime_stop_hint)
                statusText?.setTextColor(statusColor)
                micContainer?.contentDescription = if (apiHint) getString(com.sprich.app.R.string.ime_cloud_stop) else getString(com.sprich.app.R.string.ime_stop_action)
                waveform?.visibility = View.VISIBLE
                waveform?.alpha = 0.95f
                glowView?.alpha = 0.12f
                // Apply palette immediately on state change (covers idle→listening mode switch)
                try {
                    val cols = gradientForRms(0f, apiHint)
                    liquidBg?.colors = cols; glowBg?.colors = cols
                    auraView?.background?.let { bg -> (bg as? GradientDrawable)?.colors = auraColorsFor(apiHint) }
                    pillBgRef?.setStroke(dp(1), strokeForRms(0f, apiHint))
                } catch (_: Exception) {}
                visualRms = 0f
                startVisualLane()
            } else {
                stopVisualLane()
                statusText?.text = getString(com.sprich.app.R.string.ime_start)
                hint?.text = getString(com.sprich.app.R.string.ime_idle_hint)
                statusText?.setTextColor(statusColor)
                micContainer?.contentDescription = getString(com.sprich.app.R.string.ime_start)
                micContainer?.animate()?.cancel()
                micContainer?.scaleX = 1f; micContainer?.scaleY = 1f
                waveform?.visibility = View.INVISIBLE
                waveform?.alpha = 0f
                // Reset glow, aura and stroke to calm state (palette-aware, no animate fighting)
                glowView?.alpha = 0f
                glowView?.scaleX = 1.6f; glowView?.scaleY = 1.6f
                auraView?.alpha = 0f
                lastRms = 0f; visualRms = 0f
                val calmIsApi = isApiPalette()
                try {
                    val cols = gradientForRms(0f, calmIsApi)
                    liquidBg?.colors = cols; glowBg?.colors = cols
                    auraView?.background?.let { bg -> (bg as? GradientDrawable)?.colors = auraColorsFor(calmIsApi) }
                } catch (_: Exception) {}
                pillBgRef?.setStroke(dp(1), if (dark) Color.parseColor("#2A2A2A") else Color.parseColor("#E8E8E8"))
            }
        } catch (_: Exception) {}
    }

    private fun startVisualLane() {
        if (visualRunning) return
        visualRunning = true
        val cb = object : android.view.Choreographer.FrameCallback {
            var t0 = android.os.SystemClock.elapsedRealtime()
            var breathPhase = 0f
            override fun doFrame(frameTimeNanos: Long) {
                if (!visualRunning) return
                try {
                    val rms = visualRms
                    val now = android.os.SystemClock.elapsedRealtime()
                    val dt = (now - t0) / 1000f
                    breathPhase += dt * 1.8f // ~0.9Hz breath when silent
                    t0 = now
                    if (rms > 0.0012f) {
                        updateLiquidVisual(rms)
                    } else {
                        // Deterministic breath — sine, not random
                        val breath = 0.5f + 0.5f * kotlin.math.sin(breathPhase * 2f * kotlin.math.PI.toFloat())
                        val s = 0.88f + breath * 0.14f
                        dotView?.scaleX = s
                        dotView?.alpha = 0.88f + breath * 0.12f
                        glowView?.alpha = 0.06f + breath * 0.06f
                        // subtle aura breath
                        auraView?.alpha = 0.04f + breath * 0.04f
                    }
                    // waveform breath when listening silently
                    waveform?.alpha = if (rms > 0.0012f) 0.95f else 0.92f + breathPhase.let { 0.04f * kotlin.math.sin(it) }
                } catch (_: Exception) {}
                try { android.view.Choreographer.getInstance().postFrameCallbackDelayed(this, 33) } catch (_: Exception) {}
            }
        }
        visualFrameCallback = cb
        try { android.view.Choreographer.getInstance().postFrameCallback(cb) } catch (_: Exception) {}
    }

    private fun stopVisualLane() {
        visualRunning = false
        try { visualFrameCallback?.let { android.view.Choreographer.getInstance().removeFrameCallback(it) } } catch (_: Exception) {}
        visualFrameCallback = null
        try { waveformJob?.cancel() } catch (_: Exception) {}
        waveformJob = null
        try { pulseAnimator?.cancel() } catch (_: Exception) {}
        pulseAnimator = null
    }


    private var editorAuthority: EditorSnapshot? = null
    private val ownedSelections = ArrayDeque<Pair<Int, Int>>()

    private fun editorStillOwned(): Boolean = editorAuthority != null && EditorSnapshot.read(currentInputConnection) == editorAuthority

    private fun noteOwnEditorChange(success: Boolean) {
        if (!success) { cancelForEditorChange(); return }
        editorAuthority = EditorSnapshot.read(currentInputConnection)
        editorAuthority?.let { ownedSelections.addLast(it.selectionStart to it.selectionEnd) }
        while (ownedSelections.size > 16) ownedSelections.removeFirst()
    }

    private fun cancelForEditorChange() {
        stopDictation(StopReason.CURSOR_MOVED)
        editorActionController.clearHistory()
        editorActionController.clearSprichInsertion()
        ownedSelections.clear()
        editorAuthority = null
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!::session.isInitialized || isPasswordField) return
        val selection = newSelStart to newSelEnd
        val ownIndex = ownedSelections.indexOfLast { it == selection }
        if (ownIndex >= 0 && editorStillOwned()) {
            repeat(ownIndex + 1) { ownedSelections.removeFirst() }
            return
        }
        val authority = editorAuthority
        if (authority != null && selection == (authority.selectionStart to authority.selectionEnd) && editorStillOwned()) return
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd || authority != null) cancelForEditorChange()
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        try {
            super.onStartInput(info, restarting)
            stopDictation(if (restarting) StopReason.INPUT_RESTARTED else StopReason.FIELD_LOST)
            val newFieldId = "field_${fieldGeneration.incrementAndGet()}"
            currentFieldId = newFieldId
            // Focus grants editor authority; it does not start capture or enter Preparing.
            // The capture session is created by startDictationIfNeeded after a tap/instant start.
            editorActionController.clearHistory()
            editorActionController.clearSprichInsertion()
            ownedSelections.clear()
            editorAuthority = if (isPassword(info)) null else EditorSnapshot.read(currentInputConnection)
            currentFieldTokenIcHash = currentInputConnection?.hashCode() ?: 0
            isPasswordField = isPassword(info)
            // Password early exit must clear deletion/undo history, cancel gesture state, discard preview, invalidate editor ownership BEFORE return
            if (isPasswordField) {
                try { editorActionController.onPasswordFieldFocused() } catch (_: Exception) {}
                try { stopDeleteRepeat() } catch (_: Exception) {}
                try { composition.discardPartial(currentInputConnection) } catch (_: Exception) {}
                try { activeUtterance = null } catch (_: Exception) {}
                Log.i("SprichIME", "password field, silent — cleared editor history/gesture")
                stopDictation(StopReason.PASSWORD_FIELD)
                return
            }
            latency.mark("onStartInput")
            composition.reset()
            try { utteranceAudio.clear() } catch (_: Exception) {}
            try { engine.clearUtteranceCapture() } catch (_: Exception) {}
            // Keep engine ring clear for partials when in Accurate mode, but collector is authoritative
            try { fastConformerEngine.clearUtteranceCapture() } catch (_: Exception) {}
            currentUtteranceToken = null
            activeUtterance = null
            utteranceActive.set(false)
            endpointPending.set(false)
            try { editorActionController.clearHistory() } catch (_: Exception) {}

        } catch (e: Exception) {
            Log.e("SprichIME", "onStartInput failed", e)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        updateImeUi(isDictationRunning())
        if (isPassword(info)) return
        startJob?.cancel()
        startJob = scope.launch {
            val snap = runtimeConfigFlow.first { it != null }!!
            if (snap.instantMode) { delay(40); if (isInputViewShown) startDictationIfNeeded() }
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        updateImeUi(isDictationRunning())
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        try { stopVisualLane() } catch (_: Exception) {}
        try { stopDictation(StopReason.WINDOW_HIDDEN) } catch (_: Exception) {}
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

    override fun onFinishInputView(finishingInput: Boolean) {
        stopDictation(StopReason.WINDOW_HIDDEN)
        try { stopVisualLane() } catch (_: Exception) {}
        super.onFinishInputView(finishingInput)
    }

    @Volatile private var cleanupScope: kotlinx.coroutines.CoroutineScope? = null

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
        val retiredCapture = try { audio.requestStop() } catch (_: Exception) { null }
        // Cancel network/work without blocking
        try { scope.coroutineContext.cancelChildren() } catch (_: Exception) {}

        // Off-main deterministic cleanup — never blocks main, no use-after-free, field-tied scope
        try { cleanupScope?.let { try { it.cancel() } catch (_: Exception) {} } } catch (_: Exception) {}
        cleanupScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        cleanupScope!!.launch {
            val ct0 = android.os.SystemClock.elapsedRealtime()
            try { kotlinx.coroutines.withTimeoutOrNull(800) { finalizationActorJob?.cancelAndJoin() } } catch (_: Exception) {}
            try { retiredCapture?.awaitStop() } catch (_: Exception) {}
            try { speechPresence.release() } catch (_: Exception) {}
            try { lidEngine.unload() } catch (_: Exception) {}
            try { fastConformerEngine.unload() } catch (_: Exception) {}
            try { engine.unload() } catch (_: Exception) {}
            try { utteranceAudio.clear() } catch (_: Exception) {}
            try { composition.discardPartial(null) } catch (_: Exception) {}
            try { fieldController.cancelActive() } catch (_: Exception) {}
            try { scope.cancel() } catch (_: Exception) {}
            android.util.Log.i("SprichIME", "off-main cleanup complete wall=${android.os.SystemClock.elapsedRealtime()-ct0}ms total=${android.os.SystemClock.elapsedRealtime()-t0}ms")
        }
        val mainWall = android.os.SystemClock.elapsedRealtime() - t0
        android.util.Log.i("SprichIME", "onDestroy main-thread wall=${mainWall}ms (cleanup off-main)")
        if (mainWall > 50) android.util.Log.w("SprichIME", "onDestroy main wall >50ms — investigate ANR risk")
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
            if (!isInputViewShown) return
            // Gated by runtimeConfig — before first snapshot: no preload, no mic, no plan
            val snapAtStart = runtimeConfig
            if (snapAtStart == null) {
                Log.i("SprichIME", "startDictation gated: runtimeConfig not ready — Getting ready…")
                statusText?.text = getString(com.sprich.app.R.string.ime_preparing)
                (statusText?.tag as? TextView)?.text = getString(com.sprich.app.R.string.ime_loading_settings)
                return
            }
            if (isPasswordField) {
                // Password field → zero capture/mutation, clear history already done in onStartInput, also ensure no mic
                statusText?.text = getString(com.sprich.app.R.string.ime_password)
                (statusText?.tag as? TextView)?.text = getString(com.sprich.app.R.string.ime_password_hint)
                return
            }
            editorAuthority = EditorSnapshot.read(currentInputConnection)
            if (editorAuthority == null) {
                statusText?.text = getString(com.sprich.app.R.string.ime_no_cursor)
                (statusText?.tag as? TextView)?.text = getString(com.sprich.app.R.string.ime_no_cursor_hint)
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
                    statusText?.text = getString(com.sprich.app.R.string.ime_setup)
                    (statusText?.tag as? TextView)?.text = getString(com.sprich.app.R.string.ime_setup_hint)
                    try { vibrateTick() } catch (_: Exception) {}
                    writeDiagnostics("blocked Auto (no LID) resolved=${activeConfig.resolvedLanguageTag()} speechLanguage=$speechLanguage")
                    return
                }
                if (!fastReady) {
                    Log.w("SprichIME", "Auto (winner FastConformer) requested but FastConformer 126M not downloaded — download required.")
                    try { session.error("auto not supported without FastConformer") } catch (_: Exception) {}
                    statusText?.text = getString(com.sprich.app.R.string.ime_setup)
                    (statusText?.tag as? TextView)?.text = getString(com.sprich.app.R.string.ime_setup_hint)
                    try { vibrateTick() } catch (_: Exception) {}
                    writeDiagnostics("blocked Auto (no FastConformer) speechLanguage=$speechLanguage")
                    return
                }
                Log.i("SprichIME", "Auto via Tiny LID per-utterance + FastConformer 126M (winner) — proceeding, LID will detect EN/DE/ES/FR, FastConformer will transcribe (RTF 0.038)")
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
                statusText?.text = getString(com.sprich.app.R.string.ime_mic_permission)
                (statusText?.tag as? TextView)?.text = getString(com.sprich.app.R.string.ime_mic_permission_hint)
                // Vibrate error
                try { vibrateTick() } catch (_: Exception) {}
                return
            }
            val generation = sessionGeneration.incrementAndGet()
            // The retired reader must finish before we reset shared endpoint/PCM state.
            val previousReaderStopped = withContext(Dispatchers.IO) { audio.requestStop()?.awaitStop(2000) != false }
            if (generation != sessionGeneration.get()) return
            if (!previousReaderStopped) {
                failSession(generation, "microphone shutdown timed out", getString(com.sprich.app.R.string.ime_mic_unavailable), getString(com.sprich.app.R.string.ime_retry), null)
                return
            }
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
            statusText?.text = getString(com.sprich.app.R.string.ime_loading)
            (statusText?.tag as? TextView)?.text = getString(com.sprich.app.R.string.ime_first_start)

            // Route-aware loading — only load required engines, do NOT load Canary for Automatic. For API_PRIMARY, local not required.
            val routeForSession = determineRoute(snapAtStart.speechLanguage)
            val requiresLocalLoad = snapAtStart.transcriptionMode != TranscriptionMode.API_PRIMARY
            val mmForLoad = try { com.sprich.app.models.manager.ModelManager(this) } catch (_: Exception) { null }
            val loadResult: Result<Unit> = if (!requiresLocalLoad && transcriptionMode == TranscriptionMode.API_PRIMARY) {
                // API primary: no local model required for successful remote path; fallback loaded lazily on failure
                val remoteCfg = buildRemoteSttConfig(snapAtStart)
                if (remoteCfg == null) {
                    Result.failure(Exception("Remote STT not configured — set base URL/model/API key in Settings"))
                } else {
                    // Verify credential exists — secure store only, legacy plaintext no longer supported (P0-4)
                    val cred = withContext(Dispatchers.IO) { apiSecretStore.loadBoundSecret(remoteCfg.credentialRef, remoteCfg.providerId, remoteCfg.endpoint) ?: "" }
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
                    getString(com.sprich.app.R.string.ime_model_failed),
                    getString(com.sprich.app.R.string.ime_model_failed_hint),
                    loadResult.exceptionOrNull(),
                )
                return
            }
            if (requiresLocalLoad) speechPresence.prepare()
            if (generation != sessionGeneration.get()) return
            // Ensure coordinator exists
            if (localCoordinator == null) localCoordinator = LocalTranscriptionCoordinator(lidEngine, fastConformerEngine, engine)
            // Housekeeping: unload unused resident engines if queue drained
            maybeUnloadUnused(routeForSession)

            latency.mark("audioStartRequested")
            composition.reset()
            vad.reset()
            lastVadState = Vad.State.SILENCE
            utteranceActive.set(false)
            endpointPending.set(false)
            lastPartialText = ""
            try { utteranceAudio.clear() } catch (_: Exception) {}
            try { engine.clearUtteranceCapture() } catch (_: Exception) {}
            try { fastConformerEngine.clearUtteranceCapture() } catch (_: Exception) {}
            currentUtteranceToken = null
            activeUtterance = null
            Log.i("SprichIME", "vad reset ${vad.calibrationInfo()} activeConfig=$activeConfig prefsLang=$language speechLang=$speechLanguage")
            // Respect user language preference; Canary handles EN/DE/ES/FR, AUTO falls back to EN inside engine.
            // Resolved once per session and observable in diagnostics; Locale.getDefault() is never used here.
            activeConfig = SpeechSessionConfig(
                language = snapAtStart.speechLanguage.toLegacyLanguage(),
                speechLanguage = snapAtStart.speechLanguage,
                task = TranscriptionTask.TRANSCRIBE,
                enablePunctuation = true,
                enableCommands = snapAtStart.commandsEnabled,
            )
            Log.i("SprichIME", "session language resolved=${activeConfig.resolvedLanguageTag()} task=${activeConfig.resolvedTask()}")

            // All production routes transcribe the immutable final PCM snapshot once.
            // Avoid speculative Canary decoding and its second live PCM buffer.
            engine.cancelSession()
            fastConformerEngine.cancelSession()

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
                                getString(com.sprich.app.R.string.ime_mic_stopped),
                                getString(com.sprich.app.R.string.ime_retry),
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
                    getString(com.sprich.app.R.string.ime_mic_unavailable),
                    getString(com.sprich.app.R.string.ime_mic_busy_hint),
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
            failSession(generation, "start failed", getString(com.sprich.app.R.string.ime_start_failed), getString(com.sprich.app.R.string.ime_retry), t)
        }
    }

    // Hot-path: single RMS, zero extra PCM allocation (reuses readBuf), offset/length ownership
    private fun handleAudioChunk(generation: Long, samples: ShortArray, offset: Int, length: Int, timestampNanos: Long, precomputedRms: Float) {
        if (generation != sessionGeneration.get() || !session.requireActive()) { staleCallbackDrops++; return }
        try {
            pipelineChunkCount++
            pipelineSampleCount += length
            val durationMs = ((length * 1000L) / SAMPLE_RATE).coerceAtLeast(1L)
            val remoteOnly = when (activeUtterance?.plan?.transcription) {
                is TranscriptionPlan.ApiPrimary -> true
                null -> runtimeConfig?.transcriptionMode == TranscriptionMode.API_PRIMARY
                else -> false
            }
            val speechDetected = if (!remoteOnly && speechPresence.ready) speechPresence.detect(samples, offset, length) else null
            val result = vad.process(samples, offset, length, durationMs, precomputedRms, speechDetected)
            // Coalesced lane: set latest RMS, visual lane reads at frame rate (~60fps), throttled 50ms gate kept for safety
            lastRms = result.rms
            visualRms = result.rms
            // Keep 50ms gate only as secondary throttle if lane is running, otherwise direct
            if (!visualRunning && utteranceActive.get()) {
                val nowElapsed = android.os.SystemClock.elapsedRealtime()
                if (nowElapsed - lastVisualUpdateElapsed >= 50) {
                    lastVisualUpdateElapsed = nowElapsed
                    try { updateLiquidVisual(result.rms) } catch (_: Exception) {}
                }
            }
            if (result.state != lastVadState) {
                if (android.util.Log.isLoggable("SprichIME", android.util.Log.DEBUG)) {
                    android.util.Log.d(
                        "SprichIME",
                        "vad ${lastVadState.name}->${result.state.name} rms=${String.format(java.util.Locale.US,"%.5f", result.rms)} threshold=${String.format(java.util.Locale.US,"%.5f", vad.currentThreshold())} noiseFloor=${String.format(java.util.Locale.US,"%.5f", vad.noiseFloorValue())} chunks=$pipelineChunkCount samples=$pipelineSampleCount pushed=$pipelinePushedSampleCount generation=$generation",
                    )
                }
                lastVadState = result.state
            } else if (pipelineChunkCount % 16L == 0L) {
                if (android.util.Log.isLoggable("SprichIME", android.util.Log.DEBUG)) {
                    android.util.Log.d("SprichIME", "vad alive state=${result.state.name} rms=${String.format(java.util.Locale.US,"%.5f", result.rms)} chunks=$pipelineChunkCount pushed=$pipelinePushedSampleCount")
                }
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
                // Gated: if runtimeConfig not yet ready, discard onset (Getting ready… state)
                if (runtimeConfig == null) {
                    Log.w("SprichIME", "utterance onset discarded: runtimeConfig not ready")
                    utteranceActive.set(false)
                    return
                }
                // AUTHORITATIVE: UtteranceAudioCollector owns seeding preRoll exactly once — engine-independent.
                try { utteranceAudio.begin(preRoll) } catch (_: Exception) {}
                // Freeze full utterance plan at onset from ONE atomic snapshot — Settings changes apply to NEXT utterance only.
                val snapAtOnset = runtimeConfig ?: run {
                    utteranceActive.set(false)
                    return
                }
                val planAtOnset = buildUtterancePlan(snapAtOnset) ?: run {
                    utteranceActive.set(false)
                    utteranceAudio.clear()
                    scope.launch { stopDictation(StopReason.ERROR) }
                    return
                }
                val routeAtOnset = when (val tp = planAtOnset.transcription) {
                    is TranscriptionPlan.Local -> tp.route
                    is TranscriptionPlan.ApiPrimary -> tp.localFallback ?: determineRoute(snapAtOnset.speechLanguage)
                    is TranscriptionPlan.LocalApiFallback -> tp.local
                }
                // For streaming API we would start session here via planAtOnset.transcription; for now non-streaming uses frozen PCM at endpoint.
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
                Log.i("SprichIME", "utterance onset id=${token.utteranceId} preRollSamples=${preRoll.size}")
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
                if (android.util.Log.isLoggable("SprichIME", android.util.Log.DEBUG)) android.util.Log.d("SprichIME", "speech onset preRollSamples=${preRoll.size} pushedTotal=$pipelinePushedSampleCount rms=${String.format(java.util.Locale.US,"%.5f", result.rms)} elapsedMs=${android.os.SystemClock.elapsedRealtime() - pipelineStartElapsed}")
            } else if (
                utteranceActive.get() &&
                (result.state == Vad.State.SPEECH || result.state == Vad.State.HESITATION)
            ) {
                pipelinePushedSampleCount += length
                // AUTHORITATIVE: collector is single owner — single copy via offset/length, zero extra allocation
                try { utteranceAudio.append(samples, offset, length) } catch (_: Exception) {}

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
                val token = currentUtteranceToken ?: run {
                    Log.w("SprichIME", "endpoint without token — discarding (fail closed, zero decode/network/mutation)")
                    try { utteranceAudio.clear() } catch (_: Exception) {}
                    activeUtterance = null
                    staleCallbackDrops++
                    return
                }
                // Use frozen plan/route/config from activeUtterance — never re-read mutable prefs (Phase 0A+2).
                val captured = activeUtterance
                if (captured == null || captured.token.utteranceId != token.utteranceId || captured.token.generation != token.generation || captured.token.fieldGeneration != token.fieldGeneration) {
                    Log.w("SprichIME", "endpoint stale token mismatch — discarding token=$token captured=${captured?.token}")
                    staleCallbackDrops++
                    return
                }
                val pendingPlan = captured.plan
                val pendingRoute = captured.localRoute
                val pendingConfig = captured.speechConfig
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
                    getString(com.sprich.app.R.string.ime_audio_error),
                    getString(com.sprich.app.R.string.ime_retry),
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
                            try { engineJob?.cancel() } catch (_: Exception) {}
                            engineJob = null
                            // Ensure audio stopped (already stopped at STOP request, but ensure)
                            // Do not clear utteranceAudio if B already cleared? It's already empty after freeze, safe
                            utteranceActive.set(false)
                            endpointPending.set(false)
                            lastPartialText = ""
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
        // Truthful, not silent drop. Human copy, no utterance jargon.
        scope.launch(Dispatchers.Main) {
            try {
                if (isCatchingUp) {
                    statusText?.text = getString(com.sprich.app.R.string.ime_catching_up)
                    (statusText?.tag as? TextView)?.text = getString(com.sprich.app.R.string.ime_pause_hint)
                    Log.w("SprichIME", "UI Catching up — suppressed=${catchingUpSuppressedOnsets.get()} rejected=${catchingUpRejectedOnsets.get()} depth=${queueDepth.get()}")
                } else {
                    if (session.state.value is SessionState.Listening || session.state.value is com.sprich.app.input.lifecycle.SessionState.Speech || session.state.value is com.sprich.app.input.lifecycle.SessionState.Finalizing) {
                        statusText?.text = getString(com.sprich.app.R.string.ime_listening)
                        (statusText?.tag as? TextView)?.text = getString(com.sprich.app.R.string.ime_idle_hint)
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
            if (android.util.Log.isLoggable("SprichIME", android.util.Log.DEBUG))
                android.util.Log.d("SprichIME", "utteranceMetrics id=${token.utteranceId} durationMs=$pcmDurationMs rms=${String.format(java.util.Locale.US,"%.5f", pcmRms)} samples=${pending.pcm.size} lang=${pending.config.resolvedLanguageTag()} pushed=${pending.pushedSamples}")
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
            Log.i("SprichIME", "finalized source=${transcriptionResult.source} elapsedMs=$elapsed chars=${transcriptionResult.text.length}")

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
                SpokenEditingParser.parse(raw, postProcessResolved, plan.speechConfig.enableCommands)
            } catch (_: Exception) {
                SpokenEditingParser.EditResult(raw, false)
            }
            val isDeleteCmd = SpokenEditingParser.isDeleteCommand(preRefineParsed.text)
            val prepared: PreparedFinalAction = if (isDeleteCmd) {
                Log.i("SprichIME", "spoken editing command detected")
                if (preRefineParsed.text == "__DELETE_SENTENCE__") PreparedFinalAction.DeleteSentence(token) else PreparedFinalAction.DeleteLast(token)
            } else {
                // Non-command: deterministic text with vocab applied before refinement (P1-17 step 2)
                var deterministic = preRefineParsed.text
                // Ensure vocab applied deterministically before refinement (parser already did, but double-ensure)
                deterministic = try { plan.vocabulary.apply(deterministic) } catch (_: Exception) { deterministic }
                // For irrelevant vocab protection, compute relevant terms only (present in current transcript)
                val relevantTerms: List<String> = try {
                    val allEntries = plan.vocabulary.entries.map { it.written }.filter { it.isNotBlank() }
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
            // Spoken delete: only delete exact owned insertion if proof holds, else zero mutation (no 40/120 guess)
            val applied: Boolean = when (prepared) {
                is PreparedFinalAction.DeleteLast -> {
                    val ok = executeSpokenDeleteLast(token)
                    if (ok) finalCommitCount++
                    ok
                }
                is PreparedFinalAction.DeleteSentence -> {
                    // For this release: sentence delete disabled unless exact span can be safely established — zero mutation
                    Log.i("SprichIME", "spoken deleteSentence requested but disabled for release — zero mutation token=$token")
                    false
                }
                is PreparedFinalAction.Text -> {
                    val finalText = (prepared as PreparedFinalAction.Text).text
                    if (finalText.isEmpty()) {
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
            if (prepared is PreparedFinalAction.Text && (prepared as PreparedFinalAction.Text).text.isNotEmpty() && !applied) {
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

            if (reason == StopReason.ENDPOINT && !utteranceActive.get()) session.onListeningAgain()
            finishedWithRetry = reason == StopReason.ENDPOINT
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
            collectorSnap
        } catch (_: Exception) { ShortArray(0) }
        // Use frozen utterance descriptor if it matches this token (covers USER_STOP path); otherwise fallback to current plan.
        val captured = activeUtterance?.takeIf { it.token == token } ?: return
        val pendingPlan = captured.plan
        val pendingRoute = captured.localRoute
        val pendingConfig = captured.speechConfig
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

    // Legacy wrapper for tests — now fail-closed if no token
    private suspend fun finalizeUtterance(generation: Long) {
        val token = currentUtteranceToken ?: run {
            Log.w("SprichIME", "finalizeUtterance missing token — zero mutation")
            staleCallbackDrops++
            return
        }
        finalizeOnce(token, StopReason.ENDPOINT)
    }

    // Spoken delete "delete that" — only deletes exact owned insertion if cursor proof holds; zero mutation otherwise (no 40/120 guess)
    private fun executeSpokenDeleteLast(token: UtteranceToken): Boolean {
        if (token.generation != sessionGeneration.get()) { staleCallbackDrops++; return false }
        if (token.sessionId != session.sessionId || !session.isSessionValid(token.sessionId)) { staleCallbackDrops++; return false }
        if (token.fieldId != currentFieldId || token.fieldGeneration != fieldGeneration.get()) { staleCallbackDrops++; return false }
        if (!fieldController.isCurrentSession(token.sessionId)) { staleCallbackDrops++; return false }
        val ic = currentInputConnection ?: return false
        val isPass = isPasswordField || isPassword(currentInputEditorInfo)
        if (isPass) {
            Log.i("SprichIME", "spoken deleteThat blocked password field token=$token")
            return false
        }
        if (!editorStillOwned()) { cancelForEditorChange(); return false }
        val ok = editorActionController.deleteLastSprichInsertion(ic, currentFieldId, fieldGeneration.get(), isPass)
        noteOwnEditorChange(ok)
        if (ok) {
            try { fieldController.commitUtterance(token.sessionId, token.utteranceId, ic, "") } catch (_: Exception) {}
            try { composition.discardPartial(ic) } catch (_: Exception) { composition.finishIfActive(ic) }
            vibrateTick()
        }
        return ok
    }

    private fun commitFinalText(token: UtteranceToken, text: String, resolved: ResolvedUtteranceLanguage): Boolean {
        val ic = currentInputConnection ?: return false
        if (token.generation != sessionGeneration.get()) { staleCallbackDrops++; return false }
        if (token.sessionId != session.sessionId || !session.isSessionValid(token.sessionId)) { staleCallbackDrops++; return false }
        if (token.fieldId != currentFieldId || token.fieldGeneration != fieldGeneration.get()) { staleCallbackDrops++; return false }
        if (!fieldController.isCurrentSession(token.sessionId)) { staleCallbackDrops++; return false }
        if (token.capturedIc == null || ic !== token.capturedIc || !editorStillOwned()) {
            staleCallbackDrops++
            cancelForEditorChange()
            return false
        }
        // Password check independently in commit path as well
        if (isPasswordField || isPassword(currentInputEditorInfo)) {
            Log.i("SprichIME", "commitFinalText blocked password field token=$token")
            return false
        }
        // No second SpokenEditingParser parse — text is already prepared (P0-7)
        // Final vocab apply already done, but ensure once more for safety (idempotent)
        val finalText = text
        val result = try { fieldController.commitUtteranceTyped(token.sessionId, token.utteranceId, ic, finalText) } catch (_: Exception) { FieldSessionController.CommitResult.StaleSession }
        if (result is FieldSessionController.CommitResult.Committed) {
            // Record exact insertion for spoken "delete that" proof (same field/generation, cursor immediately after)
            try { editorActionController.recordSprichInsertion(currentFieldId, fieldGeneration.get(), composition.lastCommittedText ?: finalText, ic) } catch (_: Exception) {}
        }
        noteOwnEditorChange(result is FieldSessionController.CommitResult.Committed)
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

    // Legacy applyFinalText(Language) removed — only ResolvedUtteranceLanguage variant remains (duplicate deleted)
    // Backwards compat for non-token call sites
    private fun applyFinalText(text: String): Boolean {
        val token = currentUtteranceToken ?: return false
        // Route through ResolvedUnknown to keep single code path
        return applyFinalText(token, text, ResolvedUtteranceLanguage.Unknown)
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
        // Off-main audio teardown — Main does only cheap requestStop, join off-thread
        val retiredCapture = try { audio.requestStop() } catch (_: Exception) { null }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { try { retiredCapture?.awaitStop() } catch (_: Exception) {} }
        try { editorActionController.clearHistory() } catch (_: Exception) {}
        try { editorActionController.clearSprichInsertion() } catch (_: Exception) {}
        try { engineJob?.cancel() } catch (_: Exception) {}
        try { endpointJob?.cancel() } catch (_: Exception) {}
        try { engine.cancelSession() } catch (_: Exception) {}
        try { composition.discardPartial(currentInputConnection) } catch (_: Exception) {}
        try { fieldController.cancelActive() } catch (_: Exception) {}
        utteranceActive.set(false)
        endpointPending.set(false)
        lastPartialText = ""
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
        statusText?.text = getString(com.sprich.app.R.string.ime_insert_failed)
        (statusText?.tag as? TextView)?.text = getString(com.sprich.app.R.string.ime_insert_retry)
        writeDiagnostics("utteranceScopedFailure token=$token reason=$reason drops=$staleCallbackDrops claims=$finalizationClaims")
    }

    private fun stopDictation(reason: StopReason = StopReason.USER_STOP) {
        val wasActive = isDictationRunning()
        val generationAtStop = sessionGeneration.get()
        Log.i("SprichIME", "stopDictation reason=$reason wasActive=$wasActive generation=$generationAtStop chunks=$pipelineChunkCount pushed=$pipelinePushedSampleCount vadState=${vad.currentState().name} field=$currentFieldId")
        try { startJob?.cancel() } catch (_: Exception) {}
        startJob = null
        // No Main join — cheap requestStop, await off-thread; prevent start racing old record
        val retiredCapture = try { audio.requestStop() } catch (_: Exception) { null }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { try { retiredCapture?.awaitStop() } catch (_: Exception) {} }

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
                val token = currentUtteranceToken ?: run {
                    Log.w("SprichIME", "USER_STOP missing token — zero mutation")
                    staleCallbackDrops++
                    return
                }
                // Freeze current PCM snapshot — immutable, isolated copy
                val snap = try { utteranceAudio.snapshot() } catch (_: Exception) { ShortArray(0) }
                val captured = activeUtterance?.takeIf { it.token == token } ?: return
                val pendingPlan = captured.plan
                val pendingRoute = captured.localRoute
                val pendingConfig = captured.speechConfig
                val pending = PendingUtterance(
                    token = token,
                    pcm = snap,
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
                statusText?.text = getString(com.sprich.app.R.string.ime_stopping)
                (statusText?.tag as? TextView)?.text = getString(com.sprich.app.R.string.ime_finishing)
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
        if (!com.sprich.app.BuildConfig.DEBUG) return
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

    @Suppress("NewApi")
    private fun switchToNextKeyboard() {
        // Legacy wrapper — delegates to compat that returns bool
        switchToNextKeyboardCompat()
    }

    private fun switchToNextKeyboardCompat(): Boolean {
        // Prefer previous IME (restores previous keyboard), fallback to next
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val previous = try { switchToPreviousInputMethod() } catch (_: Exception) { false }
                if (previous) {
                    Log.i("SprichIME", "switchToPreviousInputMethod success")
                    return true
                }
            } else {
                // API 26-27 token-based — use window token via InputMethodService window
                try {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    val w = (this as? android.inputmethodservice.InputMethodService)?.window?.window
                    val token = w?.attributes?.token
                    if (token != null) {
                        val ok = try { imm.switchToLastInputMethod(token) } catch (_: Exception) { false }
                        if (ok) {
                            Log.i("SprichIME", "switchToLastInputMethod (token) success")
                            return true
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val next = try { switchToNextInputMethod(false) } catch (_: Exception) { false }
                if (next) {
                    Log.i("SprichIME", "switchToNextInputMethod success")
                    return true
                }
            } else {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                val w2 = (this as? android.inputmethodservice.InputMethodService)?.window?.window
                val token = w2?.attributes?.token
                if (token != null) {
                    val ok = try { imm.switchToNextInputMethod(token, false) } catch (_: Exception) { false }
                    if (ok) {
                        Log.i("SprichIME", "switchToNextInputMethod (token) success")
                        return true
                    }
                }
            }
        } catch (_: Exception) {}
        return false
    }

    @Suppress("NewApi")
    private fun switchToPreviousKeyboard(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val ok = switchToPreviousInputMethod()
                if (ok) Log.i("SprichIME", "switchToPreviousKeyboard success")
                ok
            } else {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                val w = (this as? android.inputmethodservice.InputMethodService)?.window?.window
                val token = w?.attributes?.token ?: return false
                try { imm.switchToLastInputMethod(token) } catch (_: Exception) { false }
            }
        } catch (_: Exception) { false }
    }

    private fun vibrateTick() {
        // Vibration couples into the microphone, especially on a hard surface.
        // Never create acoustic feedback while an utterance can still be captured.
        if (!hapticsEnabled || isDictationRunning()) return
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

    // ---- Swipe-to-delete editing — delegated to EditorActionController (single owner) ----

    private fun deleteLastWord(): Boolean {
        if (isDictationRunning()) stopDictation(StopReason.CURSOR_MOVED)
        editorAuthority = EditorSnapshot.read(currentInputConnection)
        // Password / selection / safe-read checks inside controller; zero mutation if cannot read
        val ic = currentInputConnection
        // Must independently check current field policy (password) even if isPasswordField stale
        val isPass = isPasswordField || isPassword(currentInputEditorInfo)
        if (isPass) {
            Log.i("SprichIME", "deleteLastWord blocked password field")
            try { editorActionController.onPasswordFieldFocused() } catch (_: Exception) {}
            return false
        }
        val ok = try {
            ic?.beginBatchEdit()
            try { composition.discardPartial(ic) } catch (_: Exception) {}
            val res = editorActionController.deletePreviousWord(ic, currentFieldId, fieldGeneration.get(), isPass)
            ic?.endBatchEdit()
            res
        } catch (_: Exception) { try { ic?.endBatchEdit() } catch (_: Exception) {}; false }
        if (ok) noteOwnEditorChange(true)
        // Haptic only after meaningful action actually succeeds, ≤ one per user action
        if (ok) vibrateTick()
        return ok
    }

    private fun undoLastDelete(): Boolean {
        if (isDictationRunning()) stopDictation(StopReason.CURSOR_MOVED)
        editorAuthority = EditorSnapshot.read(currentInputConnection)
        val ic = currentInputConnection
        val isPass = isPasswordField || isPassword(currentInputEditorInfo)
        if (isPass) {
            Log.i("SprichIME", "undo blocked password")
            return false
        }
        val ok = editorActionController.undoDeletion(ic, currentFieldId, fieldGeneration.get(), isPass)
        if (ok) noteOwnEditorChange(true)
        if (ok) vibrateTick()
        return ok
    }

    // Deleted: insertNewline (swipe-down newline removed), hold-to-repeat deletion removed per closure spec
    private fun stopDeleteRepeat() {
        // No-op: hold-to-repeat deleted. Keep for call sites that still invoke on ACTION_CANCEL/UP
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

    // Dual palette: LOCAL pleasure red vs API trustworthy blue — maximal Δ for deuteranopia, cloud semantic
    private data class BarPalette(val calm:Int, val c0:Int, val c1:Int, val c2:Int, val hot:Int, val aura:Int)
    private val LOCAL_PALETTE = BarPalette(
        calm = Color.parseColor("#B9B3C9"),
        c0 = Color.parseColor("#FF7A67"), c1 = Color.parseColor("#FF4D76"), c2 = Color.parseColor("#E92B8E"),
        hot = Color.parseColor("#FF4D76"), aura = 0x59FF4D76.toInt()
    )
    private val API_PALETTE = BarPalette(
        calm = Color.parseColor("#9AA4D9"),
        c0 = Color.parseColor("#5B8DEF"), c1 = Color.parseColor("#3B6BFF"), c2 = Color.parseColor("#1E40AF"),
        hot = Color.parseColor("#3B6BFF"), aura = 0x593B6BFF.toInt()
    )
    private fun isApiPalette(): Boolean {
        // Truthful: uses frozen ActiveUtterance.plan when utterance active (captured at VAD onset), else current transcriptionMode
        val active = activeUtterance?.plan?.transcription
        if (active != null) {
            return active is TranscriptionPlan.ApiPrimary
            // LOCAL_API_FALLBACK stays local red during speech (API only on fallback after endpoint → no mid-utterance hue jump)
        }
        // Idle fallback: reflect the mode that *will* be used for next utterance (valid remote required)
        if (transcriptionMode == TranscriptionMode.API_PRIMARY) {
            // Check remote validity without allocating new object — use cached snapshot state
            // isValidHttpsUrl on sttBaseUrlState / locked provider bypass
            val isLocked = sttProviderId == "meta-muse-voice-transcribe" || sttProviderId == "meta-muse" || sttProviderId.startsWith("gemini")
            return isLocked || isValidHttpsUrl(sttBaseUrlState)
        }
        return false
    }
    private fun palette(): BarPalette = if (isApiPalette()) API_PALETTE else LOCAL_PALETTE
    private fun auraColorsFor(isApi: Boolean): IntArray {
        val p = if (isApi) API_PALETTE else LOCAL_PALETTE
        return intArrayOf(p.aura, 0x00000000)
    }

    /** Pleasure ramp (local) / Trust ramp (API blue): silence calm -> c0 -> c1 -> c2. */
    private fun gradientForRms(rms: Float, isApi: Boolean = isApiPalette()): IntArray {
        // Map 0.0008..0.05 RMS -> t 0..1 (log-ish feel via sqrt for perceptual response)
        val raw = ((rms - 0.0008f) / (0.05f - 0.0008f)).coerceIn(0f, 1f)
        val t = kotlin.math.sqrt(raw)
        val p = if (isApi) API_PALETTE else LOCAL_PALETTE
        val c0: Int; val c1: Int; val c2: Int
        if (t < 0.5f) {
            val u = (t / 0.5f)
            c0 = lerpColor(p.calm, p.c0, u); c1 = lerpColor(p.c0, p.c1, u); c2 = lerpColor(p.c1, p.c2, u * 0.6f)
        } else {
            val u = ((t - 0.5f) / 0.5f)
            c0 = lerpColor(p.c0, p.c1, u); c1 = lerpColor(p.c1, p.c2, u); c2 = p.c2
        }
        return intArrayOf(c0, c1, c2)
    }

    private fun strokeForRms(t: Float, isApi: Boolean = isApiPalette()): Int {
        val dark = isDark()
        val base = if (dark) Color.parseColor("#2A2A2A") else Color.parseColor("#E8E8E8")
        val hot = if (isApi) API_PALETTE.hot else LOCAL_PALETTE.hot
        return lerpColor(base, hot, t.coerceIn(0f, 1f))
    }

    /** Applies rms to bar, glow, aura and pill stroke on Main thread. Properties only — no requestLayout. */
    private fun updateLiquidVisual(rms: Float) {
        try {
            val isApi = isApiPalette()
            val colors = gradientForRms(rms, isApi)
            liquidBg?.colors = colors
            glowBg?.colors = colors
            val raw = ((rms - 0.0008f) / (0.05f - 0.0008f)).coerceIn(0f, 1f)
            val t = kotlin.math.sqrt(raw)
            // Ensure aura tracks palette (rare mode switch mid-utterance → recolor aura)
            auraView?.background?.let { bg ->
                try {
                    val auraColors = auraColorsFor(isApi)
                    (bg as? GradientDrawable)?.colors = auraColors
                } catch (_: Exception) {}
            }
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
            pillBgRef?.setStroke(dp(1), strokeForRms(t, isApi))
        } catch (_: Exception) {}
    }

    /** One bright flash + soft pop the moment dictated text lands — palette-aware. */
    private fun celebrateCommit() {
        scope.launch(Dispatchers.Main) {
            try {
                val isApi = isApiPalette()
                val p = if (isApi) API_PALETTE else LOCAL_PALETTE
                val hot = intArrayOf(p.c0, p.c1, p.c2)
                liquidBg?.colors = hot
                glowBg?.colors = hot
                // Aura flash also in palette
                auraView?.background?.let { bg -> try { (bg as? GradientDrawable)?.colors = intArrayOf(p.aura, 0x00000000) } catch (_: Exception) {} }
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

    private fun buildRemoteSttConfig(snap: com.sprich.app.storage.RuntimeConfigSnapshot? = runtimeConfig): RemoteSttConfig? {
        val s = snap ?: runtimeConfig ?: return null
        // Use snapshot exclusively — no mutable field reads
        val isMuse = s.sttProviderId == "meta-muse-voice-transcribe" || s.sttProviderId == "meta-muse"
        val isGemini = s.sttProviderId == "gemini" || s.sttProviderId == "gemini-3.5-transcribe" || s.sttProviderId == "gemini-3.5-transcribe-live"
        val isLocked = isMuse || isGemini
        val endpoint: String
        val model: String
        val supportsStreaming: Boolean
        val supportsKeywordBiasing: Boolean
        if (isMuse) {
            endpoint = com.sprich.app.storage.MuseDefaults.BASE_URL // base URL only — provider appends /v1/asr/transcribe
            model = com.sprich.app.storage.MuseDefaults.MODEL
            supportsStreaming = false // FIX 7.4: batch only until live streaming proven — no fake realtime
            supportsKeywordBiasing = true
        } else if (isGemini) {
            endpoint = com.sprich.app.storage.GeminiDefaults.BASE_URL
            model = com.sprich.app.storage.GeminiDefaults.MODEL
            supportsStreaming = false // batch only until live proven
            supportsKeywordBiasing = false
        } else {
            // Custom / openai-compatible — require valid HTTPS and model
            if (!isValidHttpsUrl(s.sttBaseUrl)) return null
            if (s.sttModel.isBlank()) return null
            endpoint = s.sttBaseUrl
            model = s.sttModel
            supportsStreaming = false
            supportsKeywordBiasing = false
        }
        // Single key for locked providers: use stt_default (same key for STT+refinement if provider supports both)
        val credRef = s.sttCredentialRef.ifBlank { "stt_default" }
        val speechLang = s.speechLanguage
        val langPolicy = LanguagePolicy.fromSpeechLanguage(speechLang)
        return RemoteSttConfig(
            providerId = s.sttProviderId,
            endpoint = endpoint,
            model = model,
            languagePolicy = langPolicy,
            deadlineMs = s.sttDeadlineMs,
            credentialRef = credRef,
            supportsStreaming = supportsStreaming,
            supportsKeywordBiasing = supportsKeywordBiasing,
            preferStreaming = supportsStreaming && s.sttStreamingEnabled,
        )
    }

    private fun buildRefinementConfig(mode: RefinementMode, snap: com.sprich.app.storage.RuntimeConfigSnapshot? = runtimeConfig): RefinementConfig? {
        if (mode == RefinementMode.OFF) return null
        val s = snap ?: runtimeConfig ?: return null
        val isMuseRefine = s.refinementProviderId == "meta-muse-voice-transcribe" || s.refinementProviderId == "meta-muse" || s.refinementProviderId == "muse-spark"
        val isGeminiRefine = s.refinementProviderId == "gemini" || s.refinementProviderId.startsWith("gemini-")
        val isSttMuse = s.sttProviderId == "meta-muse-voice-transcribe" || s.sttProviderId == "meta-muse"
        // For locked Muse/Gemini refinement, use fixed endpoint/model and single key (same as STT if STT is same provider)
        if (isMuseRefine || (isSttMuse && s.refinementProviderId == "openai-compatible" && s.refinementMode != RefinementMode.OFF)) {
            // If refinement is Muse Spark and STT is Muse Voice, use single key
            val credRef = when {
                s.refinementCredentialRef.isNotBlank() && s.refinementCredentialRef != "refine_default" -> s.refinementCredentialRef
                s.sttCredentialRef.isNotBlank() -> s.sttCredentialRef
                else -> "stt_default"
            }
            return RefinementConfig(
                providerId = "meta-muse-voice-transcribe",
                endpoint = com.sprich.app.storage.MuseRefinementDefaults.ENDPOINT,
                model = com.sprich.app.storage.MuseRefinementDefaults.MODEL,
                mode = mode,
                deadlineMs = s.refinementDeadlineMs,
                credentialRef = credRef,
            )
        }
        if (isGeminiRefine) {
            val credRef = when {
                s.refinementCredentialRef.isNotBlank() && s.refinementCredentialRef != "refine_default" -> s.refinementCredentialRef
                s.sttCredentialRef.isNotBlank() -> s.sttCredentialRef
                else -> "stt_default"
            }
            return RefinementConfig(
                providerId = "gemini",
                endpoint = com.sprich.app.storage.GeminiRefinementDefaults.ENDPOINT,
                model = com.sprich.app.storage.GeminiRefinementDefaults.MODEL,
                mode = mode,
                deadlineMs = s.refinementDeadlineMs,
                credentialRef = credRef,
            )
        }
        if (!isValidHttpsUrl(s.refinementBaseUrl) || s.refinementModel.isBlank()) return null
        val credRef = s.refinementCredentialRef.ifBlank { "refine_default" }
        return RefinementConfig(
            providerId = s.refinementProviderId,
            endpoint = s.refinementBaseUrl,
            model = s.refinementModel,
            mode = mode,
            deadlineMs = s.refinementDeadlineMs,
            credentialRef = credRef,
        )
    }

    private fun buildUtterancePlan(s: com.sprich.app.storage.RuntimeConfigSnapshot): UtterancePlan? {
        val localRoute = determineRoute(s.speechLanguage)
        val transcription: TranscriptionPlan = when (s.transcriptionMode) {
            TranscriptionMode.ON_DEVICE -> TranscriptionPlan.Local(localRoute)
            TranscriptionMode.API_PRIMARY -> buildRemoteSttConfig(s)?.let { TranscriptionPlan.ApiPrimary(it, localRoute) } ?: return null
            TranscriptionMode.LOCAL_API_FALLBACK -> buildRemoteSttConfig(s)?.let { TranscriptionPlan.LocalApiFallback(localRoute, it) } ?: return null
        }
        val config = SpeechSessionConfig(language = s.speechLanguage.toLegacyLanguage(), speechLanguage = s.speechLanguage,
            task = TranscriptionTask.TRANSCRIBE, enablePunctuation = true, enableCommands = s.commandsEnabled)
        return UtterancePlan(transcription, buildRefinementPlan(s), config, vocabStore.snapshot())
    }

    private fun buildRefinementPlan(snap: com.sprich.app.storage.RuntimeConfigSnapshot? = runtimeConfig): RefinementPlan {
        val s = snap ?: runtimeConfig ?: return RefinementPlan.Off
        return when (s.refinementMode) {
            RefinementMode.OFF -> RefinementPlan.Off
            else -> {
                val cfg = buildRefinementConfig(s.refinementMode, s) ?: return RefinementPlan.Off
                RefinementPlan.Enabled(cfg, s.refinementMode)
            }
        }
    }

    private fun ensureTranscriptionCoordinator(): TranscriptionCoordinator {
        transcriptionCoordinator?.let { return it }
        if (localCoordinator == null) localCoordinator = LocalTranscriptionCoordinator(lidEngine, fastConformerEngine, engine)
        val coord = TranscriptionCoordinator(localCoordinator!!, emptyMap(), apiSecretStore, DeadlinePolicy.DEFAULT, sharedHttpClient)
        transcriptionCoordinator = coord
        return coord
    }

    // P0-13: Refinement must use frozen pending.plan.refinement.config only — never global mutable state nor permanently cached old provider
    private suspend fun providerForRefinementConfig(cfg: com.sprich.app.speech.refinement.RefinementConfig): TranscriptRefinementProvider? {
        if (!com.sprich.app.speech.remote.ProviderAvailability.isEnabled(cfg.providerId)) return null
        val secret = try { apiSecretStore.loadBoundSecret(cfg.credentialRef, cfg.providerId, cfg.endpoint) ?: "" } catch (_: Exception) { "" }
        if (secret.isBlank()) return null
        if (!isValidHttpsUrl(cfg.endpoint) || cfg.model.isBlank()) return null
        // Create fresh provider sharing connection pool — cheap to recreate, avoids stale cached provider tied to old Settings
        val client = sharedHttpClient.newBuilder().build()
        return OpenAiCompatibleRefinementProvider(cfg.endpoint, cfg.model, secret, client)
    }

    private fun isAutomaticReadyForTest(): Boolean {
        return try { com.sprich.app.models.manager.ModelManager(this).isAutomaticReady() } catch (_: Exception) { false }
    }

    private suspend fun maybeUnloadUnused(activeRoute: LocalAsrRoute) {
        // Avoid keeping both heavy stacks resident — unload unused when queue drained.
        if (queueDepth.get() != 0) return // pending work — do not unload while finalizing
        when (activeRoute) {
            is LocalAsrRoute.AutomaticFastConformer -> {
                // Automatic keeps LID+Fast, unload Canary if idle
                if (engine.isLoaded()) {
                    engine.unload()
                    Log.i("SprichIME", "maybeUnload: Automatic active — unloading Canary to save memory")
                }
            }
            is LocalAsrRoute.AccurateCanary -> {
                // Accurate keeps Canary, may unload LID/Fast after safe transition
                if (lidEngine.isLoaded() || fastConformerEngine.isLoaded()) {
                    lidEngine.unload()
                    fastConformerEngine.unload()
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

    // Retained for instrumentation callers; mutation still passes the production authority gate.
    private fun applyFinalText(token: UtteranceToken, text: String, resolved: ResolvedUtteranceLanguage): Boolean = commitFinalText(token, text, resolved)
    companion object {
        private const val SAMPLE_RATE = 16_000L
        private const val PRE_ROLL_MS = 400
    }

}
