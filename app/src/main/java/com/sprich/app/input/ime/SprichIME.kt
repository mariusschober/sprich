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
import com.sprich.app.core.perf.LatencyTracker
import com.sprich.app.core.vad.Vad
import com.sprich.app.input.commands.SpokenEditingParser
import com.sprich.app.input.composition.CompositionManager
import com.sprich.app.input.lifecycle.DictationSession
import com.sprich.app.input.lifecycle.SessionState
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.speech.api.EngineType
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechEngine
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.canary.CanaryEngine
import com.sprich.app.speech.nemotron.NemotronEngine
import com.sprich.app.SprichApp
import com.sprich.app.core.perf.ThermalMonitor
import com.sprich.app.speech.warmup.ModelWarmthManager
import com.sprich.app.speech.whisper.WhisperEngine
import com.sprich.app.storage.Preferences
import com.sprich.app.vocab.PersonalVocabStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SprichIME : InputMethodService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engineMutex = Mutex()

    private lateinit var prefs: Preferences
    private lateinit var modelManager: ModelManager
    private lateinit var latency: LatencyTracker
    private lateinit var session: DictationSession
    private lateinit var composition: CompositionManager
    private lateinit var audio: AudioCapture
    private lateinit var vad: Vad

    @Volatile private var currentEngine: SpeechEngine? = null
    private var engineJob: Job? = null
    private var instantMode: Boolean = false
    private var language: Language = Language.AUTO
    private var commandsEnabled: Boolean = true
    private var isPasswordField: Boolean = false
    private lateinit var vocabRepo: com.sprich.app.vocab.VocabRepository
    private val vocabStore get() = if (::vocabRepo.isInitialized) vocabRepo.store() else com.sprich.app.vocab.PersonalVocabStore()
    private var hapticsEnabled: Boolean = true
    private lateinit var warmthManager: ModelWarmthManager
    private val thermalMonitor = ThermalMonitor { temp -> Log.w("SprichIME", "thermal throttle $temp°C") }

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
            modelManager = ModelManager(this)
            latency = LatencyTracker()
            session = DictationSession(latency)
            composition = CompositionManager()
            audio = AudioCapture()
            vad = Vad()
            warmthManager = (application as SprichApp).warmthManager
            scope.launch { try { vocabRepo.load() } catch (_: Exception) {} }

            // Observe prefs — catch DataStore IOException via prefs flows already handle it
            scope.launch {
                try { prefs.instantMode.collect { instantMode = it } } catch (e: Exception) { Log.w("SprichIME", "instantMode collect fail", e) }
            }
            scope.launch {
                try { prefs.language.collect { language = it } } catch (e: Exception) { Log.w("SprichIME", "language collect fail", e) }
            }
            scope.launch {
                try { prefs.commands.collect { commandsEnabled = it } } catch (e: Exception) { Log.w("SprichIME", "commands collect fail", e) }
            }
            scope.launch {
                try { prefs.haptics.collect { hapticsEnabled = it } } catch (e: Exception) { Log.w("SprichIME", "haptics collect fail", e) }
            }
            scope.launch {
                try {
                    prefs.engineType.collect { e -> switchEngine(e) }
                } catch (e: Exception) { Log.w("SprichIME", "engineType collect fail", e) }
            }
            scope.launch { switchEngine(EngineType.FAST) }
            // Warmth: preload model for <100ms focus→mic
            try { warmthManager.setEngine(WhisperEngine(this)); warmthManager.preloadIfNeeded() } catch (_: Exception) {}

            // Observe session for UI — update dot/text without Compose
            scope.launch {
                session.state.collect { state ->
                    val listening = state is SessionState.Listening || state is SessionState.Speech
                    updateImeUi(listening)
                    if (listening) { try { warmthManager.markActive() } catch (_: Exception) {}; try { thermalMonitor.start() } catch (_: Exception) {} }
                    else { try { thermalMonitor.stop() } catch (_: Exception) {} }
                }
            }
        } catch (e: Exception) {
            Log.e("SprichIME", "onCreate failed", e)
        }
    }

    private suspend fun switchEngine(type: EngineType) {
        val next: SpeechEngine = when (type) {
            EngineType.FAST -> WhisperEngine(this)
            EngineType.ACCURATE -> CanaryEngine(this, modelManager)
            EngineType.STREAMING -> NemotronEngine(this, modelManager)
        }
        val res = try { next.load() } catch (e: Exception) { Result.failure(e) }
        engineMutex.withLock {
            val prev = currentEngine
            if (res.isSuccess) {
                currentEngine = next
                try { prev?.unload() } catch (_: Exception) {}
                Log.i("SprichIME", "switched to ${next.engineId}")
            } else {
                Log.w("SprichIME", "load ${next.engineId} failed: ${res.exceptionOrNull()?.message}, fallback")
                if (type != EngineType.FAST) {
                    val fallback = WhisperEngine(this)
                    val fbRes = try { fallback.load() } catch (e: Exception) { Result.failure(e) }
                    if (fbRes.isSuccess) {
                        currentEngine = fallback
                        try { prev?.unload() } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun isDark(): Boolean = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    override fun onCreateInputView(): View {
        return try {
            val dark = isDark()
            val bgColor = if (dark) Color.parseColor("#121212") else Color.parseColor("#FAFAF8")
            val borderColor = if (dark) Color.parseColor("#333333") else Color.parseColor("#E0E0DE")
            val micIdleColor = if (dark) Color.parseColor("#F5F5F3") else Color.parseColor("#111111")
            // Container with top hairline + lifted bottom for gesture nav
            val container = FrameLayout(this).apply {
                setBackgroundColor(bgColor)
                val border = View(context).apply {
                    setBackgroundColor(borderColor)
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(1), Gravity.TOP)
                }
                addView(border)
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(18))
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL)
                isClickable = true
                isFocusable = true
                setOnClickListener { toggleDictation() }
            }

            val micBox = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(micIdleColor)
                }
                background = bg
                isClickable = true
                isFocusable = true
                contentDescription = "Tap to speak"
                setOnClickListener { toggleDictation() }
            }
            val micIcon = android.widget.ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                setImageResource(com.sprich.app.R.drawable.ic_mic)
                setColorFilter(Color.WHITE)
                // center icon
            }
            micBox.addView(micIcon)

            // Center text block — minimalism + pleasure: two lines + subtle waveform
            val textBlock = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                    marginEnd = dp(12)
                }
                isClickable = true
                setOnClickListener { toggleDictation() }
            }
            val statusColor = if (dark) Color.parseColor("#F5F5F3") else Color.parseColor("#111111")
            val hintColor = if (dark) Color.parseColor("#A0A0A0") else Color.parseColor("#8A8A8A")
            val status = TextView(this).apply {
                text = "Tap to speak"
                setTextColor(statusColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val hint = TextView(this).apply {
                text = "Words appear at cursor"
                setTextColor(hintColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
            }
            // Waveform — 3 bars, quiet alive feeling, visible only when listening
            val wave = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                }
            }
            val bars = (0..2).map {
                View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(3), dp(10)).apply {
                        marginStart = if (it == 0) 0 else dp(3)
                    }
                    val bg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(2).toFloat()
                        setColor(Color.parseColor("#FF4D76"))
                    }
                    background = bg
                    alpha = 0.85f
                }
            }
            bars.forEach { wave.addView(it) }
            textBlock.addView(status)
            textBlock.addView(hint)
            textBlock.addView(wave)

            val kbBg = if (dark) Color.parseColor("#2A2A2A") else Color.parseColor("#EFEFEF")
            val kbIconColor = if (dark) Color.parseColor("#F5F5F3") else Color.parseColor("#111111")
            val kbBtn = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(kbBg)
                }
                background = bg
                isClickable = true
                isFocusable = true
                contentDescription = "Switch keyboard"
                setOnClickListener { switchToNextKeyboard() }
            }
            val kbIcon = android.widget.ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                setImageResource(com.sprich.app.R.drawable.ic_keyboard)
                setColorFilter(kbIconColor)
            }
            kbBtn.addView(kbIcon)

            row.addView(micBox)
            row.addView(textBlock)
            row.addView(kbBtn)
            container.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(1) // below border
            })

            // keep refs for updates
            statusText = status
            statusText?.tag = hint
            dotView = micIcon
            micContainer = micBox
            rootView = container
            waveform = wave
            waveformBars = bars

            updateImeUi(false)

            container
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
            val micIdle = if (dark) Color.parseColor("#F5F5F3") else Color.parseColor("#111111")
            if (isListening) {
                statusText?.text = "Listening…"
                hint?.text = "Tap to stop"
                statusText?.setTextColor(statusColor)
                (micContainer?.background as? GradientDrawable)?.setColor(Color.parseColor("#FF4D76"))
                (dotView as? android.widget.ImageView)?.setColorFilter(Color.WHITE)
                micContainer?.contentDescription = "Stop listening"
                micContainer?.animate()?.cancel()
                micContainer?.scaleX = 1f; micContainer?.scaleY = 1f
                micContainer?.animate()?.scaleX(1.08f)?.scaleY(1.08f)?.setDuration(180)?.setInterpolator(android.view.animation.OvershootInterpolator(2f))?.withEndAction {
                    micContainer?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(160)?.start()
                }?.start()
                waveform?.visibility = View.VISIBLE
                startWaveform()
                pulseAnimator?.cancel()
                pulseAnimator = android.animation.ValueAnimator.ofFloat(0.6f, 1f).apply {
                    duration = 800
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    addUpdateListener { anim -> waveform?.alpha = anim.animatedValue as Float }
                    start()
                }
            } else {
                statusText?.text = "Tap to speak"
                hint?.text = "Words appear at cursor"
                statusText?.setTextColor(statusColor)
                (micContainer?.background as? GradientDrawable)?.setColor(micIdle)
                (dotView as? android.widget.ImageView)?.setColorFilter(if (dark) Color.parseColor("#121212") else Color.WHITE)
                micContainer?.contentDescription = "Tap to speak"
                micContainer?.animate()?.cancel()
                micContainer?.scaleX = 1f; micContainer?.scaleY = 1f
                waveform?.visibility = View.GONE
                waveform?.alpha = 0.85f
                pulseAnimator?.cancel()
                stopWaveform()
            }
        } catch (_: Exception) {}
    }

    private fun startWaveform() {
        waveformJob?.cancel()
        waveformJob = scope.launch {
            val bars = waveformBars
            while (isActive) {
                try {
                    bars.forEachIndexed { i, bar ->
                        val base = 10
                        val amp = (6 + (Math.random() * 10)).toInt() + if (i == 1) 4 else 0
                        val h = dp(base + amp.coerceIn(4, 16))
                        bar.layoutParams.height = h
                        bar.requestLayout()
                    }
                    delay(140)
                } catch (_: Exception) { delay(140) }
            }
        }
    }
    private fun stopWaveform() {
        try { waveformJob?.cancel() } catch (_: Exception) {}
        waveformJob = null
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        try {
            super.onStartInput(info, restarting)
            isPasswordField = isPassword(info)
            latency.mark("onStartInput")
            // Warmth preload for next field
            try { warmthManager.preloadIfNeeded() } catch (_: Exception) {}
            if (isPasswordField) {
                Log.i("SprichIME", "password field, silent")
                stopDictation()
                return
            }
            composition.reset()
            if (instantMode) {
                scope.launch {
                    try { delay(40); startDictationIfNeeded() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e("SprichIME", "onStartInput failed", e)
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        try { warmthManager.preloadIfNeeded() } catch (_: Exception) {}
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        try { stopDictation() } catch (_: Exception) {}
        try { thermalMonitor.stop() } catch (_: Exception) {}
    }

    override fun onFinishInput() {
        try {
            super.onFinishInput()
            stopDictation()
            try { composition.finishIfActive(currentInputConnection) } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e("SprichIME", "onFinishInput failed", e)
        }
    }

    override fun onDestroy() {
        try { stopDictation() } catch (_: Exception) {}
        try { audio.release() } catch (_: Exception) {}
        scope.launch { try { currentEngine?.unload() } catch (_: Exception) {} }
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

    private fun toggleDictation() {
        try {
            val s = session.state.value
            if (s is SessionState.Listening || s is SessionState.Speech) stopDictation() else startDictationIfNeeded()
        } catch (e: Exception) { Log.e("SprichIME", "toggle failed", e) }
    }

    private fun startDictationIfNeeded() {
        try {
            if (isPasswordField) {
                statusText?.text = "Password field"
                (statusText?.tag as? TextView)?.text = "Dictation disabled here"
                return
            }
            if (session.state.value is SessionState.Listening || session.state.value is SessionState.Speech) return
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w("SprichIME", "RECORD_AUDIO not granted, abort dictation")
                try { session.error("mic permission") } catch (_: Exception) {}
                statusText?.text = "Mic permission needed"
                (statusText?.tag as? TextView)?.text = "Grant in Sprich app → Settings"
                // Vibrate error
                try { vibrateTick() } catch (_: Exception) {}
                return
            }
            val engine = currentEngine
            if (engine == null) {
                Log.w("SprichIME", "no engine loaded yet")
                try { session.error("no engine") } catch (_: Exception) {}
                statusText?.text = "Loading…"
                (statusText?.tag as? TextView)?.text = "Please wait a moment"
                scope.launch { switchEngine(EngineType.FAST) }
                return
            }
            try { session.start() } catch (_: Exception) {}
            latency.mark("audioStartRequested")
            vibrateTick()
            composition.reset()
            vad.reset()

            val started = try {
                audio.start { samples, ts ->
                    try {
                        val vadRes = vad.process(samples, 64)
                        if (vadRes.state == Vad.State.SPEECH && session.state.value is SessionState.Listening) {
                            scope.launch(Dispatchers.Main) { try { session.onSpeechOnset() } catch (_: Exception) {} }
                        }
                        engine.pushAudio(samples, ts)
                    } catch (e: Exception) { Log.w("SprichIME", "audio chunk fail", e) }
                }
            } catch (e: Exception) { Log.e("SprichIME", "audio.start exception", e); false }

            if (!started) {
                try { session.error("mic busy") } catch (_: Exception) {}
                statusText?.text = "Mic busy"
                (statusText?.tag as? TextView)?.text = "Try again or restart keyboard"
                return
            }
            latency.mark("audioActuallyRecording")
            try { session.onAudioStarted() } catch (_: Exception) {}

            try { engine.beginSession(SpeechSessionConfig(language = language, enablePunctuation = true, enableCommands = true)) } catch (e: Exception) { Log.e("SprichIME", "beginSession fail", e) }

            engineJob?.cancel()
            engineJob = scope.launch {
                try {
                    engine.partialTranscript().collectLatest { upd ->
                        try {
                            if (latency.delta("speechOnset", "firstHypothesis") == null && upd.stable.isNotEmpty()) latency.mark("firstHypothesis")
                            if (upd.stable.isNotEmpty() && latency.delta("speechOnset", "firstVisibleText") == null) latency.mark("firstVisibleText")
                            withContext(Dispatchers.Main) {
                                val ic = try { currentInputConnection } catch (_: Exception) { null }
                                if (ic == null) return@withContext
                                val stableVocab = try { vocabStore.apply(upd.stable) } catch (_: Exception) { upd.stable }
                                val unstableVocab = try { vocabStore.apply(upd.unstable) } catch (_: Exception) { upd.unstable }
                                if (upd.isFinal) {
                                    val full = listOf(stableVocab, unstableVocab).filter { it.isNotBlank() }.joinToString(" ").trim()
                                    val parsed = try { SpokenEditingParser.parse(full, language, commandsEnabled) } catch (_: Exception) { SpokenEditingParser.EditResult(full, false) }
                                    if (SpokenEditingParser.isDeleteCommand(parsed.text)) {
                                        val toDelete = if (parsed.text == "__DELETE_SENTENCE__") 120 else 40
                                        try { ic.deleteSurroundingText(toDelete, 0); composition.finishIfActive(ic) } catch (_: Exception) {}
                                    } else if (parsed.isCommand && parsed.text.isNotEmpty() && parsed.text.length <= 3) {
                                        try { composition.applyUpdate(ic, parsed.text, "", true) } catch (_: Exception) {}
                                    } else {
                                        try { composition.applyUpdate(ic, parsed.text, "", true) } catch (_: Exception) {}
                                    }
                                    latency.mark("textCommitted")
                                    try { session.onFinalizing(); delay(120); session.onListeningAgain() } catch (_: Exception) {}
                                } else {
                                    try { composition.applyUpdate(ic, stableVocab, unstableVocab, false) } catch (_: Exception) {}
                                }
                            }
                        } catch (e: Exception) { Log.e("SprichIME", "partial collect fail", e) }
                    }
                } catch (e: Exception) { Log.e("SprichIME", "engine collect outer fail", e) }
            }
            scope.launch {
                try {
                    while (session.requireActive()) {
                        delay(120)
                        if (vad.currentState() == Vad.State.UTTERANCE_END) {
                            if (!vad.hasSpeech()) { vad.reset(); continue }
                            latency.mark("endpointDetected")
                            try { engine.endUtterance() } catch (e: Exception) { Log.e("SprichIME", "endUtterance fail", e) }
                            vad.reset()
                        } else if (vad.currentState() == Vad.State.LONG_SILENCE) {
                            stopDictation(); break
                        }
                    }
                } catch (e: Exception) { Log.e("SprichIME", "vad loop fail", e) }
            }
        } catch (e: Exception) {
            Log.e("SprichIME", "startDictationIfNeeded outer fail", e)
            try { session.error("startFailed") } catch (_: Exception) {}
        }
    }

    private fun stopDictation() {
        val wasListening = try { session.state.value is SessionState.Listening || session.state.value is SessionState.Speech } catch (_: Exception) { false }
        try { audio.stop() } catch (_: Exception) {}
        try { engineJob?.cancel() } catch (_: Exception) {}
        try { composition.finishIfActive(try { currentInputConnection } catch (_: Exception) { null }) } catch (_: Exception) {}
        try { if (session.state.value !is SessionState.Idle) session.end() } catch (_: Exception) {}
        try { currentEngine?.cancelSession() } catch (_: Exception) {}
        updateImeUi(false)
        if (wasListening) vibrateStop()
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
                (getSystemService(Context.VIBRATOR_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
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
                (getSystemService(Context.VIBRATOR_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(30)
            }
        } catch (_: Exception) {}
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
