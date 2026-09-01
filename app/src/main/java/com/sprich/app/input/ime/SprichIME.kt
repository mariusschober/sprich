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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    // Per-utterance PCM capture — isolated from global ring, frozen at finalization
    private val utterancePcmLock = Any()
    private val utterancePcmBuffer = mutableListOf<Short>()
    @Volatile private var frozenUtterancePcm: ShortArray? = null

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
    private var language: Language = Language.AUTO
    private var speechLanguage: SpeechLanguage = SpeechLanguage.Auto
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
            scope.launch {
                try { prefs.language.collect { language = it } } catch (e: Exception) { Log.w("SprichIME", "language collect fail", e) }
            }
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
            synchronized(utterancePcmLock) { utterancePcmBuffer.clear(); frozenUtterancePcm = null }
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
            try { composition.finishIfActive(currentInputConnection) } catch (_: Exception) {}
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
            synchronized(utterancePcmLock) { utterancePcmBuffer.clear(); frozenUtterancePcm = null }
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

            if (
                result.state == Vad.State.SPEECH &&
                !endpointPending.get() &&
                utteranceActive.compareAndSet(false, true)
            ) {
                val preRoll = audio.snapshotPrebufferMs(PRE_ROLL_MS)
                pipelinePushedSampleCount += preRoll.size
                // Per-utterance PCM ownership: seed fresh buffer with pre-roll
                synchronized(utterancePcmLock) {
                    utterancePcmBuffer.clear()
                    frozenUtterancePcm = null
                    for (s in preRoll) utterancePcmBuffer.add(s)
                }
                // Tell engine to start per-utterance capture (isolated from previous utterance)
                try { engine.beginUtteranceCapture(preRoll) } catch (_: Exception) {}
                engine.pushAudio(preRoll, timestampNanos)
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
                synchronized(utterancePcmLock) {
                    if (frozenUtterancePcm == null) {
                        for (s in samples) utterancePcmBuffer.add(s)
                        if (utterancePcmBuffer.size > 16000 * 30) {
                            val drop = utterancePcmBuffer.size - 16000 * 30
                            repeat(drop) { if (utterancePcmBuffer.isNotEmpty()) utterancePcmBuffer.removeAt(0) }
                        }
                    }
                }
                engine.pushAudio(samples, timestampNanos)
            }

            if (
                result.state == Vad.State.UTTERANCE_END &&
                utteranceActive.compareAndSet(true, false) &&
                endpointPending.compareAndSet(false, true)
            ) {
                latency.mark("endpointDetected")
                // Freeze per-utterance PCM at endpoint so fallback cannot include next utterance
                synchronized(utterancePcmLock) {
                    frozenUtterancePcm = utterancePcmBuffer.toShortArray()
                }
                val token = currentUtteranceToken
                Log.i("SprichIME", "endpoint detected token=$token pushedSamples=$pipelinePushedSampleCount chunks=$pipelineChunkCount frozenSamples=${frozenUtterancePcm?.size ?: 0}")
                if (token != null) {
                    endpointJob = scope.launch { finalizeOnce(token, StopReason.ENDPOINT) }
                } else {
                    // No token (e.g., utteranceActive never true) — still try to finalize if we have audio
                    Log.w("SprichIME", "endpoint without token — creating synthetic token")
                    val synthetic = UtteranceToken(session.sessionId, generation, utteranceIdCounter.get(), currentFieldId, fieldGeneration.get(), try { currentInputConnection } catch (_: Exception) { null })
                    endpointJob = scope.launch { finalizeOnce(synthetic, StopReason.ENDPOINT) }
                }
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

    /**
     * Single authoritative finalization entry. Atomically claims the utterance; second caller
     * returns without decoding or inserting. Only one SpeechEngine.endUtterance() per utterance.
     */
    private suspend fun finalizeOnce(token: UtteranceToken, reason: StopReason) {
        var finishedWithRetry = false
        try {
            // Atomically claim — second caller for same utteranceId drops silently
            val claimed = synchronized(finalizedUtterances) {
                if (finalizedUtterances.contains(token.utteranceId)) false
                else { finalizedUtterances.add(token.utteranceId); true }
            }
            if (!claimed) {
                Log.w("SprichIME", "finalizeOnce duplicate claim dropped token=$token reason=$reason")
                staleCallbackDrops++
                return
            }
            finalizationClaims++
            Log.i("SprichIME", "finalizeOnce claimed token=$token reason=$reason generation=${token.generation} currentGen=${sessionGeneration.get()}")

            // Validate token is still current before expensive native decode
            if (token.generation != sessionGeneration.get() || !session.requireActive()) {
                Log.w("SprichIME", "finalizeOnce abandoned pre-decode stale generation token=$token current=${sessionGeneration.get()}")
                staleCallbackDrops++
                endpointPending.set(false)
                utteranceActive.set(false)
                return
            }
            if (token.sessionId != session.sessionId || !session.isSessionValid(token.sessionId)) {
                Log.w("SprichIME", "finalizeOnce abandoned pre-decode invalid session token=$token sessionId=${session.sessionId}")
                staleCallbackDrops++
                endpointPending.set(false)
                utteranceActive.set(false)
                return
            }
            if (token.fieldId != currentFieldId || token.fieldGeneration != fieldGeneration.get()) {
                Log.w("SprichIME", "finalizeOnce abandoned pre-decode field mismatch token=$token currentField=$currentFieldId fg=${fieldGeneration.get()}")
                staleCallbackDrops++
                endpointPending.set(false)
                utteranceActive.set(false)
                return
            }
            // Distinct stop reasons: only USER_STOP and ENDPOINT may finalize an active utterance.
            if (reason != StopReason.USER_STOP && reason != StopReason.ENDPOINT) {
                Log.i("SprichIME", "finalizeOnce cancelled by reason=$reason token=$token — prioritizing safety over speculative final")
                endpointPending.set(false)
                utteranceActive.set(false)
                vad.reset()
                lastVadState = Vad.State.SILENCE
                synchronized(utterancePcmLock) { utterancePcmBuffer.clear(); frozenUtterancePcm = null }
                try { engine.clearUtteranceCapture() } catch (_: Exception) {}
                currentUtteranceToken = null
                return
            }

            session.onFinalizing()
            Log.i("SprichIME", "finalizeOnce start token=$token pushedSamples=$pipelinePushedSampleCount reason=$reason")
            val t0 = android.os.SystemClock.elapsedRealtime()
            // Exactly one native decode per utterance — serialized inside engine via Mutex
            nativeDecodeStarts++
            val finalTranscript = engine.endUtterance()
            val elapsed = android.os.SystemClock.elapsedRealtime() - t0
            Log.i("SprichIME", "finalizeOnce decoded token=$token elapsedMs=$elapsed textLen=${finalTranscript.text.length}")

            // Re-validate all conditions immediately before insertion
            if (token.generation != sessionGeneration.get() || !session.requireActive()) {
                Log.w("SprichIME", "finalizeOnce abandoned post-decode stale generation token=$token")
                staleCallbackDrops++
                endpointPending.set(false)
                utteranceActive.set(false)
                vad.reset()
                lastVadState = Vad.State.SILENCE
                return
            }
            if (token.sessionId != session.sessionId || !session.isSessionValid(token.sessionId)) {
                Log.w("SprichIME", "finalizeOnce abandoned post-decode invalid session token=$token")
                staleCallbackDrops++
                endpointPending.set(false); utteranceActive.set(false); vad.reset(); lastVadState = Vad.State.SILENCE; return
            }
            if (token.fieldId != currentFieldId || token.fieldGeneration != fieldGeneration.get()) {
                Log.w("SprichIME", "finalizeOnce abandoned post-decode field mismatch token=$token")
                staleCallbackDrops++
                endpointPending.set(false); utteranceActive.set(false); vad.reset(); lastVadState = Vad.State.SILENCE; return
            }
            // Verify captured InputConnection still current (window not switched)
            val currentIc = try { currentInputConnection } catch (_: Exception) { null }
            if (currentIc == null) {
                Log.w("SprichIME", "finalizeOnce abandoned post-decode no InputConnection token=$token")
                staleCallbackDrops++
                endpointPending.set(false); utteranceActive.set(false); vad.reset(); lastVadState = Vad.State.SILENCE; return
            }
            // If fieldController already moved to new session, drop
            if (!fieldController.isCurrentSession(token.sessionId)) {
                Log.w("SprichIME", "finalizeOnce abandoned post-decode fieldController stale token=$token")
                staleCallbackDrops++
                endpointPending.set(false); utteranceActive.set(false); vad.reset(); lastVadState = Vad.State.SILENCE; return
            }

            var text = finalTranscript.text.trim()
            // Use frozen per-utterance PCM for fallback — never previous utterances
            val sttModeRaw = try { prefs.sttModeRaw.first() } catch (_: Exception) { "local" }
            if (sttModeRaw == "remote" || (sttModeRaw == "fallback" && text.isBlank() && pipelinePushedSampleCount > 8000)) {
                statusText?.text = "Transcribing (cloud)…"
                val snapshot = synchronized(utterancePcmLock) { frozenUtterancePcm ?: utterancePcmBuffer.toShortArray() }
                // Also try engine's frozen buffer as secondary source
                val engineSnap = try { engine.snapshotUtterancePcm() } catch (_: Exception) { ShortArray(0) }
                val fallbackSnap = if (snapshot.isNotEmpty()) snapshot else engineSnap
                Log.i("SprichIME", "remoteStt fallback token=$token snapshotSamples=${fallbackSnap.size} engineSnap=${engineSnap.size}")
                // Regression: snapshot must contain no samples from previous utterance. Verified via per-utterance buffer.
                val result = remoteStt.transcribe(fallbackSnap, SAMPLE_RATE.toInt(), activeConfig.language)
                val remoteText = result.getOrNull()?.trim().orEmpty()
                Log.i("SprichIME", "remoteStt mode=$sttModeRaw ok=${result.isSuccess} chars=${remoteText.length}")
                if (remoteText.isNotBlank()) text = remoteText
            }

            val aiEnabled = try { prefs.aiEnabled.first() } catch (_: Exception) { false }
            if (aiEnabled && text.isNotBlank()) {
                statusText?.text = "Polishing…"
                val polished = grammarFixer.fix(text, activeConfig.language)
                val fixed = polished.getOrNull()?.trim().orEmpty()
                Log.i("SprichIME", "aiPolish ok=${polished.isSuccess} inChars=${text.length} outChars=${fixed.length}")
                if (fixed.isNotBlank()) text = fixed
            }

            val applied = if (text.isBlank()) {
                Log.w("SprichIME", "final transcript empty token=$token elapsedMs=$elapsed pushedSamples=$pipelinePushedSampleCount")
                false
            } else {
                val ok = applyFinalText(token, text)
                Log.i("SprichIME", "final token=$token chars=${text.length} applied=$ok elapsedMs=$elapsed")
                if (ok) finalCommitCount++
                ok
            }
            if (text.isNotBlank() && !applied) {
                failSession(
                    token.generation,
                    "final text insertion failed token=$token",
                    "Could not insert text",
                    "Refocus the field and retry",
                    null,
                )
                return
            }
            if (applied) {
                latency.mark("textCommitted")
                celebrateCommit()
            }

            lastPartialText = ""
            vad.reset()
            lastVadState = Vad.State.SILENCE
            endpointPending.set(false)
            utteranceActive.set(false)
            pipelinePushedSampleCount = 0L
            synchronized(utterancePcmLock) { utterancePcmBuffer.clear(); frozenUtterancePcm = null }
            currentUtteranceToken = null
            try { engine.clearUtteranceCapture() } catch (_: Exception) {}
            try {
                engine.beginSession(activeConfig)
                session.onListeningAgain()
                // Field controller stays on same field/session for next utterance — no need to re-focus
                finishedWithRetry = true
                Log.i("SprichIME", "finalizeOnce done listeningAgain token=$token")
            } catch (t: Throwable) {
                Log.e("SprichIME", "beginSession after finalize failed token=$token", t)
                failSession(token.generation, "begin after finalize failed", "Speech engine stopped", "Tap to retry", t)
            }
        } catch (e: CancellationException) {
            Log.i("SprichIME", "finalizeOnce cancelled token=$token reason=$reason")
            endpointPending.set(false)
            utteranceActive.set(false)
            throw e
        } catch (t: Throwable) {
            failSession(
                token.generation,
                "finalization failed token=$token reason=$reason",
                "Transcription failed",
                "Tap to retry",
                t,
            )
        } finally {
            if (!finishedWithRetry && sessionGeneration.get() == token.generation && session.requireActive()) {
                // Defensive: keep pending cleared if we exited without re-arming
            }
        }
    }

    // Legacy wrapper for tests — delegates to finalizeOnce with ENDPOINT
    private suspend fun finalizeUtterance(generation: Long) {
        val token = currentUtteranceToken ?: UtteranceToken(session.sessionId, generation, utteranceIdCounter.get(), currentFieldId, fieldGeneration.get(), try { currentInputConnection } catch (_: Exception) { null })
        finalizeOnce(token, StopReason.ENDPOINT)
    }

    private fun applyFinalText(token: UtteranceToken, text: String): Boolean {
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
        val langForParser = activeConfig.language
        val parsed = try {
            SpokenEditingParser.parse(text, langForParser, commandsEnabled)
        } catch (_: Exception) {
            SpokenEditingParser.EditResult(text, false)
        }
        return if (SpokenEditingParser.isDeleteCommand(parsed.text)) {
            val toDelete = if (parsed.text == "__DELETE_SENTENCE__") 120 else 40
            val deleted = inputConnection.deleteSurroundingText(toDelete, 0)
            try { fieldController.commitFinal(token.sessionId, null, "") } catch (_: Exception) {}
            composition.finishIfActive(inputConnection)
            deleted
        } else {
            val finalText = try { vocabStore.apply(parsed.text) } catch (_: Exception) { parsed.text }
            // Use FieldSessionController as single authoritative owner for final insertion
            val viaController = try { fieldController.commitFinal(token.sessionId, inputConnection, finalText) } catch (_: Exception) { false }
            if (viaController) return true
            // Fallback direct (should not happen) — but still validated
            composition.applyUpdate(inputConnection, finalText, "", true)
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
        try { composition.finishIfActive(currentInputConnection) } catch (_: Exception) {}
        try { fieldController.cancelActive() } catch (_: Exception) {}
        utteranceActive.set(false)
        endpointPending.set(false)
        lastPartialText = ""
        synchronized(utterancePcmLock) { utterancePcmBuffer.clear(); frozenUtterancePcm = null }
        try { engine.clearUtteranceCapture() } catch (_: Exception) {}
        session.error(reason)
        statusText?.text = userStatus
        (statusText?.tag as? TextView)?.text = userHint
        writeDiagnostics("error=$reason generation=$generation staleDrops=$staleCallbackDrops claims=$finalizationClaims commits=$finalCommitCount")
    }

    private fun stopDictation(reason: StopReason = StopReason.USER_STOP) {
        val wasActive = isDictationRunning()
        val generationAtStop = sessionGeneration.get()
        val generation = sessionGeneration.incrementAndGet()
        Log.i("SprichIME", "stopDictation reason=$reason wasActive=$wasActive generation=$generation prev=$generationAtStop chunks=$pipelineChunkCount pushed=$pipelinePushedSampleCount vadState=${vad.currentState().name} field=$currentFieldId")
        try { startJob?.cancel() } catch (_: Exception) {}
        startJob = null
        try { audio.stop() } catch (_: Exception) {}
        // Do not cancel endpointJob immediately if USER_STOP may finalize — let finalizeOnce claim.
        // For other reasons, cancel immediately.
        if (reason != StopReason.USER_STOP && reason != StopReason.ENDPOINT) {
            try { endpointJob?.cancel() } catch (_: Exception) {}
            endpointJob = null
        }
        val shouldCommit = when (reason) {
            StopReason.USER_STOP -> wasActive && pipelinePushedSampleCount > 8000
            StopReason.ENDPOINT -> false // endpoint path uses finalizeOnce directly, not stopDictation
            else -> false
        }
        if (reason == StopReason.USER_STOP && shouldCommit) {
            // Single authoritative finalization — use token and reason, exactly one decode.
            val token = currentUtteranceToken ?: UtteranceToken(session.sessionId, generationAtStop, utteranceIdCounter.get(), currentFieldId, fieldGeneration.get(), try { currentInputConnection } catch (_: Exception) { null })
            // Freeze PCM now so fallback cannot include post-stop audio
            synchronized(utterancePcmLock) { if (frozenUtterancePcm == null) frozenUtterancePcm = utterancePcmBuffer.toShortArray() }
            // Keep engineJob alive until finalization finishes, then clean up.
            scope.launch {
                try {
                    finalizeOnce(token, StopReason.USER_STOP)
                } catch (t: Throwable) {
                    Log.w("SprichIME", "stopCommit finalizeOnce failed token=$token", t)
                } finally {
                    // After USER_STOP finalization, fully reset even if not re-arming
                    try { engineJob?.cancel() } catch (_: Exception) {}
                    engineJob = null
                    endpointJob = null
                    // If finalizeOnce already rearmed, don't clear again; otherwise clear.
                    if (session.state.value is SessionState.Idle || session.state.value is SessionState.Error) {
                        try { engine.cancelSession() } catch (_: Exception) {}
                        utteranceActive.set(false)
                        endpointPending.set(false)
                        lastPartialText = ""
                        synchronized(utterancePcmLock) { utterancePcmBuffer.clear(); frozenUtterancePcm = null }
                        try { engine.clearUtteranceCapture() } catch (_: Exception) {}
                        pipelineChunkCount = 0L; pipelineSampleCount = 0L; pipelinePushedSampleCount = 0L
                        vad.reset()
                        lastVadState = Vad.State.SILENCE
                        try { composition.finishIfActive(currentInputConnection) } catch (_: Exception) {}
                        try { if (session.state.value !is SessionState.Idle) session.end() } catch (_: Exception) { session.idle() }
                    }
                    updateImeUi(false)
                    writeDiagnostics("stopped reason=$reason generation=$generation commit=$shouldCommit token=$token claims=$finalizationClaims commits=$finalCommitCount drops=$staleCallbackDrops")
                }
            }
            if (wasActive) vibrateStop()
            Log.i("SprichIME", "dictation USER_STOP finalization scheduled token=$token generation=$generation")
            return
        }
        // Non-finalizing stop: cancel everything, make old callbacks stale
        fieldGeneration.incrementAndGet()
        currentFieldId = when (reason) {
            StopReason.FIELD_LOST, StopReason.INPUT_RESTARTED, StopReason.WINDOW_HIDDEN,
            StopReason.PASSWORD_FIELD, StopReason.ERROR, StopReason.SERVICE_DESTROYED -> null
            else -> currentFieldId
        }
        currentUtteranceToken = null
        try { endpointJob?.cancel() } catch (_: Exception) {}
        endpointJob = null
        try { engineJob?.cancel() } catch (_: Exception) {}
        engineJob = null
        try { engine.cancelSession() } catch (_: Exception) {}
        try { engine.clearUtteranceCapture() } catch (_: Exception) {}
        utteranceActive.set(false)
        endpointPending.set(false)
        lastPartialText = ""
        synchronized(utterancePcmLock) { utterancePcmBuffer.clear(); frozenUtterancePcm = null }
        pipelineChunkCount = 0L; pipelineSampleCount = 0L; pipelinePushedSampleCount = 0L
        vad.reset()
        lastVadState = Vad.State.SILENCE
        try { composition.finishIfActive(currentInputConnection) } catch (_: Exception) {}
        try { if (session.state.value !is SessionState.Idle) session.end() } catch (_: Exception) { session.idle() }
        // Also inform fieldController for stale protection
        try { currentFieldId?.let { fieldController.onFieldLost(it) } } catch (_: Exception) {}
        updateImeUi(false)
        if (wasActive) vibrateStop()
        Log.i("SprichIME", "dictation stopped reason=$reason generation=$generation field=$currentFieldId")
        writeDiagnostics("stopped reason=$reason generation=$generation fieldGen=${fieldGeneration.get()} claims=$finalizationClaims commits=$finalCommitCount drops=$staleCallbackDrops")
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
            try { composition.finishIfActive(ic) } catch (_: Exception) {}
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
