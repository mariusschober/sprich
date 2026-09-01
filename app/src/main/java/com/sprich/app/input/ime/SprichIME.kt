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
import com.sprich.app.ai.GrammarFixer
import com.sprich.app.core.perf.ThermalMonitor
import com.sprich.app.speech.canary.CanaryEngine
import com.sprich.app.speech.remote.RemoteSttEngine
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
    private val finalizedUtterances = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    @Volatile private var currentFieldTokenIcHash: Int = 0

    // Per-utterance PCM capture — primitive bounded buffer, single ownership via engine.
    // IME no longer maintains a second authoritative MutableList<Short>; it queries engine's frozen snapshot.
    // Keeping a lightweight local reference only for freeze-check before engine freeze is visible.
    // Using UtterancePcmBuffer would duplicate engine buffer — so IME now delegates.
    @Volatile private var frozenUtterancePcm: ShortArray? = null

    // Overlapping utterance queue — immutable per-utterance snapshots, serialized finalization actor
    // Invariant: one spoken utterance → one immutable PCM snapshot → one final decode → one deterministic post-processing pass → one editor commit
    // Active capture and pending finalizations are distinct: utterance N+1 can be captured while N decodes without mutation.
    data class PendingUtterance(
        val token: UtteranceToken,
        val pcm: ShortArray,
        val config: SpeechSessionConfig,
        val pushedSamples: Long,
        val reason: StopReason,
        val endpointTimestampNanos: Long,
    )
    // Single authoritative long-lived finalization actor — exactly one consumer, FIFO, no worker start/stop race
    private val pendingChannel = Channel<PendingUtterance>(capacity = Channel.UNLIMITED)
    private val queueDepth = AtomicInteger(0)
    private val pendingQueuePeak = AtomicLong(0)
    private val finalizationQueueOverflows = AtomicLong(0)
    private val maxPendingQueueDepth = 4
    @Volatile var catchingUp = false
        private set
    private var finalizationActorJob: Job? = null
    @Volatile var lastQueueDepth: Int = 0
        private set

    // Exactly-once diagnostic counters (debug/test builds, transcript-free)
    @Volatile var finalizationClaims: Long = 0
        private set
    @Volatile var finalCommitCount: Long = 0
        private set
    @Volatile var staleCallbackDrops: Long = 0
        private set
    @Volatile var nativeDecodeStarts: Long = 0
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
    // Remote backup STT + AI polish (OpenAI-compatible endpoints, configured in Settings)
    private val remoteStt by lazy {
        RemoteSttEngine(
            baseUrlProvider = { runCatching { kotlinx.coroutines.runBlocking { prefs.sttBaseUrl.first() } }.getOrDefault("") },
            apiKeyProvider = { runCatching { kotlinx.coroutines.runBlocking { prefs.sttApiKey.first() } }.getOrDefault("") },
            modelProvider = { runCatching { kotlinx.coroutines.runBlocking { prefs.sttModel.first() } }.getOrDefault("whisper-large-v3") },
        )
    }
    private val grammarFixer by lazy {
        GrammarFixer(
            baseUrlProvider = { runCatching { kotlinx.coroutines.runBlocking { prefs.aiBaseUrl.first() } }.getOrDefault("") },
            apiKeyProvider = { runCatching { kotlinx.coroutines.runBlocking { prefs.aiApiKey.first() } }.getOrDefault("") },
            modelProvider = { runCatching { kotlinx.coroutines.runBlocking { prefs.aiModel.first() } }.getOrDefault("") },
        )
    }
    // Candidate A: Whisper Tiny per-utterance LID + Canary (lowest-risk Auto)
    private val lidEngine by lazy {
        com.sprich.app.speech.lid.WhisperLidEngine(this, (application as SprichApp).let { com.sprich.app.models.manager.ModelManager(it) })
    }
    // Fallback for LID failure: validated multilingual FastConformer (126M) — does not require language flag
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
                        if (requested != EngineType.ACCURATE) {
                            Log.w("SprichIME", "forcing engine to ACCURATE (Canary) requested=$requested")
                            prefs.setEngine(EngineType.ACCURATE)
                        }
                    }
                } catch (e: Exception) { Log.w("SprichIME", "engineType collect fail", e) }
            }
            scope.launch {
                val result = engine.load()
                if (result.isFailure) Log.e("SprichIME", "Canary preload failed", result.exceptionOrNull())
                else Log.i("SprichIME", "Canary preload success")
            }
            // Preload Tiny LID if Auto may be used (warm, no block)
            scope.launch {
                try {
                    val lidRes = lidEngine.load()
                    if (lidRes.isSuccess) Log.i("SprichIME", "Tiny LID preload success")
                    else Log.i("SprichIME", "Tiny LID preload not ready: ${lidRes.exceptionOrNull()?.message}")
                } catch (e: Exception) { Log.w("SprichIME", "LID preload failed", e) }
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
                val result = engine.load()
                if (result.isFailure) Log.e("SprichIME", "preload on input failed", result.exceptionOrNull())
            }
            if (isPasswordField) {
                Log.i("SprichIME", "password field, silent")
                stopDictation(StopReason.PASSWORD_FIELD)
                return
            }
            composition.reset()
            frozenUtterancePcm = null
            try { engine.clearUtteranceCapture() } catch (_: Exception) {}
            currentUtteranceToken = null
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
            val result = engine.load()
            if (result.isFailure) Log.e("SprichIME", "preload on window failed", result.exceptionOrNull())
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
        try { stopDictation(StopReason.SERVICE_DESTROYED) } catch (_: Exception) {}
        try { pendingChannel.close() } catch (_: Exception) {}
        try { finalizationActorJob?.cancel() } catch (_: Exception) {}
        try { scope.launch { lidEngine.unload() } } catch (_: Exception) {}
        try { audio.release() } catch (_: Exception) {}
        scope.cancel()
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
            // Language handling: Canary explicit baseline vs Auto via Tiny LID
            // If Auto requested, check if Tiny LID model is available (Candidate A). If yes, allow Auto with per-utterance detection.
            // Otherwise require explicit selection.
            if (speechLanguage is SpeechLanguage.Auto) {
                // Single source of truth: ModelManager.isWhisperTinyReady() checks both encoder+decoder+tokens + size
                val lidReady = try {
                    com.sprich.app.models.manager.ModelManager(this).isWhisperTinyReady()
                } catch (_: Exception) { false }
                if (!lidReady) {
                    Log.w("SprichIME", "Auto language requested but Tiny LID not downloaded — explicit selection required.")
                    try { session.error("language auto not supported without LID") } catch (_: Exception) {}
                    statusText?.text = "Tap to choose language"
                    (statusText?.tag as? TextView)?.text = "Open Sprich app → Settings → Language (EN/DE/ES/FR) or download Tiny LID"
                    try { vibrateTick() } catch (_: Exception) {}
                    writeDiagnostics("blocked Auto (no LID) resolved=${activeConfig.resolvedLanguageTag()} speechLanguage=$speechLanguage")
                    return
                } else {
                    Log.i("SprichIME", "Auto language via Tiny LID per-utterance — proceeding, LID will detect EN/DE/ES/FR before Canary")
                    // Pre-warm LID if not loaded
                    try { lidEngine.load() } catch (_: Exception) {}
                }
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

            val loadResult = engine.load()
            if (generation != sessionGeneration.get()) return
            if (loadResult.isFailure) {
                failSession(
                    generation,
                    "engine load failed",
                    "Speech model unavailable",
                    "Restart Sprich or reinstall the APK",
                    loadResult.exceptionOrNull(),
                )
                return
            }

            latency.mark("audioStartRequested")
            vibrateTick()
            composition.reset()
            vad.reset()
            lastVadState = Vad.State.SILENCE
            utteranceActive.set(false)
            endpointPending.set(false)
            lastPartialText = ""
            frozenUtterancePcm = null
            try { engine.clearUtteranceCapture() } catch (_: Exception) {}
            currentUtteranceToken = null
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

            engine.cancelSession()
            try {
                engine.beginSession(activeConfig)
            } catch (t: Throwable) {
                failSession(
                    generation,
                    "begin session failed",
                    "Speech engine failed",
                    "Tap to retry",
                    t,
                )
                return
            }

            engineJob?.cancelAndJoin()
            engineJob = scope.launch {
                try {
                    engine.partialTranscript().collect { update ->
                        if (generation != sessionGeneration.get()) { staleCallbackDrops++; return@collect }
                        // Validate field/session ownership via controller — stale field callbacks dropped
                        val activeField = currentFieldId
                        if (activeField == null) { staleCallbackDrops++; return@collect }
                        if (update.isFinal) return@collect // finalizeOnce owns final commit; prevents duplication loop
                        // Phase 3: Auto live partial semantics — suppress wrong-language Canary partials
                        // Canary has no native Auto (decodes Auto as en), so German Auto speech would show English partial.
                        // Wrong-language partials are worse than no partials. For Auto, suppress field partials while language unresolved.
                        // Show listening state in Sprich UI, run LID at endpoint, commit one correct-language final.
                        if (activeConfig.speechLanguage is SpeechLanguage.Auto) {
                            // Do not insert wrong-language hypothesis into target field
                            // Keep latency tracking but don't apply to InputConnection
                            val hasTextForLatency = update.stable.isNotBlank() || update.unstable.isNotBlank()
                            if (hasTextForLatency && latency.delta("speechOnset", "firstHypothesis") == null) {
                                latency.mark("firstHypothesis")
                            }
                            // Optionally keep IME UI as listening, not hypothesis
                            Log.i("SprichIME", "partial suppressed for Auto unresolved (no wrong-lang) stableLen=${update.stable.length} unstableLen=${update.unstable.length}")
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
                audio.start(
                    onChunk = { samples, timestampNanos ->
                        handleAudioChunk(generation, samples, timestampNanos)
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

    private fun handleAudioChunk(generation: Long, samples: ShortArray, timestampNanos: Long) {
        if (generation != sessionGeneration.get() || !session.requireActive()) { staleCallbackDrops++; return }
        try {
            pipelineChunkCount++
            pipelineSampleCount += samples.size
            val durationMs = ((samples.size * 1000L) / SAMPLE_RATE).coerceAtLeast(1L)
            val result = vad.process(samples, durationMs)
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

            if (catchingUp && result.state == Vad.State.SPEECH) {
                Log.w("SprichIME", "CatchingUp suppressing new utterance onset depth=${queueDepth.get()} max=$maxPendingQueueDepth")
                // Do not start new utterance until queue recovers; keep utteranceActive false
                // Also degrade speculative partial work: we could cancel partial job, but at least we don't start new capture
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
                // Per-utterance PCM ownership: engine owns seeding preRoll exactly once — no duplicate IME buffer.
                frozenUtterancePcm = null
                // Do NOT maintain a parallel IME PCM buffer; engine is single authoritative owner.
                // Ownership contract: beginUtteranceCapture owns seeding preRoll exactly once.
                // pushAudio must NOT be called again for the same preRoll — prevents [preRoll][preRoll] duplication.
                try { engine.beginUtteranceCapture(preRoll) } catch (_: Exception) {}
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
                Log.i("SprichIME", "utterance onset token=$token preRollSamples=${preRoll.size} pushedTotal=$pipelinePushedSampleCount")
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
                pipelinePushedSampleCount += samples.size
                // Engine is single authoritative PCM owner — no duplicate IME buffer.
                engine.pushAudio(samples, timestampNanos)
            }

            if (
                result.state == Vad.State.UTTERANCE_END &&
                utteranceActive.compareAndSet(true, false)
            ) {
                latency.mark("endpointDetected")
                // Mark pending for observability; do not block next onset — queue handles continuous speech.
                endpointPending.set(true)
                // Freeze per-utterance PCM at endpoint so fallback cannot include next utterance.
                // CRITICAL: immutable snapshot — captured synchronously before next onset can reuse live buffer.
                // This prevents Race 1 (A reading B's snapshot) by copying to isolated PendingUtterance.pcm.
                val frozenSnap: ShortArray = try { engine.snapshotUtterancePcm() } catch (_: Exception) { ShortArray(0) }
                // Also cache in legacy global for non-queued fallback paths (e.g., remoteStt fallback)
                frozenUtterancePcm = frozenSnap
                val token = currentUtteranceToken ?: run {
                    Log.w("SprichIME", "endpoint without token — creating synthetic token")
                    UtteranceToken(session.sessionId, generation, utteranceIdCounter.get(), currentFieldId, fieldGeneration.get(), try { currentInputConnection } catch (_: Exception) { null })
                }
                // Create immutable PendingUtterance containing ALL state required to finalize that utterance.
                // Once created, nothing in the active capture path may mutate it.
                val pending = PendingUtterance(
                    token = token,
                    pcm = frozenSnap.copyOf(), // immutable isolated copy — B cannot clear/replace it
                    config = activeConfig.copy(),
                    pushedSamples = pipelinePushedSampleCount,
                    reason = StopReason.ENDPOINT,
                    endpointTimestampNanos = System.nanoTime(),
                )
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
                    Log.e("SprichIME", "finalizePending failed $pending", t)
                    failSession(pending.token.generation, "finalization failed token=${pending.token} reason=${pending.reason}", "Transcription failed", "Tap to retry", t)
                } finally {
                    val newDepth = queueDepth.decrementAndGet()
                    lastQueueDepth = newDepth
                    // Exit CatchingUp when recovered
                    if (catchingUp && newDepth < maxPendingQueueDepth - 1) {
                        catchingUp = false
                        Log.i("SprichIME", "CatchingUp recovered depth=$newDepth")
                    }
                    if (newDepth == 0) {
                        endpointPending.set(false)
                    }
                    Log.i("SprichIME", "actor processed pending=${pending.token.utteranceId} depthBefore=$depthBefore newDepth=$newDepth catchingUp=$catchingUp")
                }
            }
            Log.i("SprichIME", "finalization actor completed (channel closed)")
        }
    }

    private fun enqueuePending(pending: PendingUtterance) {
        // Enforce genuine bound: at capacity, preserve every frozen utterance, degrade partials, enter CatchingUp
        val depthBefore = queueDepth.get()
        if (depthBefore >= maxPendingQueueDepth) {
            finalizationQueueOverflows.incrementAndGet()
            if (!catchingUp) {
                catchingUp = true
                Log.w("SprichIME", "finalization queue at capacity depth=$depthBefore pending=${pending.token.utteranceId} peak=${pendingQueuePeak.get()} overflows=${finalizationQueueOverflows.get()} — entering CatchingUp, degrading partials, preventing new utterances until depth recovers")
            } else {
                Log.w("SprichIME", "queue still at capacity depth=$depthBefore pending=${pending.token.utteranceId} overflows=${finalizationQueueOverflows.get()}")
            }
            // Degrade speculative partial work first: we could cancel partial job, but we at least log and keep final.
            // Do NOT discard pending — speech must never be silently discarded.
        }
        val newDepth = queueDepth.incrementAndGet()
        lastQueueDepth = newDepth
        if (newDepth.toLong() > pendingQueuePeak.get()) pendingQueuePeak.set(newDepth.toLong())
        Log.i("SprichIME", "enqueuePending token=${pending.token} pcm=${pending.pcm.size} queueDepth=$newDepth peak=${pendingQueuePeak.get()} reason=${pending.reason} catchingUp=$catchingUp")
        // UNLIMITED channel guarantees no suspend and no丢弃; backpressure is via catchingUp flag, not channel suspension (avoids unbounded suspended coroutines holding PCM)
        val result = pendingChannel.trySend(pending)
        if (!result.isSuccess) {
            // Should not happen with UNLIMITED, but handle for bounded case
            Log.e("SprichIME", "pendingChannel trySend failed depth=$newDepth pending=${pending.token.utteranceId} result=$result")
            queueDepth.decrementAndGet()
            // As last resort, preserve frozen utterance by re-enqueueing via direct channel send in separate coroutine (bounded but must not lose)
            scope.launch { pendingChannel.send(pending) }
        }
        endpointPending.set(true)
    }

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
            // Per-utterance LID for Auto: run Whisper Tiny before Canary if config is Auto (production-safe, no mock)
            var effectiveConfig = pending.config
            var lidLatencyMs: Long = 0
            var lidDetected: String = pending.config.resolvedLanguageTag()
            var lidOutcomeForLog: String = "explicit"
            var useFastConformerFallback = false
            if (pending.config.speechLanguage is SpeechLanguage.Auto) {
                try {
                    if (!lidEngine.isLoaded()) {
                        val lidLoad = lidEngine.load()
                        Log.i("SprichIME", "LID auto-load utt=${token.utteranceId} success=${lidLoad.isSuccess} err=${lidLoad.exceptionOrNull()?.message}")
                    }
                    val lidOutcome = lidEngine.identify(pending.pcm)
                    when (lidOutcome) {
                        is com.sprich.app.speech.lid.WhisperLidEngine.LidOutcome.Detected -> {
                            lidLatencyMs = lidOutcome.latencyMs
                            lidDetected = lidOutcome.rawCode
                            lidOutcomeForLog = "Detected"
                            effectiveConfig = pending.config.copy(
                                language = lidOutcome.language,
                                speechLanguage = SpeechLanguage.Fixed(lidOutcome.language.code)
                            )
                            Log.i("SprichIME", "LID per-utterance utt=${token.utteranceId} raw=${lidOutcome.rawCode} detected=${lidOutcome.language} latencyMs=${lidOutcome.latencyMs} effective=${effectiveConfig.resolvedLanguageTag()} pcmSamples=${pending.pcm.size} rms=${String.format(java.util.Locale.US,"%.5f", pcmRms)}")
                        }
                        is com.sprich.app.speech.lid.WhisperLidEngine.LidOutcome.Unsupported -> {
                            lidLatencyMs = lidOutcome.latencyMs
                            lidDetected = lidOutcome.rawCode
                            lidOutcomeForLog = "Unsupported"
                            Log.w("SprichIME", "LID unsupported raw=${lidOutcome.rawCode} utt=${token.utteranceId} — trying FastConformer fallback if available, not EN")
                            // Use validated multilingual fallback if available, else fail closed
                            val mm = com.sprich.app.models.manager.ModelManager(this@SprichIME)
                            if (mm.isFastConformerReady()) {
                                useFastConformerFallback = true
                                Log.i("SprichIME", "LID unsupported, FastConformer fallback will be used utt=${token.utteranceId}")
                            } else {
                                Log.w("SprichIME", "LID unsupported and no FastConformer — failing Auto utterance utt=${token.utteranceId} (no EN fallback)")
                                // Fail closed: do not insert wrong-language transcript
                                // We will return without committing, preserving audio correctness
                                // For now, treat as failed and drop (no commit)
                                // To avoid silent drop, we could keep pending for retry, but spec says preserve correctness
                                // So we just return without commit
                                maybeClearActiveStateForToken(token)
                                return
                            }
                        }
                        is com.sprich.app.speech.lid.WhisperLidEngine.LidOutcome.Failed -> {
                            lidLatencyMs = lidOutcome.latencyMs
                            lidDetected = "failed:${lidOutcome.reason}"
                            lidOutcomeForLog = "Failed"
                            Log.w("SprichIME", "LID failed utt=${token.utteranceId} reason=${lidOutcome.reason} — trying FastConformer fallback, not EN")
                            val mm = com.sprich.app.models.manager.ModelManager(this@SprichIME)
                            if (mm.isFastConformerReady()) {
                                useFastConformerFallback = true
                            } else {
                                Log.w("SprichIME", "LID failed and no fallback — failing Auto utterance")
                                maybeClearActiveStateForToken(token)
                                return
                            }
                        }
                        is com.sprich.app.speech.lid.WhisperLidEngine.LidOutcome.Unavailable -> {
                            lidDetected = "unavailable:${lidOutcome.reason}"
                            lidOutcomeForLog = "Unavailable"
                            Log.w("SprichIME", "LID unavailable utt=${token.utteranceId} reason=${lidOutcome.reason} — trying FastConformer fallback, not EN")
                            val mm = com.sprich.app.models.manager.ModelManager(this@SprichIME)
                            if (mm.isFastConformerReady()) {
                                useFastConformerFallback = true
                            } else {
                                Log.w("SprichIME", "LID unavailable and no fallback — failing Auto utterance")
                                maybeClearActiveStateForToken(token)
                                return
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("SprichIME", "LID exception utt=${token.utteranceId}", e)
                    // Fail closed, try fallback
                    val mm = try { com.sprich.app.models.manager.ModelManager(this@SprichIME) } catch (_: Exception) { null }
                    if (mm != null && mm.isFastConformerReady()) {
                        useFastConformerFallback = true
                        lidDetected = "exception-fallback"
                        lidOutcomeForLog = "Failed"
                    } else {
                        Log.w("SprichIME", "LID exception and no fallback — failing Auto utterance")
                        maybeClearActiveStateForToken(token)
                        return
                    }
                }
            }
            val t0 = android.os.SystemClock.elapsedRealtime()
            nativeDecodeStarts++
            // Use immutable snapshot and its immutable effectiveConfig — never mutate live buffer
            // If LID failed/unsupported and FastConformer is available, use it as validated multilingual fallback (not EN)
            val finalTranscript = if (useFastConformerFallback) {
                try {
                    if (!fastConformerEngine.isLoaded()) {
                        val fLoad = fastConformerEngine.load()
                        Log.i("SprichIME", "FastConformer fallback load utt=${token.utteranceId} success=${fLoad.isSuccess}")
                    }
                    fastConformerEngine.transcribeSnapshot(pending.pcm, effectiveConfig)
                } catch (e: Exception) {
                    Log.w("SprichIME", "FastConformer fallback failed utt=${token.utteranceId}", e)
                    com.sprich.app.speech.api.FinalTranscript("")
                }
            } else {
                engine.transcribeSnapshot(pending.pcm, effectiveConfig)
            }
            val elapsed = android.os.SystemClock.elapsedRealtime() - t0
            // Raw vs post triage: raw is finalTranscript.text, post will be after parser
            val debugTraceEnabled = try { prefs.debugTranscriptTrace.first() } catch (_: Exception) { false }
            if (debugTraceEnabled) {
                Log.i("SprichIME", "RAW_ASR token=${token.utteranceId} text=\"${finalTranscript.text.take(80)}\"")
            } else {
                Log.i("SprichIME", "finalizePending decoded token=$token elapsedMs=$elapsed textLen=${finalTranscript.text.length} queueDepth=${lastQueueDepth} rms=${String.format(java.util.Locale.US,"%.5f", pcmRms)} durationMs=$pcmDurationMs")
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

            var text = finalTranscript.text.trim()
            // Punctuation triage: capture three stages for debug
            val raw = finalTranscript.text
            // POST_PROCESS stage: after spoken command parsing and typography normalization, before editor
            // For debug tracing: log RAW_ASR vs POST_PROCESS vs EDITOR_FINAL lengths only (no content by default)
            Log.i("SprichIME", "punctuationTriage token=${token.utteranceId} RAW_ASR_len=${raw.length} POST_len=${text.length} lang=${effectiveConfig.resolvedLanguageTag()} lidDetected=$lidDetected lidMs=$lidLatencyMs")
            // Typography normalization already applied via SpokenEditingParser (POST_PROCESS)
            // Remote fallback uses pending.pcm isolated, not global — use effective language
            val sttModeRaw = try { prefs.sttModeRaw.first() } catch (_: Exception) { "local" }
            if (sttModeRaw == "remote" || (sttModeRaw == "fallback" && text.isBlank() && pending.pushedSamples > 8000)) {
                statusText?.text = "Transcribing (cloud)…"
                val fallbackSnap = pending.pcm // immutable isolated, never B's audio
                Log.i("SprichIME", "remoteStt fallback token=$token snapshotSamples=${fallbackSnap.size} pendingPushed=${pending.pushedSamples} effectiveLang=${effectiveConfig.resolvedLanguageTag()}")
                val result = remoteStt.transcribe(fallbackSnap, SAMPLE_RATE.toInt(), effectiveConfig.language)
                val remoteText = result.getOrNull()?.trim().orEmpty()
                Log.i("SprichIME", "remoteStt mode=$sttModeRaw ok=${result.isSuccess} chars=${remoteText.length}")
                if (remoteText.isNotBlank()) text = remoteText
            }

            val aiEnabled = try { prefs.aiEnabled.first() } catch (_: Exception) { false }
            if (aiEnabled && text.isNotBlank()) {
                statusText?.text = "Polishing…"
                val polished = grammarFixer.fix(text, effectiveConfig.language)
                val fixed = polished.getOrNull()?.trim().orEmpty()
                Log.i("SprichIME", "aiPolish ok=${polished.isSuccess} inChars=${text.length} outChars=${fixed.length}")
                if (fixed.isNotBlank()) text = fixed
            }

            val applied = if (text.isBlank()) {
                Log.w("SprichIME", "final transcript empty token=$token elapsedMs=$elapsed pushedSamples=${pending.pushedSamples} effectiveLang=${effectiveConfig.resolvedLanguageTag()} lid=$lidDetected")
                // Even blank final must reset only if it owns active capture; otherwise preserve B
                false
            } else {
                val ok = applyFinalText(token, text, effectiveConfig.language)
                Log.i("SprichIME", "final token=$token chars=${text.length} applied=$ok elapsedMs=$elapsed lang=${effectiveConfig.resolvedLanguageTag()} lid=$lidDetected postLen=${text.length} lidMs=$lidLatencyMs")
                if (ok) finalCommitCount++
                ok
            }
            if (text.isNotBlank() && !applied) {
                failSession(token.generation, "final text insertion failed token=$token", "Could not insert text", "Refocus the field and retry", null)
                return
            }
            if (applied) {
                latency.mark("textCommitted")
                celebrateCommit()
            }

            lastPartialText = ""
            // Only clear active capture state if this pending still owns it; otherwise B is active and must not be corrupted.
            maybeClearActiveStateForToken(token)
            // Also clear legacy global frozen only if it equals this pending's pcm (avoid clearing B's snapshot)
            if (frozenUtterancePcm != null && frozenUtterancePcm?.size == pending.pcm.size) {
                // Content check optional: if same size, assume it's this pending; otherwise B's newer snapshot has different size likely.
                // To be safe, only clear if token matches current token
                if (currentUtteranceToken?.utteranceId == token.utteranceId) {
                    frozenUtterancePcm = null
                }
            }
            // Do NOT call engine.clearUtteranceCapture() here — that would erase B's live buffer.
            // Engine's active buffer is owned by live capture; per-utterance snapshot already isolated.
            // Only need to ensure partial decoding continues for next utterance.
            if (reason == StopReason.ENDPOINT) {
                try {
                    // Re-arm for continuous listening only if session still valid and no generation change.
                    // beginSession clears pcmRing/stabilizer but NOT utteranceBuffer that holds B's active capture? Actually beginSession clears utteranceBuffer — that would erase B!
                    // So we must NOT call beginSession if B is already capturing (utteranceActive true with different token).
                    val isBActive = currentUtteranceToken != null && currentUtteranceToken?.utteranceId != token.utteranceId && utteranceActive.get()
                    if (isBActive) {
                        Log.i("SprichIME", "finalizePending ENDPOINT skip re-arm, B already active token=${currentUtteranceToken} A=$token")
                    } else {
                        engine.beginSession(pending.config)
                        if (session.state.value !is SessionState.Listening) {
                            try { session.onListeningAgain() } catch (_: Exception) {}
                        }
                        Log.i("SprichIME", "finalizePending ENDPOINT re-armed token=$token fieldStill=${fieldController.isCurrentSession(token.sessionId)}")
                    }
                    finishedWithRetry = true
                } catch (t: Throwable) {
                    Log.e("SprichIME", "beginSession after ENDPOINT finalize failed token=$token", t)
                    failSession(token.generation, "begin after finalize failed", "Speech engine stopped", "Tap to retry", t)
                }
            } else if (reason == StopReason.USER_STOP) {
                Log.i("SprichIME", "finalizePending USER_STOP committed token=$token awaiting termination")
                finishedWithRetry = false
            }
        } catch (e: CancellationException) {
            Log.i("SprichIME", "finalizePending cancelled token=${pending.token} reason=${pending.reason}")
            maybeClearActiveStateForToken(pending.token)
            throw e
        } catch (t: Throwable) {
            failSession(token.generation, "finalization failed token=$token reason=$reason", "Transcription failed", "Tap to retry", t)
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
        // Build immutable snapshot — copy PCM now before live buffer reused for next utterance
        val snap: ShortArray = try {
            frozenUtterancePcm?.copyOf() ?: engine.snapshotUtterancePcm().copyOf()
        } catch (_: Exception) { ShortArray(0) }
        val pending = PendingUtterance(
            token = token,
            pcm = snap,
            config = activeConfig.copy(),
            pushedSamples = pipelinePushedSampleCount,
            reason = reason,
            endpointTimestampNanos = System.nanoTime(),
        )
        if (reason == StopReason.USER_STOP) {
            // USER_STOP must be synchronous for caller awaiting termination ordering; decode before generation bump
            finalizePending(pending)
        } else {
            // ENDPOINT queued — allows capture of B while A decodes
            enqueuePending(pending)
        }
    }

    // Legacy wrapper for tests — delegates to finalizeOnce with ENDPOINT
    private suspend fun finalizeUtterance(generation: Long) {
        val token = currentUtteranceToken ?: UtteranceToken(session.sessionId, generation, utteranceIdCounter.get(), currentFieldId, fieldGeneration.get(), try { currentInputConnection } catch (_: Exception) { null })
        finalizeOnce(token, StopReason.ENDPOINT)
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
                    // Only editor rejection may retry via direct composition — still validated
                    Log.w("SprichIME", "controller EditorRejected, retrying direct commit token=$token")
                    composition.applyUpdate(inputConnection, finalText, "", true)
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
        try { engine.clearUtteranceCapture() } catch (_: Exception) {}
        session.error(reason)
        statusText?.text = userStatus
        (statusText?.tag as? TextView)?.text = userHint
        writeDiagnostics("error=$reason generation=$generation staleDrops=$staleCallbackDrops claims=$finalizationClaims commits=$finalCommitCount")
    }

    private fun stopDictation(reason: StopReason = StopReason.USER_STOP) {
        val wasActive = isDictationRunning()
        val generationAtStop = sessionGeneration.get()
        Log.i("SprichIME", "stopDictation reason=$reason wasActive=$wasActive generation=$generationAtStop chunks=$pipelineChunkCount pushed=$pipelinePushedSampleCount vadState=${vad.currentState().name} field=$currentFieldId")
        try { startJob?.cancel() } catch (_: Exception) {}
        startJob = null
        try { audio.stop() } catch (_: Exception) {}

        // Determine if this stop should finalize current utterance (only USER_STOP with sufficient audio)
        val shouldCommitAsFinalizing = when (reason) {
            StopReason.USER_STOP -> wasActive && pipelinePushedSampleCount > 8000
            StopReason.ENDPOINT -> false // endpoint path uses finalizeOnce directly, not stopDictation
            else -> false
        }

        if (shouldCommitAsFinalizing) {
            // USER_STOP must NOT invalidate its own token before finalization completes.
            // Keep generation unchanged during finalize; advance only after completion.
            // Do not cancel endpointJob yet — let finalizeOnce claim race be atomic.
            val token = currentUtteranceToken ?: UtteranceToken(session.sessionId, generationAtStop, utteranceIdCounter.get(), currentFieldId, fieldGeneration.get(), try { currentInputConnection } catch (_: Exception) { null })
            if (frozenUtterancePcm == null) {
                frozenUtterancePcm = try { engine.snapshotUtterancePcm() } catch (_: Exception) { ShortArray(0) }
            }
            // Stop accepting new mic data already done via audio.stop(); endpoint will handle freeze.
            scope.launch {
                try {
                    finalizeOnce(token, StopReason.USER_STOP)
                } catch (t: Throwable) {
                    Log.w("SprichIME", "stopCommit finalizeOnce failed token=$token", t)
                } finally {
                    // After USER_STOP finalization, terminate session / advance generation (never re-arm)
                    val newGen = sessionGeneration.incrementAndGet()
                    fieldGeneration.incrementAndGet()
                    try { engineJob?.cancel() } catch (_: Exception) {}
                    engineJob = null
                    endpointJob = null
                    // Ensure termination — USER_STOP must stop listening, not keep re-armed
                    try { engine.cancelSession() } catch (_: Exception) {}
                    try { engine.clearUtteranceCapture() } catch (_: Exception) {}
                    utteranceActive.set(false)
                    endpointPending.set(false)
                    lastPartialText = ""
                    frozenUtterancePcm = null
                    currentUtteranceToken = null
                    pipelineChunkCount = 0L; pipelineSampleCount = 0L; pipelinePushedSampleCount = 0L
                    vad.reset()
                    lastVadState = Vad.State.SILENCE
                    try { composition.discardPartial(currentInputConnection) } catch (_: Exception) {}
                    // Controller already transitioned Inserting->Listening via commitUtterance, but USER_STOP must end.
                    // Force session to Idle.
                    try { if (session.state.value !is SessionState.Idle) session.end() } catch (_: Exception) { try { session.idle() } catch (_: Exception) {} }
                    // Do not clear currentFieldId here? USER_STOP terminates dictation but field may remain focused.
                    // Keep field for next manual start; generation bump already prevents stale inserts.
                    updateImeUi(false)
                    writeDiagnostics("stopped USER_STOP committed reason=$reason prevGen=$generationAtStop newGen=$newGen token=$token claims=$finalizationClaims commits=$finalCommitCount drops=$staleCallbackDrops")
                }
            }
            if (wasActive) vibrateStop()
            Log.i("SprichIME", "dictation USER_STOP finalization scheduled token=$token generation=$generationAtStop")
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
        try { engineJob?.cancel() } catch (_: Exception) {}
        engineJob = null
        try { engine.cancelSession() } catch (_: Exception) {}
        try { engine.clearUtteranceCapture() } catch (_: Exception) {}
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
        val text = Diagnostics.collect(this, engine.engineId, languageTag = activeConfig.resolvedLanguageTag(), task = activeConfig.resolvedTask().name, sessionId = session.sessionId) +
            event + "\n" +
            latency.report() + "\n" +
            "audioActive=${audio.isActive()} vad=${vad.currentState()} engineLoaded=${engine.isLoaded()} sessionId=${session.sessionId}\n"
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
            val ok = ic.deleteSurroundingText(toDelete, 0)
            if (!ok) {
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL))
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL))
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


    companion object {
        private const val SAMPLE_RATE = 16_000L
        private const val PRE_ROLL_MS = 400
    }
}
