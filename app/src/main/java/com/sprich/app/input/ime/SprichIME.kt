package com.sprich.app.input.ime

import android.content.Context
import com.sprich.app.api.*
import com.sprich.app.speech.refinement.RefinementProviderFactory
import kotlinx.coroutines.flow.drop
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
import com.sprich.app.diagnostics.TelemetryEventType
import com.sprich.app.diagnostics.UtteranceOutcome
import com.sprich.app.diagnostics.UtteranceTelemetry
import com.sprich.app.core.perf.LatencyTracker
import com.sprich.app.core.vad.Vad
import com.sprich.app.input.commands.AcousticBasis
import com.sprich.app.input.commands.CommandPolicy
import com.sprich.app.input.commands.ParsedDictation
import com.sprich.app.input.commands.SpokenEditingParser
import com.sprich.app.input.commands.TranscriptOrigin
import com.sprich.app.input.composition.CompositionManager
import com.sprich.app.input.lifecycle.DictationSession
import com.sprich.app.input.lifecycle.FieldSessionController
import com.sprich.app.input.lifecycle.QueueAdmission
import com.sprich.app.input.lifecycle.QueuedUtterance
import com.sprich.app.input.lifecycle.SessionState
import com.sprich.app.input.lifecycle.StopReason
import com.sprich.app.input.lifecycle.UtteranceQueue
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
import com.sprich.app.speech.CoordinatorOutcome
import com.sprich.app.speech.remoteFailureOutcome
import com.sprich.app.speech.remote.ApiFailure
import com.sprich.app.speech.remote.RemoteSttConfig
import com.sprich.app.speech.remote.RemoteSttProvider
import com.sprich.app.speech.remote.OpenAiCompatibleSttProvider
import com.sprich.app.speech.remote.DeadlinePolicy
import com.sprich.app.speech.remote.LiveRemoteUtterance
import com.sprich.app.speech.remote.RemoteTranscriptUpdate
import com.sprich.app.speech.remote.VoiceApiStage
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

    // Android applies a per-app locale to activities, but some IME hosts keep the service
    // context in the phone locale. Resolve the selected Sprich locale for every visible IME
    // string so the bar and its accessibility actions change with the rest of the app.
    @Volatile private var localizedResourceKey: String? = null
    @Volatile private var localizedResources: android.content.res.Resources? = null
    @Volatile private var staticMessageIds: Pair<Int, Int>? = null

    private fun uiResources(): android.content.res.Resources {
        val locales = if (android.os.Build.VERSION.SDK_INT >= 33) {
            getSystemService(android.app.LocaleManager::class.java).applicationLocales
                .takeUnless { it.isEmpty } ?: resources.configuration.locales
        } else resources.configuration.locales
        val key = locales.toLanguageTags()
        localizedResources?.takeIf { localizedResourceKey == key }?.let { return it }
        val configuration = android.content.res.Configuration(resources.configuration).apply { setLocales(locales) }
        return createConfigurationContext(configuration).resources.also {
            localizedResources = it
            localizedResourceKey = key
        }
    }

    private fun uiString(id: Int, vararg arguments: Any): String = uiResources().getString(id, *arguments)

    /** Render a status and hint from one locale snapshot so a live locale change cannot mix languages. */
    private fun showStaticMessage(
        title: Int,
        hint: Int,
        strings: android.content.res.Resources = uiResources(),
    ) {
        staticMessageIds = title to hint
        statusText?.text = strings.getString(title)
        (statusText?.tag as? TextView)?.text = strings.getString(hint)
    }

    private fun refreshLocalizedMessage() {
        localizedResourceKey = null
        localizedResources = null
        staticMessageIds?.let { (title, hint) -> showStaticMessage(title, hint) }
            ?: refreshSessionUi()
    }

    private lateinit var prefs: Preferences
    private lateinit var latency: LatencyTracker
    private lateinit var session: DictationSession
    private lateinit var composition: CompositionManager
    private lateinit var audio: AudioCapture
    private lateinit var vad: Vad
    private lateinit var speechPresence: com.sprich.app.core.vad.SpeechPresence

    private lateinit var engine: CanaryEngine
    // Neutral authoritative PCM collector — single canonical source, independent of ASR engine.
    private val utteranceAudio = UtteranceAudioCollector(maxSamples = 16000 * 120)
    /**
     * P05 §5.3 split-continuation state. lastVadSilenceSample is in
     * utterance-domain samples (utteranceAudio.size() at silence time, -1 =
     * none this utterance). pendingSplitSeed carries the previous split's
     * overlap tail into the next utterance seed. lastSplitCommit holds the
     * last applied split-chain text for commit-time reconciliation; cleared
     * on fresh (non-split, non-reconciled) commits and session stop/fail.
     */
    private var lastVadSilenceSample: Int = -1
    private var pendingSplitSeed: ShortArray? = null
    private var lastSplitCommit: Pair<Long, String>? = null
    /**
     * P05 §5.3: session hard cap (120 s) fires once per capture session.
     * stopDictation(SESSION_LIMIT) reuses the USER_STOP drain path, so all
     * captured text is preserved; the soft-cap wrap-up stays a tested policy
     * hook (sessionSoftCapReached) without its own mic state.
     */
    private val sessionHardStopFired = AtomicBoolean(false)

    /** P05 §5.3: split-continuation state never survives session boundaries. */
    private fun clearSplitContinuationState() {
        try { pendingSplitSeed = null } catch (_: Exception) {}
        try { lastSplitCommit = null } catch (_: Exception) {}
        lastVadSilenceSample = -1
    }
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
    @Volatile private var currentFieldId: String? = null
    private var lastUtteranceEndSample = 0L // Audio-thread boundary; a faster API endpoint must not replay old pre-roll.
    private val fieldGeneration = AtomicLong(0L)
    private val utteranceIdCounter = AtomicLong(0L)
    @Volatile private var currentUtteranceToken: UtteranceToken? = null
    // Bounded exactly-once history (plan §6.5, Gate C): pure-JVM claim set,
    // host-tested in P03P07QueueDeadlineTelemetryTest. First claim wins;
    // history holds the last 128 utterance IDs.
    private val finalizedUtterances = com.sprich.app.input.lifecycle.FinalizationClaims()
    @Volatile private var currentFieldTokenIcHash: Int = 0

    // --- Phase 0A+2: immutable active utterance descriptor — frozen at onset, Settings changes apply to NEXT utterance only.
    // Once speech onset occurs, route / language configuration / transcription mode / provider config revision / refinement mode must not change for that utterance.
    data class ActiveUtterance(
        val token: UtteranceToken,
        val localRoute: LocalAsrRoute,
        val speechConfig: SpeechSessionConfig,
        val plan: UtterancePlan,
        val live: LiveRemoteUtterance? = null,
    )
    @Volatile private var activeUtterance: ActiveUtterance? = null

    // Prepared final action — command vs text separation (S01: switch on the parsed type,
    // never reinterpret text; refinement receives/returns text only and can never gain
    // command authority). Sentence deletion has no variant: the editor authority cannot prove
    // an exact owned sentence span, so the spoken grammar does not offer it (S06).
    sealed interface PreparedFinalAction {
        data class Text(val text: String, val resolved: ResolvedUtteranceLanguage) : PreparedFinalAction
        data class DeleteLast(val token: UtteranceToken) : PreparedFinalAction

        companion object {
            /**
             * Zero-mutation text for rejected parses: commits nothing (consumed via
             * `isBlank` below). A named constant — never a display string — so the
             * locale gate does not mistake it for a hard-coded UI label.
             */
            const val REJECTED_PARSE_TEXT = ""
        }
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
        val live: LiveRemoteUtterance? = null,
        /**
         * P02: VAD speech-episode flag snapshotted at endpoint/freeze time, while the Vad
         * still holds this utterance's state (the finalization actor runs later, after a
         * possible reset). Session-latched hasSpeechEver: true means speech occurred during
         * this capture session. Default false = legacy behavior (UNKNOWN basis).
         */
        val hadSpeech: Boolean = false,
        /**
         * P05 §5.3: non-null when this endpoint is a cap split. The overlap
         * audio is carried into the next utterance seed; at commit, the text
         * reconciles against the previous split commit (verified dedupe only).
         */
        val splitOverlap: com.sprich.app.core.audio.ContinuousSpeechPolicy.AudioSpan? = null,
    )
    // Single authoritative long-lived finalization actor — exactly one consumer, FIFO, no worker start/stop race, genuinely bounded.
    // Bounded capacity 4 ensures PCM retention bounded (max ~4 utterances). Overload is explicit via rejected/suppressed counters, not silent loss.
    private val maxPendingQueueDepth = 4
    private val pendingChannel = Channel<PendingUtterance>(capacity = maxPendingQueueDepth)
    /**
     * P03 §6.2 admission + ordered-commit-lane authority. The serial
     * finalization actor below already commits FIFO; the queue adds the byte
     * ceiling (24 MiB retained PCM — the channel bounds count only), shed
     * accounting, lane-advance on drops, and finish/cancel session semantics.
     * Observed, never gating: drops advance the lane as failed so no text is
     * fabricated and the lane cannot stall behind an entry that will never
     * produce a result. All calls are try/catch-guarded: the queue refines
     * telemetry, it must never break finalization.
     */
    private val utteranceQueue = UtteranceQueue()
    private val queueDepth = AtomicInteger(0)
    private val pendingQueuePeak = AtomicLong(0)
    // P07: per-utterance attempt telemetry — ids and timing only, never transcripts.
    private val utteranceTelemetry = UtteranceTelemetry()
    /** P07 §6.4: once-per-(utterance, type) guard for repeatable emissions. */
    private val telemetryStageGuard = com.sprich.app.diagnostics.TelemetryStageGuard()
    private val telemetrySeq = AtomicLong(0)
    /** Records telemetry; observability must never break dictation. */
    private fun tel(utteranceId: Long, type: TelemetryEventType, outcome: UtteranceOutcome? = null, code: Int = 0) {
        try {
            utteranceTelemetry.record(utteranceId, telemetrySeq.incrementAndGet(), type, outcome, 0, code)
            // P07 §6.4 trace verification: DEBUG-only per-event line so a
            // device run can prove boundary coverage from logcat.
            if (com.sprich.app.BuildConfig.DEBUG) {
                Log.d(
                    "SprichIME",
                    "tel utt=$utteranceId $type" +
                        (if (outcome != null) " outcome=$outcome" else "") +
                        (if (code != 0) " code=$code" else ""),
                )
            }
        } catch (_: Exception) { }
    }
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
    @Volatile private var isPasswordField: Boolean = false
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
    private val sharedHttpClient get() = ApiHttp.client

    private var transcriptionCoordinator: TranscriptionCoordinator? = null
    private var deletionGesture: EditorActionController.DeletionGesture? = null
    private var gesturePreview = BarGestureRecognizer.Preview.NONE
    private var statusBeforeGesture: CharSequence? = null
    private var hintBeforeGesture: CharSequence? = null
    private var pillViewRef: VoiceGestureBar? = null
    private var whisperBadge: TextView? = null
    private var undoButton: View? = null
    /**
     * U03 recovery (plan §9.6). [lastFailedCommit] keeps recognized-but-never-
     * committed text for explicit same-field retry; the app-scoped
     * [SprichApp.recoveryDrafts] store backs the Home review/discard card.
     * Both clear on screen lock; the store additionally TTL-expires (120 s).
     */
    private var lastFailedCommit: FailedCommit? = null
    private var retryButton: View? = null
    private var screenOffReceiver: android.content.BroadcastReceiver? = null
    /**
     * U02 correction sheet (§9.5). [correctionTarget] is the exact-span target
     * captured at sheet open; [correctionUndo] the one-step undo proof after
     * Apply; [correctionDictationTarget] arms dictated-replacement routing
     * (next post-arm utterance delivers its text to the sheet instead of the
     * field). The controller is Compose-observed state owned here.
     */
    private val correctionController = com.sprich.app.ui.correction.CorrectionSheetController()
    private var correctionTarget: com.sprich.app.input.correction.CorrectionTarget? = null
    private var correctionUndo: com.sprich.app.input.correction.UndoEdit? = null
    private var correctionDictationTarget: com.sprich.app.input.correction.CorrectionTarget? = null
    private var correctionArmedAtNanos: Long = 0L
    private var correctionDialog: android.app.Dialog? = null
    private var correctButton: View? = null
    private var lastAppliedReplacement: String? = null
    /**
     * Compose has no Activity here (IME service + Dialog window), so the
     * sheet's ComposeView gets explicit view-tree owners driven by this
     * registry (RESUMED while shown, DESTROYED on dismiss).
     */
    private var correctionSheetView: com.sprich.app.ui.correction.CorrectionSheetView? = null
    private var dismissalGeneration = 0L
    private var dismissing = false
    private var whisperChangeJob: Job? = null
    @Volatile private var captureWhisperMode = false
    private val whisperMode get() = runtimeConfig?.whisperMode == true
    // Liquid visual state driven by mic RMS
    @Volatile private var lastRms = 0f
    private var lastVisualUpdateElapsed = 0L
    private var liquidBg: android.graphics.drawable.GradientDrawable? = null
    private var glowView: View? = null
    private var glowBg: android.graphics.drawable.GradientDrawable? = null
    private var auraView: View? = null
    private var pillBgRef: android.graphics.drawable.GradientDrawable? = null
    // ViewRootImpl rejects accessibility content changes off the UI thread
    // (error today, fatal on future Android) — audio callbacks must hop here.
    private val mainThreadHandler = android.os.Handler(android.os.Looper.getMainLooper())
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
    private var resultNotice: Int? = null
    private val liveUtterances = java.util.concurrent.ConcurrentHashMap<Long, LiveRemoteUtterance>()
    private var remotePreview: RemoteTranscriptUpdate? = null
    private var remotePreviewId: Long? = null
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
            // U03: screen lock clears recovery state (store contract) + hides retry.
            try {
                screenOffReceiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                        try {
                            (application as? SprichApp)?.let { it.recoveryDrafts.onLock(); it.noteRecoveryChanged() }
                        } catch (_: Exception) {}
                        lastFailedCommit = null
                        try { retryButton?.visibility = View.GONE } catch (_: Exception) {}
                    }
                }
                androidx.core.content.ContextCompat.registerReceiver(
                    this,
                    screenOffReceiver,
                    android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            } catch (_: Exception) {
                screenOffReceiver = null
            }
            vad = Vad()
            speechPresence = com.sprich.app.core.vad.SpeechPresence(assets)
            engine = CanaryEngine(this, com.sprich.app.models.manager.ModelManager(this))
            // Neutral collector requires no engine ownership; coordinator will be created after engines lazy init
            scope.launch { try { vocabRepo.load() } catch (_: Exception) {} }

            // Single atomic runtime config — one DataStore emission → one immutable snapshot, no combine of async fields
            scope.launch {
                try {
                    prefs.runtimeConfigSnapshot.collect { snap ->
                        val profileChanged = runtimeConfig?.let { it.whisperMode != snap.whisperMode } == true
                        val resume = profileChanged && isDictationRunning() && isInputViewShown
                        runtimeConfig = snap
                        runtimeConfigFlow.value = snap
                        if (profileChanged) {
                            if (isDictationRunning()) stopDictation(StopReason.CURSOR_MOVED)
                            refreshSessionUi()
                            if (resume) startJob = scope.launch { startDictationIfNeeded() }
                        }
                        Log.i("SprichIME", "runtimeConfig snapshot $snap")
                    }
                } catch (e: Exception) { Log.w("SprichIME", "runtimeConfig collect fail", e) }
            }
            scope.launch {
                ApiHttp.permissionEpoch.drop(1).collect { stopDictation(StopReason.CURSOR_MOVED) }
            }
            // One-time legacy credential migration (P0-3 fail-closed)
            scope.launch { try { com.sprich.app.storage.LegacyApiCredentialMigrator.migrateIfNeeded(prefs, ApiSecretStore(this@SprichIME)) } catch (e: Exception) { Log.w("SprichIME", "legacy migration failed", e) } }
            // Start single long-lived finalization actor (no lost-wakeup race)
            startFinalizationActor()

            // Observe session for UI — update dot/text without Compose
            scope.launch {
                session.state.collect { refreshSessionUi() }
            }
        } catch (e: Exception) {
            Log.e("SprichIME", "onCreate failed", e)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        localizedResourceKey = null
        localizedResources = null
        statusText?.post {
            if (isInputViewShown) refreshLocalizedMessage()
        }
    }

    private fun isDark(): Boolean = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    // The compact voice bar leaves room for the real editor, including in landscape.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        cancelBarGesture()
        return try {
            val dark = isDark()
            val imeBackground = Color.parseColor(if (dark) "#151315" else "#FFF8F3")
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
                    val navBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars() or androidx.core.view.WindowInsetsCompat.Type.captionBar()).bottom
                    val bottomInset = navBottom
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottomInset + dp(12))
                    insets
                }
                setPadding(0, dp(8), 0, dp(12))
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) { androidx.core.view.ViewCompat.requestApplyInsets(v) }
                    override fun onViewDetachedFromWindow(v: View) { stopVisualLane() }
                })
            }

            // Liquid pill — smaller, centered, away from corners, prevents cut-off in small apps
            val pillBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                orientation = GradientDrawable.Orientation.TL_BR
                colors = (if (dark) listOf("#392832", "#2B2531", "#242B35") else listOf("#FFF1E6", "#FBE4EC", "#E9EFFF")).map(Color::parseColor).toIntArray()
                setStroke(dp(1), if (dark) Color.parseColor("#6A4E5C") else Color.parseColor("#E9C9D5"))
            }
            val pill = VoiceGestureBar(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = pillBg
                elevation = dp(4).toFloat()
                minimumHeight = dp(76)
                setPadding(dp(16), dp(14), dp(8), dp(14))
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

            // One large voice action and one separate, discoverable keyboard switch.
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
                text = uiString(com.sprich.app.R.string.ime_start)
                setTextColor(statusColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
                isSingleLine = false
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val hint = TextView(this).apply {
                text = uiString(com.sprich.app.R.string.ime_idle_hint)
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
            whisperBadge = TextView(this).apply {
                text = uiString(com.sprich.app.R.string.whisper_badge)
                setTextColor(if (dark) Color.parseColor("#B6E5DA") else Color.parseColor("#245C51"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), dp(3))
                visibility = if (whisperMode) View.VISIBLE else View.GONE
            }
            textBlock.addView(whisperBadge)
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

            pillViewRef = pill
            pill.onBeginDeletion = { beginGestureDeletion() }
            pill.onAction = { performBarAction(it) }
            pill.onPreview = { showGesturePreview(it) }
            pill.onFinishGesture = { deletionGesture?.cancel(); deletionGesture = null }
            pill.setOnLongClickListener { openSprichSettings(); true }

            // TalkBack receives the current state and one state-correct click action.
            androidx.core.view.ViewCompat.setAccessibilityDelegate(pill, object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Button"
                    info.contentDescription = (if (whisperMode) uiString(com.sprich.app.R.string.whisper_mode) + ". " else "") + "${status.text}. ${hint.text}"
                    info.removeAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
                    if (!isPasswordField) {
                        info.addAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
                            uiString(when { tapCancelsWork() -> com.sprich.app.R.string.cancel; isDictationRunning() -> com.sprich.app.R.string.ime_stop_action; else -> com.sprich.app.R.string.ime_start_action })))
                        info.addAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat(com.sprich.app.R.id.action_delete_word, uiString(com.sprich.app.R.string.ime_delete_unit_preview)))
                        info.addAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat(com.sprich.app.R.id.action_delete_phrase, uiString(com.sprich.app.R.string.ime_delete_sentence_preview)))
                        info.addAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat(com.sprich.app.R.id.action_whisper_mode, uiString(if (whisperMode) com.sprich.app.R.string.ime_whisper_off_action else com.sprich.app.R.string.ime_whisper_on_action)))
                        if (editorActionController.historySize() > 0) info.addAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat(com.sprich.app.R.id.action_undo_delete, uiString(com.sprich.app.R.string.ime_undo_action)))
                    }
                    info.addAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat(com.sprich.app.R.id.action_switch_keyboard, uiString(com.sprich.app.R.string.switch_keyboard)))
                    info.addAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat(com.sprich.app.R.id.action_hide_keyboard, uiString(com.sprich.app.R.string.ime_hide_action)))
                    info.addAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat(com.sprich.app.R.id.action_open_settings, uiString(com.sprich.app.R.string.ime_settings_action)))
                }
                override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean = when (action) {
                    com.sprich.app.R.id.action_delete_word -> deleteOnce(EditorActionController.Unit.WORD_OR_SYMBOL)
                    com.sprich.app.R.id.action_delete_phrase -> deleteOnce(EditorActionController.Unit.PHRASE)
                    com.sprich.app.R.id.action_undo_delete -> undoLastDelete()
                    com.sprich.app.R.id.action_whisper_mode -> toggleWhisperMode()
                    com.sprich.app.R.id.action_switch_keyboard -> switchToTypingKeyboard()
                    com.sprich.app.R.id.action_hide_keyboard -> { dismissKeyboard(); true }
                    com.sprich.app.R.id.action_open_settings -> { openSprichSettings(); true }
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
                contentDescription = uiString(com.sprich.app.R.string.switch_keyboard)
                setOnClickListener { switchToTypingKeyboard() }
            }
            val undo = android.widget.ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setImageResource(com.sprich.app.R.drawable.ic_undo)
                imageTintList = android.content.res.ColorStateList.valueOf(statusColor)
                val backgroundAttr = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, backgroundAttr, true)
                setBackgroundResource(backgroundAttr.resourceId)
                contentDescription = uiString(com.sprich.app.R.string.ime_undo_action)
                visibility = View.GONE
                setOnClickListener { undoLastDelete() }
            }
            undoButton = undo
            // U03: retry-insert for recognized-but-never-committed text. Hidden
            // unless a same-field retry is eligible; joins the bar tap targets
            // so touch exploration and taps reach it like undo/switch.
            val retry = android.widget.ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setImageResource(com.sprich.app.R.drawable.ic_retry)
                imageTintList = android.content.res.ColorStateList.valueOf(statusColor)
                val backgroundAttr = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, backgroundAttr, true)
                setBackgroundResource(backgroundAttr.resourceId)
                contentDescription = uiString(com.sprich.app.R.string.ime_retry_insert)
                visibility = View.GONE
                setOnClickListener { retryFailedCommit() }
            }
            retryButton = retry
            // U02: cursor-word correction entry. Visible only when a correctable
            // word sits under the cursor (never in password fields).
            val correct = android.widget.ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setImageResource(com.sprich.app.R.drawable.ic_correct)
                imageTintList = android.content.res.ColorStateList.valueOf(statusColor)
                val backgroundAttr = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, backgroundAttr, true)
                setBackgroundResource(backgroundAttr.resourceId)
                contentDescription = uiString(com.sprich.app.R.string.ime_correct_word)
                visibility = View.GONE
                setOnClickListener { openCorrectionSheet() }
            }
            correctButton = correct
            pill.tapTargets = listOf(undo, switchButton, retry, correct)

            pillBgRef = pillBg
            // Allow glow/aura to draw beyond bounds
            outer.clipChildren = false
            outer.clipToPadding = false
            pill.clipChildren = false
            pill.clipToPadding = false
            textBlock.clipChildren = false
            textBlock.clipToPadding = false
            pill.addView(textBlock)
            pill.addView(undo)
            pill.addView(retry)
            pill.addView(correct)
            pill.addView(switchButton)
            outer.addView(aura)   // behind the pill
            outer.addView(pill)

            // Keep view references for state and microphone feedback.
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
                text = uiString(com.sprich.app.R.string.ime_fallback)
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

    private fun tokenOwnsCurrentField(token: UtteranceToken): Boolean = token.generation == sessionGeneration.get() &&
        token.fieldGeneration == fieldGeneration.get() && token.fieldId == currentFieldId && !isPasswordField && session.requireActive()

    private fun openLiveUtterance(token: UtteranceToken, plan: UtterancePlan): LiveRemoteUtterance? {
        val remote = (plan.transcription as? TranscriptionPlan.ApiPrimary)?.remote ?: return null
        if (!remote.supportsStreaming || !remote.preferStreaming || !tokenOwnsCurrentField(token)) return null
        return LiveRemoteUtterance(scope, remote, plan.apiPermissionEpoch, token.utteranceId, plan.remoteVocabularyHints,
            credential = { apiSecretStore.loadBoundSecret(remote.credentialRef, remote.providerId, remote.endpoint).orEmpty() },
            isCurrent = { tokenOwnsCurrentField(token) },
            onProgress = { updateRemoteProgress(token, it) },
        ).also { liveUtterances[token.utteranceId] = it }
    }

    private fun updateRemoteProgress(token: UtteranceToken, progress: RemoteTranscriptUpdate) {
        // P07 §6.4: utterance-scoped remote boundaries, once each. Recorded
        // regardless of field ownership — the provider event happened for
        // this utterance even if the field moved on.
        try {
            val id = token.utteranceId
            if (progress.stage == VoiceApiStage.CONNECTING &&
                telemetryStageGuard.shouldEmit(id, TelemetryEventType.REMOTE_CONNECT)
            ) {
                tel(id, TelemetryEventType.REMOTE_CONNECT)
            }
            if (progress.preview.isNotBlank() &&
                telemetryStageGuard.shouldEmit(id, TelemetryEventType.FIRST_PREVIEW)
            ) {
                tel(id, TelemetryEventType.FIRST_PREVIEW)
            }
            val backlog = progress.totalUploadBytes - progress.uploadedBytes
            if (progress.stage == VoiceApiStage.UPLOADING && backlog > 0 &&
                telemetryStageGuard.shouldEmit(id, TelemetryEventType.UPLOAD_BACKLOG)
            ) {
                tel(id, TelemetryEventType.UPLOAD_BACKLOG)
            }
            if (progress.stage == VoiceApiStage.COMPLETE &&
                telemetryStageGuard.shouldEmit(id, TelemetryEventType.PROVIDER_FINAL)
            ) {
                tel(id, TelemetryEventType.PROVIDER_FINAL)
            }
        } catch (_: Exception) {}
        scope.launch(Dispatchers.Main) {
            if (tokenOwnsCurrentField(token) && currentUtteranceToken?.utteranceId == token.utteranceId) {
                remotePreview = progress; remotePreviewId = token.utteranceId
                renderRemoteProgress()
            }
        }
    }

    private fun renderRemoteProgress() {
        if (gesturePreview != BarGestureRecognizer.Preview.NONE) return
        val progress = remotePreview ?: return
        if (remotePreviewId != currentUtteranceToken?.utteranceId || isPasswordField) return
        staticMessageIds = null
        val strings = uiResources()
        fun text(id: Int, vararg arguments: Any): String = strings.getString(id, *arguments)
        val hint = statusText?.tag as? TextView
        if (progress.preview.isBlank() && queueDepth.get() > 0 && activeUtterance?.token?.utteranceId == remotePreviewId && progress.stage != VoiceApiStage.FAILED) {
            statusText?.text = text(com.sprich.app.R.string.ime_transcribing)
            hint?.text = text(if (tapCancelsWork()) com.sprich.app.R.string.ime_writing_hint else com.sprich.app.R.string.ime_keep_speaking)
            return
        }
        if (progress.preview.isNotBlank()) {
            // Show the latest words without modifying the editor or saving a transcript.
            statusText?.let { label ->
                val preview = progress.preview.replace(Regex("\\s+"), " ")
                val available = (label.width - label.totalPaddingLeft - label.totalPaddingRight).coerceAtLeast(dp(120))
                label.text = android.text.TextUtils.ellipsize(preview, label.paint, available * 1.7f, android.text.TextUtils.TruncateAt.START)
                label.maxLines = 2
                label.ellipsize = android.text.TextUtils.TruncateAt.END
            }
        } else statusText?.text = text(when (progress.stage) {
            VoiceApiStage.CONNECTING -> com.sprich.app.R.string.ime_api_connecting
            VoiceApiStage.UPLOADING -> com.sprich.app.R.string.ime_api_uploading
            VoiceApiStage.PROCESSING, VoiceApiStage.FINISHING -> com.sprich.app.R.string.ime_transcribing
            VoiceApiStage.FAILED -> com.sprich.app.R.string.ime_api_interrupted
            else -> com.sprich.app.R.string.ime_listening
        })
        hint?.text = when {
            progress.stage == VoiceApiStage.FAILED -> text(com.sprich.app.R.string.ime_api_interrupted_hint)
            progress.stage == VoiceApiStage.UPLOADING && progress.totalUploadBytes > 0 -> text(com.sprich.app.R.string.ime_api_upload_percent,
                (progress.uploadedBytes * 100 / progress.totalUploadBytes).coerceIn(0, 100).toInt())
            progress.processedMs > 0 -> text(com.sprich.app.R.string.ime_api_progress,
                java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(progress.processedMs / 1000.0)) +
                progress.speaker?.let { " · " + text(com.sprich.app.R.string.ime_api_speaker, it) }.orEmpty()
            else -> text(com.sprich.app.R.string.ime_api_live_hint)
        }
        if (progress.stage != VoiceApiStage.FAILED && progress.stage != VoiceApiStage.UPLOADING) {
            hint?.append(" · " + text(if (tapCancelsWork())
                com.sprich.app.R.string.ime_writing_hint else com.sprich.app.R.string.ime_stop_hint))
        }
    }

    private fun cancelLiveUtterances() {
        liveUtterances.values.forEach { it.cancel() }; liveUtterances.clear()
        remotePreview = null; remotePreviewId = null
    }

    private fun refreshSessionUi() {
        if (!::session.isInitialized || dismissing || gesturePreview != BarGestureRecognizer.Preview.NONE) return
        val state = session.state.value
        if (state is SessionState.Error) return
        updateImeUi(isDictationRunning())
        // Affordances (undo/retry/correction) follow session + editor state.
        try { updateEditingControls() } catch (_: Exception) {}
        if (state is SessionState.Preparing) {
            showStaticMessage(com.sprich.app.R.string.ime_preparing, com.sprich.app.R.string.ime_loading_hint)
        } else if (state is SessionState.Finalizing && !utteranceActive.get()) {
            showStaticMessage(com.sprich.app.R.string.ime_transcribing, com.sprich.app.R.string.ime_writing_hint)
        }
    }

    private fun updateImeUi(isListening: Boolean) {
        if (dismissing) return
        updateEditingControls()
        if (gesturePreview != BarGestureRecognizer.Preview.NONE) return
        try {
            val strings = uiResources()
            val dark = isDark()
            statusText?.maxLines = 2
            statusText?.ellipsize = android.text.TextUtils.TruncateAt.END
            val statusColor = if (dark) Color.parseColor("#F5F5F3") else Color.parseColor("#111111")
            // Hint reflects active pipeline: API vs Local — adds quiet trust signal without clutter
            val apiHint = isApiPalette()
            if (isPasswordField) {
                stopVisualLane()
                showStaticMessage(com.sprich.app.R.string.ime_password, com.sprich.app.R.string.ime_password_hint, strings)
                waveform?.visibility = View.INVISIBLE
                micContainer?.contentDescription = strings.getString(com.sprich.app.R.string.ime_password)
                return
            }
            if (isListening) {
                showStaticMessage(
                    if (whisperMode) com.sprich.app.R.string.ime_whisper_listening else com.sprich.app.R.string.ime_listening,
                    if (apiHint) com.sprich.app.R.string.ime_cloud_listening else com.sprich.app.R.string.ime_stop_hint,
                    strings,
                )
                statusText?.setTextColor(statusColor)
                micContainer?.contentDescription = strings.getString(if (apiHint) com.sprich.app.R.string.ime_cloud_stop else com.sprich.app.R.string.ime_stop_action)
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
                showStaticMessage(
                    if (whisperMode) com.sprich.app.R.string.ime_whisper_start else com.sprich.app.R.string.ime_start,
                    when { apiHint -> com.sprich.app.R.string.ime_api_idle_hint; runtimeConfig?.refinementMode != null && runtimeConfig?.refinementMode != RefinementMode.OFF -> com.sprich.app.R.string.ime_cleanup_idle_hint; else -> com.sprich.app.R.string.ime_idle_hint },
                    strings,
                )
                statusText?.setTextColor(statusColor)
                micContainer?.contentDescription = strings.getString(com.sprich.app.R.string.ime_start)
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
                pillBgRef?.setStroke(dp(1), if (dark) Color.parseColor("#6A4E5C") else Color.parseColor("#E9C9D5"))
            }
            if (session.state.value !is SessionState.Preparing && session.state.value !is SessionState.Finalizing) {
                resultNotice?.let {
                    staticMessageIds = null
                    (statusText?.tag as? TextView)?.text = strings.getString(it)
                }
            }
            renderRemoteProgress()
        } catch (_: Exception) {}
    }

    private fun startVisualLane() {
        if (visualRunning || !isInputViewShown || !android.animation.ValueAnimator.areAnimatorsEnabled()) return
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


    @Volatile private var editorAuthority: EditorSnapshot? = null
    @Volatile private var fieldAllowsContext = false
    private val ownedSelections = ArrayDeque<Pair<Int, Int>>()

    private fun editorStillOwned(): Boolean = editorAuthority != null && EditorSnapshot.read(currentInputConnection) == editorAuthority

    private fun noteOwnEditorChange(success: Boolean) {
        if (!success) { cancelForEditorChange(); return }
        editorAuthority = EditorSnapshot.read(currentInputConnection)
        editorAuthority?.let { ownedSelections.addLast(it.selectionStart to it.selectionEnd) }
        while (ownedSelections.size > 16) ownedSelections.removeFirst()
    }

    private fun cancelForEditorChange() {
        cancelBarGesture()
        stopDictation(StopReason.CURSOR_MOVED)
        editorActionController.clearHistory()
        editorActionController.clearSprichInsertion()
        ownedSelections.clear()
        editorAuthority = null
        updateEditingControls()
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
            cancelBarGesture()
            resetBarMotion()
            resultNotice = null
            stopDictation(if (restarting) StopReason.INPUT_RESTARTED else StopReason.FIELD_LOST)
            val newFieldId = "field_${fieldGeneration.incrementAndGet()}"
            currentFieldId = newFieldId
            // Focus grants editor authority; it does not start capture or enter Preparing.
            // The capture session is created by startDictationIfNeeded after a tap/instant start.
            editorActionController.clearHistory()
            editorActionController.clearSprichInsertion()
            ownedSelections.clear()
            fieldAllowsContext = info != null && !isPassword(info) && (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
            editorAuthority = if (isPassword(info)) null else EditorSnapshot.read(currentInputConnection)
            currentFieldTokenIcHash = currentInputConnection?.hashCode() ?: 0
            isPasswordField = isPassword(info)
            // Password early exit must clear deletion/undo history, cancel gesture state, discard preview, invalidate editor ownership BEFORE return
            if (isPasswordField) {
                try { editorActionController.onPasswordFieldFocused() } catch (_: Exception) {}
                try { cancelBarGesture() } catch (_: Exception) {}
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
            // New field: refresh affordances (correction entry needs the fresh IC).
            try { updateEditingControls() } catch (_: Exception) {}

        } catch (e: Exception) {
            Log.e("SprichIME", "onStartInput failed", e)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        cancelBarGesture()
        resetBarMotion()
        updateImeUi(isDictationRunning())
        // Per-app locale propagation can trail focus by a frame for a resident IME service.
        // Re-resolve once after the system has delivered the new application configuration.
        statusText?.postDelayed({
            if (isInputViewShown) refreshLocalizedMessage()
        }, 300L)
        if (isPassword(info)) return
        if (info?.packageName == packageName && info.privateImeOptions == com.sprich.app.ui.vocab.TYPED_SPELLING_IME_OPTION) {
            switchToTypingKeyboard()
            return
        }
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
        cancelBarGesture()
        resetBarMotion()
        try { stopVisualLane() } catch (_: Exception) {}
        try { stopDictation(StopReason.WINDOW_HIDDEN) } catch (_: Exception) {}
    }

    override fun onFinishInput() {
        try {
            cancelBarGesture()
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
        cancelBarGesture()
        // U02: the sheet is window-bound; never leak it past the input view.
        try { closeCorrectionSheet() } catch (_: Exception) {}
        stopDictation(StopReason.WINDOW_HIDDEN)
        try { stopVisualLane() } catch (_: Exception) {}
        super.onFinishInputView(finishingInput)
    }

    @Volatile private var cleanupScope: kotlinx.coroutines.CoroutineScope? = null

    override fun onDestroy() {
        cancelBarGesture()
        resetBarMotion()
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
        // P03: invalidate and discard all lane state.
        try { utteranceQueue.cancel() } catch (_: Exception) {}
        try {
            screenOffReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        screenOffReceiver = null
        try { closeCorrectionSheet() } catch (_: Exception) {}
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
            Log.i("SprichIME", "toggle running=${isDictationRunning()} sessionState=${session.state.value} utterActive=${utteranceActive.get()}")
            if (isDictationRunning()) {
                stopDictation(if (tapCancelsWork()) StopReason.CURSOR_MOVED else StopReason.USER_STOP)
            } else {
                startJob?.cancel()
                startJob = scope.launch { startDictationIfNeeded() }
            }
        } catch (e: Exception) { Log.e("SprichIME", "toggle failed", e) }
    }

    // A previous phrase can be finalizing while the next one is being captured. Tapping must
    // finish the current capture in that case, not unexpectedly cancel both phrases.
    private fun tapCancelsWork(): Boolean = session.state.value is SessionState.Preparing ||
        (session.state.value is SessionState.Finalizing && !utteranceActive.get())

    private fun isDictationRunning(): Boolean = when (session.state.value) {
        is SessionState.Preparing,
        is SessionState.Listening,
        is SessionState.Speech,
        is SessionState.Finalizing -> true
        else -> false
    }

    private suspend fun startDictationIfNeeded() {
        resultNotice = null
        try {
            if (!isInputViewShown) return
            // Gated by runtimeConfig — before first snapshot: no preload, no mic, no plan
            val snapAtStart = runtimeConfig
            if (snapAtStart == null) {
                Log.i("SprichIME", "startDictation gated: runtimeConfig not ready — Getting ready…")
                showStaticMessage(com.sprich.app.R.string.ime_preparing, com.sprich.app.R.string.ime_loading_settings)
                return
            }
            if (isPasswordField) {
                // Password field → zero capture/mutation, clear history already done in onStartInput, also ensure no mic
                showStaticMessage(com.sprich.app.R.string.ime_password, com.sprich.app.R.string.ime_password_hint)
                return
            }
            editorAuthority = EditorSnapshot.read(currentInputConnection)
            if (editorAuthority == null) {
                showStaticMessage(com.sprich.app.R.string.ime_no_cursor, com.sprich.app.R.string.ime_no_cursor_hint)
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
                    showStaticMessage(com.sprich.app.R.string.ime_setup, com.sprich.app.R.string.ime_setup_hint)
                    try { vibrateTick() } catch (_: Exception) {}
                    writeDiagnostics("blocked Auto (no LID) resolved=${activeConfig.resolvedLanguageTag()} speechLanguage=$speechLanguage")
                    return
                }
                if (!fastReady) {
                    Log.w("SprichIME", "Auto (winner FastConformer) requested but FastConformer 126M not downloaded — download required.")
                    try { session.error("auto not supported without FastConformer") } catch (_: Exception) {}
                    showStaticMessage(com.sprich.app.R.string.ime_setup, com.sprich.app.R.string.ime_setup_hint)
                    try { vibrateTick() } catch (_: Exception) {}
                    writeDiagnostics("blocked Auto (no FastConformer) speechLanguage=$speechLanguage")
                    return
                }
                Log.i("SprichIME", "Auto via Tiny LID per-utterance + FastConformer 126M (winner) — proceeding, LID will detect EN/DE/ES/FR, FastConformer will transcribe (RTF 0.038)")
            } else if (speechLanguage is SpeechLanguage.Auto && transcriptionMode == TranscriptionMode.API_PRIMARY) {
                Log.i("SprichIME", "API_PRIMARY with Auto — skipping local LID/FastConformer gate, provider handles language (Phase 4 independence)")
                // Local fallback will be loaded lazily on remote failure if available
            }
            if (session.state.value is SessionState.Listening || session.state.value is SessionState.Speech) {
                Log.i("SprichIME", "start skipped already ${session.state.value} gen=${sessionGeneration.get()}")
                return
            }
            val permissionGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.i("SprichIME", "permission RECORD_AUDIO granted=$permissionGranted inputType=${currentInputConnection?.let { try { currentInputInfo()?.inputType } catch (_: Exception) { -1 } } ?: -1}")
            if (!permissionGranted) {
                Log.w("SprichIME", "RECORD_AUDIO not granted, abort dictation")
                try { session.error("mic permission") } catch (_: Exception) {}
                showStaticMessage(com.sprich.app.R.string.ime_mic_permission, com.sprich.app.R.string.ime_mic_permission_hint)
                // Vibrate error
                try { vibrateTick() } catch (_: Exception) {}
                return
            }
            val generation = sessionGeneration.incrementAndGet()
            // P03: new capture session reopens lane intake (finish/cancel closed it).
            try { utteranceQueue.reset() } catch (_: Exception) {}
            // P05 §5.3: fresh session, fresh split state and session cap.
            try { clearSplitContinuationState() } catch (_: Exception) {}
            sessionHardStopFired.set(false)
            // The retired reader must finish before we reset shared endpoint/PCM state.
            val previousReaderStopped = withContext(Dispatchers.IO) { audio.requestStop()?.awaitStop(2000) != false }
            if (generation != sessionGeneration.get()) return
            if (!previousReaderStopped) {
                failSession(generation, "microphone shutdown timed out", com.sprich.app.R.string.ime_mic_unavailable, com.sprich.app.R.string.ime_retry, null)
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
            showStaticMessage(com.sprich.app.R.string.ime_loading, com.sprich.app.R.string.ime_first_start)

            // Route-aware loading — only load required engines, do NOT load Canary for Automatic. For API_PRIMARY, local not required.
            val routeForSession = determineRoute(snapAtStart.speechLanguage)
            val requiresLocalLoad = snapAtStart.transcriptionMode != TranscriptionMode.API_PRIMARY
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
                    com.sprich.app.R.string.ime_model_failed,
                    com.sprich.app.R.string.ime_model_failed_hint,
                    loadResult.exceptionOrNull(),
                )
                return
            }
            if (requiresLocalLoad) speechPresence.prepare(snapAtStart.whisperMode)
            if (generation != sessionGeneration.get()) return
            // Ensure coordinator exists
            if (localCoordinator == null) localCoordinator = LocalTranscriptionCoordinator(lidEngine, fastConformerEngine, engine)
            // Housekeeping: unload unused resident engines if queue drained
            maybeUnloadUnused(routeForSession)

            latency.mark("audioStartRequested")
            lastUtteranceEndSample = 0L
            composition.reset()
            captureWhisperMode = snapAtStart.whisperMode
            vad.configureWhisperMode(captureWhisperMode)
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
                    whisperMode = captureWhisperMode,
                    onChunkWithOffset = { samples, offset, length, timestampNanos, rms ->
                        handleAudioChunk(generation, samples, offset, length, timestampNanos, rms)
                    },
                    onFailure = { reason ->
                        scope.launch {
                            failSession(
                                generation,
                                reason,
                                com.sprich.app.R.string.ime_mic_stopped,
                                com.sprich.app.R.string.ime_retry,
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
                    com.sprich.app.R.string.ime_mic_unavailable,
                    com.sprich.app.R.string.ime_mic_busy_hint,
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
            failSession(generation, "start failed", com.sprich.app.R.string.ime_start_failed, com.sprich.app.R.string.ime_retry, t)
        }
    }

    // Hot-path: single RMS, zero extra PCM allocation (reuses readBuf), offset/length ownership
    private fun handleAudioChunk(generation: Long, samples: ShortArray, offset: Int, length: Int, timestampNanos: Long, precomputedRms: Float) {
        if (generation != sessionGeneration.get() || !session.requireActive()) { staleCallbackDrops++; return }
        // A changed profile is applied by restarting capture, never to the old prebuffer.
        if (runtimeConfig?.whisperMode != captureWhisperMode) return
        try {
            pipelineChunkCount++
            pipelineSampleCount += length
            // P05 §5.3: 120 s session safety ceiling — SESSION_LIMIT reuses
            // the USER_STOP drain path so all captured text is preserved.
            // Fires once per capture session; utterance caps bound segments.
            if (!sessionHardStopFired.get()) {
                try {
                    val sessionSamples = pipelineSampleCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    if (com.sprich.app.core.audio.ContinuousSpeechPolicy.sessionLimitReached(sessionSamples) &&
                        sessionHardStopFired.compareAndSet(false, true)
                    ) {
                        Log.i("SprichIME", "session hard cap reached samples=$pipelineSampleCount — stopping with drain")
                        scope.launch { stopDictation(StopReason.SESSION_LIMIT) }
                    }
                } catch (_: Exception) {}
            }
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
            }
            // P05 §5.3: remember the latest silence position (utterance-domain
            // samples) while an utterance is active — a future soft-cap split
            // prefers endpointing here over chopping mid-word.
            if (utteranceActive.get() &&
                (result.state == Vad.State.SILENCE || result.state == Vad.State.LONG_SILENCE)
            ) {
                try { lastVadSilenceSample = utteranceAudio.size() } catch (_: Exception) {}
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
                val buffered = audio.snapshotPrebufferMs(PRE_ROLL_MS)
                val newSamples = (pipelineSampleCount - lastUtteranceEndSample).coerceIn(0L, buffered.size.toLong()).toInt()
                val preRoll = if (newSamples == buffered.size) buffered else buffered.copyOfRange(buffered.size - newSamples, buffered.size)
                pipelinePushedSampleCount += preRoll.size
                // Gated: if runtimeConfig not yet ready, discard onset (Getting ready… state)
                if (runtimeConfig == null) {
                    Log.w("SprichIME", "utterance onset discarded: runtimeConfig not ready")
                    utteranceActive.set(false)
                    return
                }
                // AUTHORITATIVE: UtteranceAudioCollector owns seeding preRoll exactly once — engine-independent.
                // P05 §5.3: a split continuation prepends the previous split's
                // overlap tail so no audio is lost at the split boundary.
                lastVadSilenceSample = -1
                val seed = try {
                    val tail = pendingSplitSeed
                    pendingSplitSeed = null
                    if (tail != null && tail.isNotEmpty()) {
                        Log.i("SprichIME", "split continuation seed overlapSamples=${tail.size} preRoll=${preRoll.size}")
                        tail + preRoll
                    } else preRoll
                } catch (_: Exception) { preRoll }
                try { utteranceAudio.begin(seed) } catch (_: Exception) {}
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
                val live = openLiveUtterance(token, planAtOnset)
                activeUtterance = ActiveUtterance(token, routeAtOnset, planAtOnset.speechConfig, planAtOnset, live)
                live?.offerAudio(preRoll)
                Log.i("SprichIME", "utterance onset id=${token.utteranceId} preRollSamples=${preRoll.size}")
                // P07 §6.4: utterance identity starts here — onset + first audio.
                try {
                    tel(token.utteranceId, TelemetryEventType.SPEECH_ONSET)
                    tel(token.utteranceId, TelemetryEventType.FIRST_PCM)
                } catch (_: Exception) {}
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
                (activeUtterance?.live != null || result.state == Vad.State.SPEECH || result.state == Vad.State.HESITATION)
            ) {
                pipelinePushedSampleCount += length
                // AUTHORITATIVE: collector is single owner — single copy via offset/length, zero extra allocation
                try { utteranceAudio.append(samples, offset, length) } catch (_: Exception) {}
                activeUtterance?.live?.offerAudio(samples, offset, length)

            }

            val liveEndpoint = activeUtterance?.live?.shouldFinish(result.state == Vad.State.SPEECH, result.state == Vad.State.UTTERANCE_END, durationMs)
                ?: (result.state == Vad.State.UTTERANCE_END)
            // P05 §5.3: soft-cap split prefers a safe pause over chopping
            // mid-word. Only when a VAD silence within the overlap window
            // exists; the hard ceiling below stays the emergency mechanism.
            val audioSizeNow = try { utteranceAudio.size() } catch (_: Exception) { 0 }
            val softPauseSplit = if (!remoteOnly && audioSizeNow >= com.sprich.app.core.audio.ContinuousSpeechPolicy.SEGMENT_SOFT_CAP_SAMPLES) {
                try {
                    val silence = lastVadSilenceSample.takeIf { it >= 0 }
                    com.sprich.app.core.audio.ContinuousSpeechPolicy.planSegmentSplit(audioSizeNow, silence)
                        ?.takeIf { it.atSafePause }
                } catch (_: Exception) { null }
            } else null
            if (
                (liveEndpoint || utteranceAudio.size() >= 16000 * (if (remoteOnly) 119 else 29) || softPauseSplit != null) &&
                utteranceActive.compareAndSet(true, false)
            ) {
                latency.mark("endpointDetected")
                // Mark pending for observability; do not block next onset — queue handles continuous speech.
                endpointPending.set(true)
                // Freeze authoritative collector snapshot — immutable, engine-independent.
                // CRITICAL: captured synchronously before next onset can reuse live buffer.
                val frozenSnap: ShortArray = try { utteranceAudio.freeze() } catch (_: Exception) { ShortArray(0) }
                lastUtteranceEndSample = pipelineSampleCount
                // P05 §5.3: cap splits carry the overlap tail into the next
                // utterance seed so no audio is lost at the boundary. Exact
                // plan from the frozen size (soft pause preferred, hard cap
                // forced) — null for ordinary endpoints.
                var splitOverlap: com.sprich.app.core.audio.ContinuousSpeechPolicy.AudioSpan? = null
                try {
                    if (!remoteOnly && frozenSnap.size >= com.sprich.app.core.audio.ContinuousSpeechPolicy.SEGMENT_SOFT_CAP_SAMPLES) {
                        val silence = lastVadSilenceSample.takeIf { it in 0..frozenSnap.size }
                        val plan = com.sprich.app.core.audio.ContinuousSpeechPolicy.planSegmentSplit(frozenSnap.size, silence)
                        if (plan != null) {
                            splitOverlap = plan.overlap
                            val from = plan.overlap.startSampleInclusive.coerceIn(0, frozenSnap.size)
                            val to = plan.overlap.endSampleExclusive.coerceIn(from, frozenSnap.size)
                            if (to > from) {
                                pendingSplitSeed = frozenSnap.copyOfRange(from, to)
                                Log.i("SprichIME", "split continuation overlapSamples=${to - from} atSafePause=${plan.atSafePause}")
                            }
                        }
                    }
                } catch (_: Exception) {
                    splitOverlap = null
                    try { pendingSplitSeed = null } catch (_: Exception) {}
                }
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
                    live = captured.live,
                    hadSpeech = try { vad.hasSpeech() } catch (_: Exception) { false },
                    splitOverlap = splitOverlap,
                )
                captured.live?.sealInput(frozenSnap)
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
                    com.sprich.app.R.string.ime_audio_error,
                    com.sprich.app.R.string.ime_retry,
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
                    coroutineScope {
                        val task = async(start = CoroutineStart.LAZY) { finalizePending(pending) }
                        endpointJob = task
                        try { task.start(); task.await() }
                        finally { if (endpointJob === task) endpointJob = null }
                    }
                } catch (e: CancellationException) {
                    Log.i("SprichIME", "finalization actor cancelled pending=$pending")
                    // Revoking an API cancels this utterance, not the long-lived queue consumer.
                    currentCoroutineContext().ensureActive()
                } catch (t: Throwable) {
                    Log.e("SprichIME", "finalizePending outer failed $pending", t)
                    // Utterance-scoped — do NOT destroy B
                    try { failUtteranceScoped(pending.token, "finalization outer failed ${pending.token}", t) } catch (_: Exception) {}
                } finally {
                    liveUtterances.remove(pending.token.utteranceId)?.cancel()
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
                        if (!stopRequested && session.requireActive() && session.state.value is SessionState.Finalizing) {
                            session.onListeningAgain()
                            if (utteranceActive.get()) session.onSpeechOnset()
                        }
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

    /**
     * Explicit newest-rejection shared by every admission authority (channel
     * count bound, utterance-queue count+byte bound). Bounded memory, counted.
     * Do not pretend speech was captured.
     */
    private fun rejectEnqueuedPending(pending: PendingUtterance, cause: String, depthBefore: Int) {
        finalizationQueueOverflows.incrementAndGet()
        catchingUpRejectedOnsets.incrementAndGet()
        if (!catchingUp) {
            catchingUp = true
            Log.w("SprichIME", "finalization queue at capacity depth=$depthBefore pending=${pending.token.utteranceId} cause=$cause peak=${pendingQueuePeak.get()} overflows=${finalizationQueueOverflows.get()} rejected=${catchingUpRejectedOnsets.get()} — entering CatchingUp, rejecting new utterance (bounded)")
            updateCatchUpUi(true)
        } else {
            Log.w("SprichIME", "queue at capacity depth=$depthBefore pending=${pending.token.utteranceId} cause=$cause overflows=${finalizationQueueOverflows.get()} rejected=${catchingUpRejectedOnsets.get()} — rejecting (bounded)")
            updateCatchUpUi(true)
        }
        liveUtterances.remove(pending.token.utteranceId)?.cancel()
        pending.pcm.fill(0)
        // P07 §6.4: retained audio released on bounded reject.
        try { tel(pending.token.utteranceId, TelemetryEventType.RELEASE) } catch (_: Exception) {}
    }

    private fun enqueuePending(pending: PendingUtterance) {
        val depthBefore = queueDepth.get()
        if (depthBefore >= maxPendingQueueDepth) {
            // Genuinely bounded: reject new frozen utterance rather than growing memory unbounded. Preserve already queued FIFO, reject newest, count explicitly.
            rejectEnqueuedPending(pending, "channel-capacity", depthBefore)
            return
        }
        // Proactive backpressure one before capacity: enter CatchingUp early to degrade partials and prevent new onset
        if (depthBefore >= maxPendingQueueDepth - 1 && !catchingUp) {
            catchingUp = true
            Log.w("SprichIME", "finalization queue near capacity depth=$depthBefore pending=${pending.token.utteranceId} — entering CatchingUp early, degrading partials")
            updateCatchUpUi(true)
        }
        // P03 §6.2: admit to the ordered commit lane (count AND 24 MiB byte
        // ceiling; the channel above bounds count only). Shed converts a
        // pending-cleanup entry to faithful-ready — the deterministic text the
        // actor commits when cleanup is unavailable, so shedding needs no
        // extra handling here beyond the record.
        val queueSeq = pending.token.utteranceId
        val admission = try {
            utteranceQueue.admit(
                QueuedUtterance(
                    seq = queueSeq,
                    utteranceId = queueSeq,
                    byteSize = pending.pcm.size * 2,
                    endpointMonoMs = pending.endpointTimestampNanos / 1_000_000L,
                ),
            )
        } catch (_: Exception) {
            QueueAdmission.Admitted()
        }
        when (admission) {
            is QueueAdmission.Rejected -> {
                rejectEnqueuedPending(pending, "utterance-queue-${admission.reason.name}", queueDepth.get())
                return
            }
            is QueueAdmission.Admitted -> if (admission.shedCleanupForSeq != null) {
                Log.i("SprichIME", "utterance-queue shed cleanup for seq=${admission.shedCleanupForSeq} to admit utt=$queueSeq (faithful-ready)")
            }
        }
        val newDepth = queueDepth.incrementAndGet()
        lastQueueDepth = newDepth
        if (newDepth.toLong() > pendingQueuePeak.get()) pendingQueuePeak.set(newDepth.toLong())
        Log.i("SprichIME", "enqueuePending token=${pending.token} pcm=${pending.pcm.size} queueDepth=$newDepth peak=${pendingQueuePeak.get()} reason=${pending.reason} catchingUp=$catchingUp")
        tel(pending.token.utteranceId, TelemetryEventType.QUEUE_ADMISSION)
        if (pending.reason == StopReason.ENDPOINT) tel(pending.token.utteranceId, TelemetryEventType.ENDPOINT)
        // Bounded channel (capacity=4) — trySend without suspension avoids unbounded PCM retention via parked coroutines
        val result = pendingChannel.trySend(pending)
        if (!result.isSuccess) {
            // Race where two endpoints concurrent exceed bound — reject explicitly
            Log.e("SprichIME", "pendingChannel trySend failed depth=$newDepth pending=${pending.token.utteranceId} result=$result — rejecting (bounded race)")
            queueDepth.decrementAndGet()
            catchingUpRejectedOnsets.incrementAndGet()
            finalizationQueueOverflows.incrementAndGet()
            updateCatchUpUi(true)
            liveUtterances.remove(pending.token.utteranceId)?.cancel()
            pending.pcm.fill(0)
            // P07 §6.4: retained audio released on bounded-race reject.
            try { tel(pending.token.utteranceId, TelemetryEventType.RELEASE) } catch (_: Exception) {}
            return
        }
        endpointPending.set(true)
    }

    private fun updateCatchUpUi(isCatchingUp: Boolean) {
        // Truthful, not silent drop. Human copy, no utterance jargon.
        scope.launch(Dispatchers.Main) {
            try {
                if (isCatchingUp) {
                    showStaticMessage(com.sprich.app.R.string.ime_catching_up, com.sprich.app.R.string.ime_pause_hint)
                    Log.w("SprichIME", "UI Catching up — suppressed=${catchingUpSuppressedOnsets.get()} rejected=${catchingUpRejectedOnsets.get()} depth=${queueDepth.get()}")
                } else {
                    if (session.state.value is SessionState.Listening || session.state.value is com.sprich.app.input.lifecycle.SessionState.Speech || session.state.value is com.sprich.app.input.lifecycle.SessionState.Finalizing) {
                        showStaticMessage(com.sprich.app.R.string.ime_listening, com.sprich.app.R.string.ime_idle_hint)
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
     * P03 lane bookkeeping. Marks the entry done/failed, then pops every
     * committable head. The serial actor commits FIFO, so a popped head that
     * is not the just-completed entry means overlapping finalization
     * completed out of order — observed in logs, never gating commits.
     * Drops (stale/cancelled, no result will ever commit) advance the lane
     * as failed: no text is fabricated and the lane cannot stall behind them.
     */
    private fun markQueueDone(queueSeq: Long, failed: Boolean) {
        try {
            if (failed) utteranceQueue.markFailed(queueSeq) else utteranceQueue.markRecognitionDone(queueSeq)
            drainQueueLane(queueSeq)
        } catch (_: Exception) {}
    }

    private fun drainQueueLane(queueSeq: Long) {
        try {
            while (true) {
                val head = utteranceQueue.pollCommittable() ?: break
                if (head.seq != queueSeq) {
                    Log.w("SprichIME", "commit-lane order: head seq=${head.seq} completed before seq=$queueSeq")
                }
                if (head.seq == queueSeq) break
            }
        } catch (_: Exception) {}
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
            if (pending.plan.apiPermissionEpoch != ApiHttp.currentEpoch) return
            // Atomically claim — second caller for same utteranceId drops silently (exactly-once)
            val claimed = finalizedUtterances.claim(token.utteranceId)
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
                markQueueDone(token.utteranceId, failed = true)
                // Do NOT clear utteranceActive/endpointPending if B is active with different token
                maybeClearActiveStateForToken(token)
                return
            }
            if (token.sessionId != session.sessionId || !session.isSessionValid(token.sessionId)) {
                Log.w("SprichIME", "finalizePending abandoned pre-decode invalid session token=$token sessionId=${session.sessionId}")
                staleCallbackDrops++
                markQueueDone(token.utteranceId, failed = true)
                maybeClearActiveStateForToken(token)
                return
            }
            if (token.fieldId != currentFieldId || token.fieldGeneration != fieldGeneration.get()) {
                Log.w("SprichIME", "finalizePending abandoned pre-decode field mismatch token=$token currentField=$currentFieldId fg=${fieldGeneration.get()}")
                staleCallbackDrops++
                markQueueDone(token.utteranceId, failed = true)
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
            tel(token.utteranceId, TelemetryEventType.RECOGNITION_START)
            var recognitionFailed = false
            // P07: per-utterance outcome (remote attempt record included) instead
            // of the coarse local-only mapping. The AttemptBox rule stays in the
            // coordinator; this layer only maps it to telemetry truthfully.
            val coordinatorOutcome: CoordinatorOutcome = try {
                ensureTranscriptionCoordinator().transcribeWithOutcome(pending.pcm, plan, pending.token.utteranceId, pending.live) {
                    updateRemoteProgress(pending.token, it)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SprichIME", "transcriptionCoordinator failed utt=${token.utteranceId}", e)
                recognitionFailed = true
                tel(token.utteranceId, TelemetryEventType.RECOGNITION_END, UtteranceOutcome.INFERENCE_FAILURE)
                markQueueDone(token.utteranceId, failed = true)
                CoordinatorOutcome(
                    result = TranscriptionResult("", ResolvedUtteranceLanguage.Unknown, plan.speechConfig, TranscriptionSourceId.LOCAL_FAST),
                    remoteFailure = null,
                    remoteAttempted = false,
                    fellBackToLocal = false,
                )
            }
            val transcriptionResult: TranscriptionResult = coordinatorOutcome.result
            val elapsed = android.os.SystemClock.elapsedRealtime() - t0
            if (!recognitionFailed) {
                // Blank with a remote failure means the failure explains the
                // blank (not proven silence); blank without one is NO_SPEECH.
                // Non-blank after a failed remote attempt is SUCCESS via local
                // fallback (fellBackToLocal), with the attempt kept in logs.
                val remoteOutcome = coordinatorOutcome.remoteFailure?.let { remoteFailureOutcome(it) }
                if (remoteOutcome != null) {
                    val code = (coordinatorOutcome.remoteFailure as? ApiFailure.Http)?.status ?: 0
                    Log.i("SprichIME", "remote attempt utt=${token.utteranceId} failure=${coordinatorOutcome.remoteFailure} fallback=${coordinatorOutcome.fellBackToLocal} blank=${transcriptionResult.text.isBlank()}")
                    tel(token.utteranceId, TelemetryEventType.RECOGNITION_END, remoteOutcome, code)
                } else {
                    val outcome =
                        if (transcriptionResult.text.isBlank()) UtteranceOutcome.NO_SPEECH else UtteranceOutcome.SUCCESS
                    tel(token.utteranceId, TelemetryEventType.RECOGNITION_END, outcome)
                    // P07 §6.4: local provider produced its final transcript.
                    // Remote finals arrive via updateRemoteProgress COMPLETE.
                    if (!coordinatorOutcome.remoteAttempted) {
                        tel(token.utteranceId, TelemetryEventType.PROVIDER_FINAL, outcome)
                    }
                }
                markQueueDone(token.utteranceId, failed = false)
            }
            // U02 dictated replacement: an utterance endpointed after arming
            // delivers its text to the open sheet instead of the field. The
            // user reviews before Apply (which revalidates the exact span),
            // so no field mutation happens here; the lane entry resolves done
            // with no cleanup attempted.
            val armedCorrection = correctionDictationTarget
            if (!recognitionFailed && armedCorrection != null && transcriptionResult.text.isNotBlank() &&
                correctionDictationEndedAfterArm(pending.endpointTimestampNanos, correctionArmedAtNanos)
            ) {
                deliverCorrectionDictation(transcriptionResult.text)
                try { utteranceQueue.markCleanupDone(token.utteranceId) } catch (_: Exception) {}
                drainQueueLane(token.utteranceId)
                try { maybeClearActiveStateForToken(token) } catch (_: Exception) {}
                return
            }
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
                saveRecoveryDraft(token, transcriptionResult.text, postProcessResolved)
                markQueueDone(token.utteranceId, failed = true)
                maybeClearActiveStateForToken(token)
                return
            }
            if (token.sessionId != session.sessionId || !session.isSessionValid(token.sessionId)) {
                Log.w("SprichIME", "finalizePending abandoned post-decode invalid session token=$token")
                staleCallbackDrops++
                saveRecoveryDraft(token, transcriptionResult.text, postProcessResolved)
                markQueueDone(token.utteranceId, failed = true)
                maybeClearActiveStateForToken(token)
                return
            }
            if (token.fieldId != currentFieldId || token.fieldGeneration != fieldGeneration.get()) {
                Log.w("SprichIME", "finalizePending abandoned post-decode field mismatch token=$token")
                staleCallbackDrops++
                saveRecoveryDraft(token, transcriptionResult.text, postProcessResolved)
                markQueueDone(token.utteranceId, failed = true)
                maybeClearActiveStateForToken(token)
                return
            }
            val currentIc = try { currentInputConnection } catch (_: Exception) { null }
            if (currentIc == null) {
                Log.w("SprichIME", "finalizePending abandoned post-decode no InputConnection token=$token")
                staleCallbackDrops++
                saveRecoveryDraft(token, transcriptionResult.text, postProcessResolved)
                markQueueDone(token.utteranceId, failed = true)
                maybeClearActiveStateForToken(token)
                return
            }
            if (!fieldController.isCurrentSession(token.sessionId)) {
                Log.w("SprichIME", "finalizePending abandoned post-decode fieldController stale token=$token")
                staleCallbackDrops++
                saveRecoveryDraft(token, transcriptionResult.text, postProcessResolved)
                markQueueDone(token.utteranceId, failed = true)
                maybeClearActiveStateForToken(token)
                return
            }

            // S01: typed command authority — parse exactly once via the trusted parser and
            // switch on the result type; never reinterpret text. Refinement receives/returns
            // text only. Remote-origin destructive phrases arrive as Text (confirmation-only).
            // P1-17: Apply personal vocabulary before refinement, send only relevant terms.
            val raw = transcriptionResult.text.trim()
            // A labeled conversation keeps the provider's final spans intact; commands and cleanup
            // must not turn one speaker's words into an edit or move text between speakers.
            val speakerDictation = transcriptionResult.source == TranscriptionSourceId.API_META_MUSE &&
                (plan.transcription as? TranscriptionPlan.ApiPrimary)?.remote?.options?.speakerLabels == true
            var cleanupNotice: Int? = null
            // P07: authoritative per-utterance fallback record (replaces the plan+source derivation).
            val localFallbackUsed = coordinatorOutcome.fellBackToLocal
            Log.i("SprichIME", "pipeline: token=${token.utteranceId} source=${transcriptionResult.source} mode=${plan.transcription::class.simpleName} rawLen=${raw.length} lang=${effectiveConfig.resolvedLanguageTag()} refinement=${plan.refinement::class.simpleName}")
            val origin = if (transcriptionResult.source == TranscriptionSourceId.LOCAL_FAST ||
                transcriptionResult.source == TranscriptionSourceId.LOCAL_CANARY
            ) TranscriptOrigin.LOCAL else TranscriptOrigin.REMOTE
            // P02: per-utterance VAD speech-episode flag snapshotted at endpoint time
            // (pending.hadSpeech). Fail-safe mapping: speech present enables legitimate
            // symbol output; otherwise legacy UNKNOWN (symbol-only stays suppressed).
            // The flag is session-latched, so this never drops genuine dictation.
            val commandPolicy = CommandPolicy(
                commandsEnabled = plan.speechConfig.enableCommands,
                origin = origin,
                speakerLabelsPresent = speakerDictation,
            )
            val parsed: ParsedDictation = try {
                if (speakerDictation) ParsedDictation.Text(raw)
                else SpokenEditingParser.parseTyped(raw, postProcessResolved, commandPolicy, AcousticBasis.fromHadSpeech(pending.hadSpeech))
            } catch (_: Exception) {
                ParsedDictation.Text(raw)
            }
            // P03: set inside the Text branch when optional cleanup is attempted
            // and exhausted (timeout/discard/reject); read at commit time.
            var cleanupExhausted = false
            val prepared: PreparedFinalAction = when (parsed) {
                is ParsedDictation.Editing -> {
                    // Only local-origin destructive commands reach here, and only for an exact
                    // whole-utterance phrase in a Known language with commands enabled.
                    // Mutation still requires fresh owned-span proof in executeSpokenDeleteLast.
                    Log.i("SprichIME", "spoken editing command action=${parsed.action} origin=$origin")
                    PreparedFinalAction.DeleteLast(token)
                }
                is ParsedDictation.Formatting -> {
                    // Whole-utterance formatting command: deterministic, no cleanup round-trip.
                    Log.i("SprichIME", "spoken formatting command action=${parsed.action}")
                    PreparedFinalAction.Text(parsed.rendered, postProcessResolved)
                }
                is ParsedDictation.Rejected -> {
                    Log.i("SprichIME", "parse rejected reason=${parsed.reason} — zero mutation token=$token")
                    PreparedFinalAction.Text(PreparedFinalAction.REJECTED_PARSE_TEXT, postProcessResolved)
                }
                is ParsedDictation.Text -> {
                // Non-command: deterministic text with vocab applied before refinement (P1-17 step 2)
                var deterministic = parsed.value
                val vocabularyProfile = com.sprich.app.vocab.RecognitionProfile.result(plan, transcriptionResult.source)?.key
                if (!speakerDictation) deterministic = try { plan.vocabulary.apply(deterministic, vocabularyProfile) } catch (_: Exception) { deterministic }
                // S04: relevant-term discovery uses the same canonical comparison as enforcement,
                // so discovery and the validator can never disagree about a term's presence.
                val relevantTerms: List<String> = try {
                    com.sprich.app.speech.refinement.TermCanonical.relevantTerms(deterministic, plan.vocabulary.terms())
                } catch (_: Exception) { emptyList<String>() }
                val refinedText: String = when (val rp = plan.refinement) {
                    is RefinementPlan.Off -> deterministic
                    is RefinementPlan.Enabled -> {
                        if (speakerDictation || deterministic.isBlank()) deterministic else {
                            refinementStarts++
                            val provider = providerForRefinementConfig(rp.config)
                            if (provider == null) {
                                cleanupNotice = com.sprich.app.R.string.ime_without_cleanup
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
                                    context = plan.fieldContext,
                                    promptVersion = rp.config.promptVersion,
                                    whisperMode = plan.whisperMode,
                                )
                                val deadlineMs = rp.config.deadlineMs
                                // P03/P07: optional cleanup is the queue's sheddable unit with
                                // a hard deadline; expiry falls back to faithful deterministic.
                                try { utteranceQueue.markCleanupPending(token.utteranceId) } catch (_: Exception) {}
                                tel(token.utteranceId, TelemetryEventType.CLEANUP_START)
                                val refinedResult = try {
                                    kotlinx.coroutines.withTimeoutOrNull(deadlineMs) { provider.refine(req) }
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                    cleanupNotice = com.sprich.app.R.string.ime_without_cleanup
                                    Log.w("SprichIME", "refinement failed: ${(e as? ApiException)?.failure ?: "unavailable"}")
                                    null
                                }
                                if (refinedResult == null) {
                                    if (cleanupNotice == null) cleanupNotice = com.sprich.app.R.string.ime_cleanup_timed_out
                                    Log.w("SprichIME", "refinement timeout/discard after ${deadlineMs}ms, using deterministic")
                                    cleanupExhausted = true
                                    tel(token.utteranceId, TelemetryEventType.CLEANUP_END)
                                    deterministic
                                } else {
                                    val candidate = refinedResult.text.trim()
                                    val validation = RefinementValidator.validate(deterministic, candidate, rp.mode, relevantTerms)
                                    when (validation) {
                                        is RefinementValidator.Result.Accept -> {
                                            Log.i("SprichIME", "refinement accepted mode=${rp.mode} inLen=${deterministic.length} outLen=${candidate.length} latency=${refinedResult.latencyMs}")
                                            tel(token.utteranceId, TelemetryEventType.CLEANUP_END)
                                            tel(token.utteranceId, TelemetryEventType.CLEANUP_VALIDATED)
                                            candidate
                                        }
                                        is RefinementValidator.Result.Reject -> {
                                            cleanupNotice = com.sprich.app.R.string.ime_cleanup_kept_original
                                            Log.w("SprichIME", "refinement rejected reason=${validation.reason}, using deterministic")
                                            cleanupExhausted = true
                                            tel(token.utteranceId, TelemetryEventType.CLEANUP_END)
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
            }

            // S01: the prepared action commits directly — refined text is NEVER reinterpreted
            // as a command, and no late cleanup/provider response may rewrite committed text.
            // Spoken delete: only delete exact owned insertion if proof holds, else zero mutation.
            currentCoroutineContext().ensureActive()
            if (plan.apiPermissionEpoch != ApiHttp.currentEpoch) return
            // P03: resolve the lane entry — expired cleanup commits faithful
            // deterministic, otherwise the (possibly unrefined) result is ready.
            try {
                if (cleanupExhausted) utteranceQueue.expireCleanup(token.utteranceId)
                else utteranceQueue.markCleanupDone(token.utteranceId)
            } catch (_: Exception) {}
            tel(token.utteranceId, TelemetryEventType.COMMIT_ATTEMPT)
            val applied: Boolean = when (prepared) {
                is PreparedFinalAction.DeleteLast -> {
                    val ok = executeSpokenDeleteLast(token)
                    if (ok) finalCommitCount++
                    ok
                }
                is PreparedFinalAction.Text -> {
                    // P05 §5.3: split continuations reconcile against the
                    // previous split commit — verified dedupe only, never
                    // blind concatenation. Non-split commits clear the chain.
                    var commitText = (prepared as PreparedFinalAction.Text).text
                    var wasReconciled = false
                    try {
                        val prev = lastSplitCommit
                        if (pending.splitOverlap != null && prev != null) {
                            val reconciled = com.sprich.app.core.audio.ContinuousSpeechPolicy.reconcileWithOverlapSearch(
                                prev.second,
                                commitText,
                                overlapSpan = pending.splitOverlap,
                            )
                            if (reconciled.overlapDeduped) {
                                Log.i("SprichIME", "split reconcile deduped token=$token prev=${prev.first} chars=${commitText.length}->${reconciled.text.length}")
                                commitText = reconciled.text
                                wasReconciled = true
                            }
                        }
                    } catch (_: Exception) {}
                    if (commitText.isEmpty()) {
                        Log.w("SprichIME", "final transcript empty token=$token")
                        try { lastSplitCommit = null } catch (_: Exception) {}
                        false
                    } else {
                        val ok = commitFinalText(token, commitText, prepared.resolved)
                        Log.i("SprichIME", "final token=$token chars=${commitText.length} applied=$ok elapsedMs=$elapsed lang=${effectiveConfig.resolvedLanguageTag()} lid=$lidDetected resolved=$postProcessResolved")
                        if (ok) finalCommitCount++
                        try {
                            lastSplitCommit =
                                if (ok && (pending.splitOverlap != null || wasReconciled)) token.utteranceId to commitText
                                else null
                        } catch (_: Exception) {}
                        ok
                    }
                }
            }
            tel(
                token.utteranceId,
                TelemetryEventType.COMMIT_RESULT,
                if (applied) UtteranceOutcome.SUCCESS else UtteranceOutcome.CANCELLED,
            )
            drainQueueLane(token.utteranceId)
            // P07 §6.4: lane exit — the entry is popped above, retained
            // audio eligible for release. Guard dedups against the
            // maybeClearActiveStateForToken path below.
            try {
                if (telemetryStageGuard.shouldEmit(token.utteranceId, TelemetryEventType.RELEASE)) {
                    tel(token.utteranceId, TelemetryEventType.RELEASE)
                }
            } catch (_: Exception) {}
            if (prepared is PreparedFinalAction.Text && (prepared as PreparedFinalAction.Text).text.isNotEmpty() && !applied) {
                // UTTERANCE-SCOPED: editor ambiguous must NOT destroy active B
                failUtteranceScoped(token, "final text insertion ambiguous token=$token", null)
                return
            }
            if (prepared is PreparedFinalAction.Text && (prepared as PreparedFinalAction.Text).text.isBlank() && !applied) {
                // P07: per-utterance remote failure (never the legacy global) decides the notice.
                resultNotice = if (plan.transcription is TranscriptionPlan.ApiPrimary && coordinatorOutcome.remoteFailure != null) com.sprich.app.R.string.ime_api_failure else com.sprich.app.R.string.ime_no_speech
            } else if (prepared is PreparedFinalAction.Text && !applied) {
                // U03: recognized text, nothing committed — keep for explicit retry + Home review.
                saveRecoveryDraft(token, prepared.text, prepared.resolved)
                resultNotice = com.sprich.app.R.string.ime_kept_retry
                try { micContainer?.announceForAccessibility(uiString(resultNotice!!)) } catch (_: Exception) {}
            } else if (applied) {
                resultNotice = when {
                    cleanupNotice != null -> cleanupNotice
                    localFallbackUsed -> com.sprich.app.R.string.ime_local_fallback
                    else -> null
                }
                resultNotice?.let { micContainer?.announceForAccessibility(uiString(it)) }
                latency.mark("textCommitted")
                celebrateCommit()
            }

            // Post-commit housekeeping moved after prepared handling
            lastPartialText = ""
            // Only clear active capture state if this pending still owns it; otherwise B is active and must not be corrupted.
            maybeClearActiveStateForToken(token)

            if (reason == StopReason.ENDPOINT && !utteranceActive.get()) session.onListeningAgain()
            if (remotePreviewId == token.utteranceId) { remotePreview = null; remotePreviewId = null }
            if (session.state.value is SessionState.Listening) updateImeUi(true)
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
            // P07 §6.4: this token's active capture ownership is released.
            try {
                if (telemetryStageGuard.shouldEmit(token.utteranceId, TelemetryEventType.RELEASE)) {
                    tel(token.utteranceId, TelemetryEventType.RELEASE)
                }
            } catch (_: Exception) {}
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
            live = captured.live,
            hadSpeech = try { vad.hasSpeech() } catch (_: Exception) { false },
        )
        captured.live?.sealInput(snap)
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
        userStatus: Int,
        userHint: Int,
        throwable: Throwable?,
    ) {
        if (generation != sessionGeneration.get()) { staleCallbackDrops++; return }
        if (throwable != null) Log.e("SprichIME", reason, throwable) else Log.e("SprichIME", reason)
        sessionGeneration.incrementAndGet()
        fieldGeneration.incrementAndGet()
        cancelLiveUtterances()
        // P03: session failed — pending lane work is stale, discard it.
        try { utteranceQueue.cancel() } catch (_: Exception) {}
        currentFieldId = null
        currentUtteranceToken = null
        activeUtterance = null
        // Claimed ids are intentionally retained (bounded to 128 inside
        // FinalizationClaims) to prevent utterance-id reuse after failure.
        // P05 §5.3: split-continuation state never survives a failure.
        try { clearSplitContinuationState() } catch (_: Exception) {}
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
        showStaticMessage(userStatus, userHint)
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
        tel(token.utteranceId, TelemetryEventType.CANCELLATION)
        // P03: scoped failure advances the lane as failed — no result commits.
        try { utteranceQueue.markFailed(token.utteranceId) } catch (_: Exception) {}
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
        showStaticMessage(com.sprich.app.R.string.ime_insert_failed, com.sprich.app.R.string.ime_insert_retry)
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
            StopReason.USER_STOP, StopReason.SESSION_LIMIT -> wasActive && hasActiveUtterance && currentPcmSize > 0 && notYetFinalized
            StopReason.ENDPOINT -> false // endpoint path uses finalizeOnce directly, not stopDictation
            else -> false
        }

        // USER_STOP must be FIFO-serialized with ENDPOINT via same finalization actor — no direct finalizePending call that bypasses queue.
        // P05 §5.3: SESSION_LIMIT shares the drain path so capped sessions preserve all captured text.
        if (reason == StopReason.USER_STOP || reason == StopReason.SESSION_LIMIT) {
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
                    live = captured.live,
                    hadSpeech = try { vad.hasSpeech() } catch (_: Exception) { false },
                )
                captured.live?.sealInput(snap)
                enqueuePending(pending)
                Log.i("SprichIME", "USER_STOP enqueued final token=$token queueDepth=${queueDepth.get()} snapSamples=${snap.size}")
            }
            // If queue empty and no final to enqueue, terminate immediately; else await drain via actor
            if (queueDepth.get() == 0 && !shouldEnqueueFinal) {
                val newGen = sessionGeneration.incrementAndGet()
                cancelLiveUtterances()
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
                // P05 §5.3: split-continuation state never survives a stop.
                try { clearSplitContinuationState() } catch (_: Exception) {}
                try { composition.discardPartial(currentInputConnection) } catch (_: Exception) {}
                try { if (session.state.value !is SessionState.Idle) session.end() } catch (_: Exception) { try { session.idle() } catch (_: Exception) {} }
                // P03: intake closed, accepted work already drained via the actor (queue empty by branch condition).
                try {
                    val drained = utteranceQueue.finish()
                    if (drained.isNotEmpty()) Log.w("SprichIME", "USER_STOP finish drained ${drained.size} unexpected entries")
                } catch (_: Exception) {}
                updateImeUi(false)
                writeDiagnostics("stopped $reason immediate (no queue) prevGen=$generationAtStop newGen=$newGen claims=$finalizationClaims")
            } else {
                // Enqueued — mark stop after drain, preserve FIFO order, no generation bump yet.
                // P05 §5.3: split chain cleared now — in-flight split items
                // keep full text (safe fallback, no reconcile base).
                try { clearSplitContinuationState() } catch (_: Exception) {}
                stopRequested = true
                stopRequestedGeneration = generationAtStop
                showStaticMessage(com.sprich.app.R.string.ime_stopping, com.sprich.app.R.string.ime_finishing)
                Log.i("SprichIME", "$reason awaiting drain queueDepth=${queueDepth.get()} generation=$generationAtStop enqueuedFinal=$shouldEnqueueFinal")
                writeDiagnostics("$reason awaiting drain queueDepth=${queueDepth.get()} generation=$generationAtStop enqueuedFinal=$shouldEnqueueFinal")
            }
            if (wasActive) vibrateStop()
            return
        }

        // Non-finalizing stops: advance generation IMMEDIATELY to make old tokens permanently stale
        val newGeneration = sessionGeneration.incrementAndGet()
        fieldGeneration.incrementAndGet()
        cancelLiveUtterances()
        // U03: abandon-type stops must not kill an in-flight snapshot decode —
        // post-decode validation still refuses insertion (stale generation) and
        // preserves obtainable text as a recovery draft. Only user cancel,
        // sensitive fields, and teardown cancel immediately.
        if (reason.cancelsInflightDecode()) {
            try { endpointJob?.cancel() } catch (_: Exception) {}
            endpointJob = null
        }
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
        // P03: generation invalidated above — pending lane work is stale, discard it.
        try { utteranceQueue.cancel() } catch (_: Exception) {}
        updateImeUi(false)
        if (wasActive) vibrateStop()
        Log.i("SprichIME", "dictation stopped reason=$reason newGen=$newGeneration field=$currentFieldId sessionState=${session.state.value}")
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

    private fun switchToTypingKeyboard(): Boolean {
        cancelBarGesture()
        stopDictation(StopReason.WINDOW_HIDDEN)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val choices = imm.enabledInputMethodList.filter { method ->
            method.packageName != packageName && (method.subtypeCount == 0 ||
                (0 until method.subtypeCount).any { method.getSubtypeAt(it).mode == "keyboard" && !method.getSubtypeAt(it).isAuxiliary })
        }
        // Direct switching keeps the next IME window visible on Samsung. Its public "previous"
        // operation changes the selected IME but can collapse the window. Android persists this
        // colon-separated history; every candidate is still validated against the enabled list.
        val enabledIds = choices.asSequence().map { it.id }.toSet()
        val recentTypingId = runCatching {
            android.provider.Settings.Secure
                .getString(contentResolver, "input_methods_subtype_history")
                .orEmpty()
                .split(':')
                .asSequence()
                .map { it.substringBefore(';') }
                .firstOrNull { it in enabledIds }
        }.getOrNull()
        val switched = runCatching {
            if (recentTypingId != null || choices.size == 1) {
                switchInputMethod(recentTypingId ?: choices.single().id)
                true
            } else if (choices.isNotEmpty()) {
                if (android.os.Build.VERSION.SDK_INT >= 28) switchToPreviousInputMethod()
                else {
                    @Suppress("DEPRECATION")
                    val token = window?.window?.attributes?.token
                    token != null && imm.switchToLastInputMethod(token)
                }
            } else false
        }.getOrDefault(false)
        if (switched) { vibrateTick(); return true }
        // The picker is the safe fallback when Android has no remembered typing keyboard.
        imm.showInputMethodPicker()
        if (choices.isEmpty()) android.widget.Toast.makeText(this, com.sprich.app.R.string.ime_no_keyboard, android.widget.Toast.LENGTH_SHORT).show()
        return false
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

    // The gesture owns the editor anchor. Each held repeat must earn a new anchor after its one mutation.
    private fun beginGestureDeletion() {
        if (isDictationRunning() || audio.isActive() || queueDepth.get() > 0) stopDictation(StopReason.CURSOR_MOVED)
        deletionGesture?.cancel()
        deletionGesture = editorActionController.beginDeletion(currentInputConnection, currentFieldId, fieldGeneration.get(), isPasswordField || isPassword(currentInputEditorInfo))
    }

    private fun deleteOnce(unit: EditorActionController.Unit): Boolean {
        beginGestureDeletion()
        return try { performDeletion(unit) } finally { deletionGesture?.cancel(); deletionGesture = null }
    }

    private fun performDeletion(unit: EditorActionController.Unit): Boolean {
        val gesture = deletionGesture ?: return false
        val ic = currentInputConnection ?: return false
        // This is exactly one editor mutation. A batch can defer Compose's surrounding-text update,
        // causing the controller to verify before the mutation is observable and correctly withhold Undo.
        val ok = try {
            editorActionController.deleteStep(gesture, unit, ic, currentFieldId, fieldGeneration.get(), isPasswordField || isPassword(currentInputEditorInfo))
        } catch (_: Exception) { gesture.cancel(); false }
        if (ok) { noteOwnEditorChange(true); vibrateTick() }
        updateEditingControls()
        return ok
    }

    private fun undoLastDelete(): Boolean {
        if (isDictationRunning()) stopDictation(StopReason.CURSOR_MOVED)
        val ok = editorActionController.undoDeletion(currentInputConnection, currentFieldId, fieldGeneration.get(), isPasswordField || isPassword(currentInputEditorInfo))
        if (ok) { noteOwnEditorChange(true); vibrateTick() }
        updateEditingControls()
        return ok
    }

    private fun updateEditingControls() {
        undoButton?.visibility = if (!isPasswordField && editorActionController.historySize() > 0) View.VISIBLE else View.GONE
        whisperBadge?.visibility = if (whisperMode && !isPasswordField) View.VISIBLE else View.GONE
        retryButton?.visibility = if (retryVisibleNow()) View.VISIBLE else View.GONE
        correctButton?.visibility = if (correctionEntryVisible()) View.VISIBLE else View.GONE
    }

    private fun retryVisibleNow(): Boolean = try {
        val record = lastFailedCommit ?: return false
        val icPresent = try { currentInputConnection != null } catch (_: Exception) { false }
        val controllerCurrent = try { fieldController.isCurrentSession(record.token.sessionId) } catch (_: Exception) { false }
        retryEligible(record, currentFieldId, icPresent, controllerCurrent, isPasswordField)
    } catch (_: Exception) {
        false
    }

    /**
     * U03: keep recognized-but-uncommitted text for explicit retry + Home
     * review. Call with non-blank text only, on paths where recognition
     * produced text but nothing was committed (failed insert, abandoned
     * field) — never on user-cancelled or empty paths.
     */
    private fun saveRecoveryDraft(token: UtteranceToken, text: String, resolved: ResolvedUtteranceLanguage) {
        if (text.isBlank()) return
        lastFailedCommit = FailedCommit(token, text, resolved)
        try {
            val app = application as? SprichApp ?: return
            if (app.recoveryDrafts.save(text)) app.noteRecoveryChanged()
        } catch (_: Exception) {
        }
        try { updateEditingControls() } catch (_: Exception) {}
    }

    /**
     * U03: explicit user-tap retry of the failed insert, same field only.
     * [commitFinalText] revalidates generation/session/field/IC-identity/
     * password/editor-ownership itself, so a stale record can only fail
     * honestly — never corrupt another field or session.
     */
    private fun retryFailedCommit() {
        val record = lastFailedCommit ?: return
        if (!retryVisibleNow()) {
            lastFailedCommit = null
            try { updateEditingControls() } catch (_: Exception) {}
            return
        }
        val ok = try { commitFinalText(record.token, record.text, record.resolved) } catch (_: Exception) { false }
        if (ok) {
            finalCommitCount++
            lastFailedCommit = null
            try {
                (application as? SprichApp)?.let { it.recoveryDrafts.consume(); it.noteRecoveryChanged() }
            } catch (_: Exception) {}
            Log.i("SprichIME", "recovery retry inserted utt=${record.token.utteranceId} chars=${record.text.length}")
        } else {
            Log.w("SprichIME", "recovery retry still failing utt=${record.token.utteranceId} — draft kept")
        }
        try { updateEditingControls() } catch (_: Exception) {}
    }

    /**
     * U02 editor snapshot for correction targeting. Prefers extracted text
     * (absolute cursor known); falls back to capped before/after, in which
     * case absolute offsets are known only when `before` reached field start.
     */
    private data class CorrectionEditorSnapshot(
        val before: String,
        val after: String,
        val cursorAbsolute: Int,
        val collapsed: Boolean,
    )

    private fun readEditorSnapshot(maxBefore: Int = 1000, maxAfter: Int = 256): CorrectionEditorSnapshot? {
        val ic = try { currentInputConnection } catch (_: Exception) { null } ?: return null
        return try {
            val extracted = try {
                ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            } catch (_: Exception) {
                null
            }
            if (extracted != null && extracted.text != null) {
                val text = extracted.text.toString()
                val selStart = extracted.selectionStart.coerceIn(0, text.length)
                val selEnd = extracted.selectionEnd.coerceIn(0, text.length)
                CorrectionEditorSnapshot(text.substring(0, selStart), text.substring(selStart), selStart, selStart == selEnd)
            } else {
                val before = try { ic.getTextBeforeCursor(maxBefore, 0)?.toString() } catch (_: Exception) { null } ?: return null
                val after = try { ic.getTextAfterCursor(maxAfter, 0)?.toString() } catch (_: Exception) { null } ?: ""
                val cursorAbs = if (before.length < maxBefore) before.length else -1
                CorrectionEditorSnapshot(before, after, cursorAbs, true)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun correctionEntryVisible(): Boolean {
        if (isPasswordField) return false
        return try {
            val ic = try { currentInputConnection } catch (_: Exception) { null } ?: return false
            val before = try { ic.getTextBeforeCursor(64, 0)?.toString() } catch (_: Exception) { null } ?: return false
            com.sprich.app.input.correction.CorrectionPlan.wordAtCursor(before, before.length) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * U02: open the correction sheet on the cursor word. Captures the
     * exact-span target (field + generation + offsets + surrounding snapshot);
     * every later mutation revalidates against a FRESH snapshot, so a moved
     * cursor or host edit can only refuse honestly, never mis-splice.
     */
    private fun openCorrectionSheet() {
        try { closeCorrectionSheet() } catch (_: Exception) {}
        if (isPasswordField) { Log.i("SprichIME", "correction sheet refused: password field"); return }
        val fieldId = currentFieldId
        if (fieldId == null) { Log.i("SprichIME", "correction sheet refused: no field"); return }
        val snap = readEditorSnapshot()
        if (snap == null) { Log.i("SprichIME", "correction sheet refused: no snapshot field=$fieldId"); return }
        if (!snap.collapsed || snap.cursorAbsolute < 0) { Log.i("SprichIME", "correction sheet refused: selection collapsed=${snap.collapsed} cursor=${snap.cursorAbsolute}"); return }
        val (start, len) = com.sprich.app.input.correction.CorrectionPlan.wordAtCursor(snap.before, snap.before.length)
            ?: run { Log.i("SprichIME", "correction sheet refused: no word before=${snap.before.takeLast(16)}"); return }
        val word = try { snap.before.substring(start, start + len) } catch (_: Exception) { return }
        if (word.isBlank()) { Log.i("SprichIME", "correction sheet refused: blank word"); return }
        Log.i("SprichIME", "correction sheet opening word=$word field=$fieldId")
        correctionTarget = com.sprich.app.input.correction.CorrectionTarget(
            fieldId = fieldId,
            fieldGeneration = fieldGeneration.get(),
            startOffset = start,
            text = word,
            beforeAtOpen = snap.before,
            afterAtOpen = snap.after,
        )
        correctionUndo = null
        lastAppliedReplacement = null
        correctionController.targetWord = word
        correctionController.replacement = word
        correctionController.listening = false
        correctionController.undoAvailable = false
        correctionController.proposal = null
        correctionController.rememberAvailable = false
        correctionController.notice = null
        // Classic Views: the IME service window has no Activity lifecycle,
        // so Compose content would need hand-wired view-tree owners.
        val dialog = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog)
        val sheet = com.sprich.app.ui.correction.buildCorrectionSheetView(
            this,
            correctionController,
            com.sprich.app.ui.correction.CorrectionSheetActions(
                onReplacementChange = { correctionController.replacement = it },
                onProposalTap = {
                    correctionController.proposal?.let { correctionController.replacement = it.newForm }
                },
                onDictate = { armCorrectionDictation() },
                onApply = { applyCorrection() },
                onUndo = { undoCorrection() },
                onRemember = { rememberSpelling() },
                onDismiss = { closeCorrectionSheet() },
            ),
        )
        correctionSheetView = sheet
        // Controller mutations can arrive off the UI thread (dictation /
        // correction callbacks); always refresh via the attached view.
        correctionController.onChange = {
            try {
                val root = sheet.root
                root.post {
                    try {
                        if (correctionDialog === dialog) sheet.refresh(correctionController)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        dialog.setContentView(
            sheet.root,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        try {
            dialog.window?.let { w ->
                w.attributes?.token = try { rootView?.windowToken } catch (_: Exception) { null }
                w.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
                // Transparent window decor: the rounded panel draws the sheet in both themes.
                w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
        } catch (_: Exception) {}
        correctionDialog = dialog
        try {
            dialog.show()
            Log.i("SprichIME", "correction sheet shown")
        } catch (e: Exception) {
            Log.w("SprichIME", "correction sheet show failed", e)
            correctionDialog = null
        }
    }

    private fun closeCorrectionSheet() {
        try { correctionController.onChange = null } catch (_: Exception) {}
        correctionSheetView = null
        try { correctionDialog?.dismiss() } catch (_: Exception) {}
        correctionDialog = null
        correctionTarget = null
        correctionUndo = null
        correctionDictationTarget = null
        lastAppliedReplacement = null
        try {
            correctionController.listening = false
            correctionController.undoAvailable = false
            correctionController.proposal = null
            correctionController.rememberAvailable = false
            correctionController.notice = null
        } catch (_: Exception) {}
    }

    /**
     * U02 dictated replacement: arm routing, then start dictation unless
     * already running. Only an utterance endpointed AFTER arming delivers
     * (guarded by [correctionArmedAtNanos] at the finalize site), so an
     * in-flight unrelated utterance can never be hijacked into the sheet.
     * Arming and the endpoint timestamp both use [System.nanoTime] so the
     * guard survives deep sleep (see [correctionDictationEndedAfterArm]).
     */
    private fun armCorrectionDictation() {
        correctionDictationTarget = correctionTarget ?: return
        correctionArmedAtNanos = System.nanoTime()
        correctionController.listening = true
        correctionController.notice = null
        try {
            if (!isDictationRunning()) toggleDictation()
        } catch (_: Exception) {}
    }

    private fun deliverCorrectionDictation(text: String) {
        correctionDictationTarget = null
        correctionController.replacement = text
        correctionController.listening = false
        correctionController.notice = null
        Log.i("SprichIME", "correction dictated replacement chars=${text.length}")
    }

    /**
     * U02 Apply: re-read a fresh snapshot and run the full CorrectionPlan
     * revalidation (field, generation, span). The splice is one batch edit:
     * select exact span, commit replacement, park cursor after it.
     */
    private fun applyCorrection() {
        val target = correctionTarget ?: return
        val replacement = correctionController.replacement
        if (replacement.isBlank()) return
        val snap = readEditorSnapshot() ?: run {
            correctionController.notice = uiString(com.sprich.app.R.string.ime_insert_failed)
            return
        }
        if (!snap.collapsed || snap.cursorAbsolute < 0) {
            correctionController.notice = uiString(com.sprich.app.R.string.ime_insert_failed)
            return
        }
        val current = com.sprich.app.input.correction.EditorText(snap.before, snap.after)
        val fieldId = currentFieldId
        when (val res = com.sprich.app.input.correction.CorrectionPlan.planCorrection(
            target, current, fieldId, fieldGeneration.get(), replacement,
        )) {
            is com.sprich.app.input.correction.CorrectionResult.Rejected -> {
                Log.w("SprichIME", "correction rejected reason=${res.reason}")
                correctionController.notice = uiString(com.sprich.app.R.string.ime_insert_failed)
            }
            is com.sprich.app.input.correction.CorrectionResult.Apply -> {
                val absStart = snap.cursorAbsolute - snap.before.length + target.startOffset
                val absEnd = absStart + target.text.length
                val ic = try { currentInputConnection } catch (_: Exception) { null }
                val ok = if (ic == null || absStart < 0 || absEnd < 0) {
                    false
                } else try {
                    ic.beginBatchEdit()
                    val selected = try { ic.setSelection(absStart, absEnd) } catch (_: Exception) { false }
                    val committed = if (selected) {
                        try { ic.commitText(replacement, 1) } catch (_: Exception) { false }
                    } else false
                    if (committed) {
                        try { ic.setSelection(absStart + replacement.length, absStart + replacement.length) } catch (_: Exception) {}
                    }
                    try { ic.endBatchEdit() } catch (_: Exception) {}
                    committed
                } catch (_: Exception) {
                    try { ic.endBatchEdit() } catch (_: Exception) {}
                    false
                }
                if (ok) {
                    try { editorActionController.recordSprichInsertion(currentFieldId, fieldGeneration.get(), replacement, ic!!) } catch (_: Exception) {}
                    correctionUndo = res.undo
                    lastAppliedReplacement = replacement
                    correctionController.undoAvailable = true
                    correctionController.proposal = res.spellingProposal
                    correctionController.rememberAvailable =
                        res.spellingProposal != null && replacement.trim() == res.spellingProposal.newForm
                    correctionController.notice = null
                    Log.i("SprichIME", "correction applied word='${target.text}' chars=${replacement.length}")
                } else {
                    correctionController.notice = uiString(com.sprich.app.R.string.ime_insert_failed)
                }
            }
        }
    }

    private fun undoCorrection() {
        val undo = correctionUndo ?: return
        val snap = readEditorSnapshot() ?: return
        if (!snap.collapsed || snap.cursorAbsolute < 0) return
        if (!com.sprich.app.input.correction.CorrectionPlan.canUndo(snap.before, undo)) {
            correctionController.notice = uiString(com.sprich.app.R.string.ime_insert_failed)
            correctionUndo = null
            correctionController.undoAvailable = false
            return
        }
        val absStart = snap.cursorAbsolute - snap.before.length + undo.startOffset
        val absEnd = absStart + undo.expectedCurrent.length
        val ic = try { currentInputConnection } catch (_: Exception) { null } ?: return
        val ok = try {
            ic.beginBatchEdit()
            val selected = try { ic.setSelection(absStart, absEnd) } catch (_: Exception) { false }
            val committed = if (selected) {
                try { ic.commitText(undo.restore, 1) } catch (_: Exception) { false }
            } else false
            if (committed) {
                try { ic.setSelection(absStart + undo.restore.length, absStart + undo.restore.length) } catch (_: Exception) {}
            }
            try { ic.endBatchEdit() } catch (_: Exception) {}
            committed
        } catch (_: Exception) {
            try { ic.endBatchEdit() } catch (_: Exception) {}
            false
        }
        if (ok) {
            correctionUndo = null
            lastAppliedReplacement = null
            correctionController.undoAvailable = false
            correctionController.rememberAvailable = false
            correctionController.notice = null
            Log.i("SprichIME", "correction undone restore='${undo.restore}'")
        } else {
            correctionController.notice = uiString(com.sprich.app.R.string.ime_insert_failed)
            Log.w("SprichIME", "correction undo failed")
        }
    }

    private fun rememberSpelling() {
        val proposal = correctionController.proposal ?: return
        if (!correctionController.rememberAvailable) return
        try {
            // scope is Main: Compose state sets stay on Main, repo write on IO.
            scope.launch {
                val notice = try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        vocabRepo.add(proposal.oldForm, proposal.newForm)
                    }
                    correctionController.rememberAvailable = false
                    uiString(com.sprich.app.R.string.correction_remembered)
                } catch (e: com.sprich.app.vocab.VocabularyConflictException) {
                    uiString(com.sprich.app.R.string.correction_conflict)
                } catch (_: Exception) {
                    uiString(com.sprich.app.R.string.ime_insert_failed)
                }
                correctionController.notice = notice
            }
        } catch (_: Exception) {}
    }

    private fun performBarAction(action: BarGestureRecognizer.Action): Boolean = when (action) {
        BarGestureRecognizer.Action.DELETE_UNIT -> performDeletion(EditorActionController.Unit.WORD_OR_SYMBOL)
        BarGestureRecognizer.Action.DELETE_PHRASE -> performDeletion(EditorActionController.Unit.PHRASE)
        BarGestureRecognizer.Action.DELETE_SENTENCE -> performDeletion(EditorActionController.Unit.SENTENCE)
        BarGestureRecognizer.Action.WHISPER -> toggleWhisperMode()
        BarGestureRecognizer.Action.SWITCH_KEYBOARD -> switchToTypingKeyboard()
        BarGestureRecognizer.Action.HIDE -> { dismissKeyboard(); true }
        BarGestureRecognizer.Action.SETTINGS -> { openSprichSettings(); true }
        BarGestureRecognizer.Action.TAP -> { toggleDictation(); true }
    }

    private fun cancelBarGesture() {
        pillViewRef?.cancelGesture()
        deletionGesture?.cancel(); deletionGesture = null
    }

    private fun showGesturePreview(preview: BarGestureRecognizer.Preview) {
        if (isPasswordField && preview !in setOf(BarGestureRecognizer.Preview.NONE, BarGestureRecognizer.Preview.HIDE)) {
            gesturePreview = BarGestureRecognizer.Preview.NONE
            refreshSessionUi()
            return
        }
        if (gesturePreview == BarGestureRecognizer.Preview.NONE && preview != BarGestureRecognizer.Preview.NONE) {
            statusBeforeGesture = statusText?.text
            hintBeforeGesture = (statusText?.tag as? TextView)?.text
        }
        gesturePreview = preview
        if (preview == BarGestureRecognizer.Preview.NONE) {
            if (session.state.value is SessionState.Error) {
                statusText?.text = statusBeforeGesture
                (statusText?.tag as? TextView)?.text = hintBeforeGesture
            } else refreshSessionUi()
            statusBeforeGesture = null; hintBeforeGesture = null
            return
        }
        val (title, hint) = when (preview) {
            BarGestureRecognizer.Preview.DELETE_UNIT -> com.sprich.app.R.string.ime_delete_unit_preview to com.sprich.app.R.string.ime_delete_release_hint
            BarGestureRecognizer.Preview.DELETE_SENTENCE -> com.sprich.app.R.string.ime_delete_sentence_preview to com.sprich.app.R.string.ime_delete_hold_hint
            BarGestureRecognizer.Preview.REPEAT -> com.sprich.app.R.string.ime_delete_repeat_preview to com.sprich.app.R.string.ime_delete_repeat_hint
            BarGestureRecognizer.Preview.WHISPER -> (if (whisperMode) com.sprich.app.R.string.ime_whisper_off_preview else com.sprich.app.R.string.ime_whisper_on_preview) to com.sprich.app.R.string.ime_switch_release_hint
            BarGestureRecognizer.Preview.HIDE -> com.sprich.app.R.string.ime_hide_preview to com.sprich.app.R.string.ime_hide_hint
            else -> return
        }
        staticMessageIds = null
        val strings = uiResources()
        statusText?.text = strings.getString(title)
        (statusText?.tag as? TextView)?.text = strings.getString(hint)
    }

    private fun toggleWhisperMode(): Boolean {
        if (isPasswordField || isPassword(currentInputEditorInfo) || runtimeConfig == null || whisperChangeJob?.isActive == true) return false
        val target = !whisperMode
        val resume = isDictationRunning()
        if (resume) stopDictation(StopReason.CURSOR_MOVED)
        val field = currentFieldId
        val generation = fieldGeneration.get()
        whisperChangeJob = scope.launch {
            try {
                prefs.setWhisperMode(target)
                runtimeConfigFlow.first { it?.whisperMode == target }
                refreshSessionUi()
                if (resume && currentFieldId == field && fieldGeneration.get() == generation && isInputViewShown) {
                    startJob = scope.launch { startDictationIfNeeded() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { android.widget.Toast.makeText(this@SprichIME, com.sprich.app.R.string.ime_whisper_save_failed, android.widget.Toast.LENGTH_SHORT).show() }
        }
        return true
    }

    private fun openSprichSettings() {
        cancelBarGesture()
        stopDictation(StopReason.WINDOW_HIDDEN)
        requestHideSelf(0)
        val intent = android.content.Intent(this, com.sprich.app.ui.MainActivity::class.java).apply {
            action = com.sprich.app.ui.MainActivity.ACTION_OPEN_SETTINGS
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun dismissKeyboard() {
        cancelBarGesture()
        dismissing = true
        stopVisualLane()
        stopDictation(StopReason.WINDOW_HIDDEN)
        val stamp = ++dismissalGeneration
        val bar = pillViewRef
        if (bar == null || !android.animation.ValueAnimator.areAnimatorsEnabled()) { requestHideSelf(0); return }
        bar.animate().cancel()
        bar.animate().translationY(dp(112).toFloat()).scaleX(0.96f).scaleY(0.72f).alpha(0f)
            .setDuration(180).setInterpolator(android.view.animation.PathInterpolator(0.32f, 0f, 0.8f, 0.4f))
            .withEndAction { if (stamp == dismissalGeneration) requestHideSelf(0) }.start()
    }

    private fun resetBarMotion() {
        dismissing = false
        dismissalGeneration++
        pillViewRef?.let { bar ->
            bar.animate().withEndAction(null).cancel()
            bar.translationY = 0f; bar.alpha = 1f; bar.scaleX = 1f; bar.scaleY = 1f
        }
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
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainThreadHandler.post { updateLiquidVisual(rms) }
            return
        }
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
        return LocalAsrRoute.fromLanguage(speechLang)
    }

    // Strict HTTPS validation — centralized via EndpointValidator (P1 centralization)
    private fun isValidHttpsUrl(url: String): Boolean = com.sprich.app.core.security.EndpointValidator.isValidHttpsUrl(url)

    private fun buildRemoteSttConfig(snap: com.sprich.app.storage.RuntimeConfigSnapshot? = runtimeConfig): RemoteSttConfig? {
        val s = snap ?: return null
        val choice = s.apiChoice(ApiUse.VOICE)
        if (!choice.valid) return null
        return choice.remote(LanguagePolicy.fromSpeechLanguage(s.speechLanguage), s.sttDeadlineMs.coerceIn(1000, 15_000))
    }

    private fun buildRefinementConfig(mode: RefinementMode, snap: com.sprich.app.storage.RuntimeConfigSnapshot? = runtimeConfig): RefinementConfig? {
        val s = snap ?: return null
        if (mode == RefinementMode.OFF) return null
        val choice = s.apiChoice(ApiUse.WRITING)
        if (!choice.valid) return null
        return choice.refinement(s.refinementContextEnabled).copy(mode = mode)
    }

    private fun buildUtterancePlan(s: com.sprich.app.storage.RuntimeConfigSnapshot): UtterancePlan? {
        val localRoute = determineRoute(s.speechLanguage)
        val transcription: TranscriptionPlan = when (s.transcriptionMode) {
            TranscriptionMode.ON_DEVICE -> TranscriptionPlan.Local(localRoute)
            TranscriptionMode.API_PRIMARY -> buildRemoteSttConfig(s)?.let { TranscriptionPlan.ApiPrimary(it, localRoute.takeIf { s.apiLocalFallback && localRouteReady(it) }) } ?: return null
            TranscriptionMode.LOCAL_API_FALLBACK -> buildRemoteSttConfig(s)?.let { TranscriptionPlan.LocalApiFallback(localRoute, it) } ?: return null
        }
        val config = SpeechSessionConfig(language = s.speechLanguage.toLegacyLanguage(), speechLanguage = s.speechLanguage,
            task = TranscriptionTask.TRANSCRIBE, enablePunctuation = true, enableCommands = s.commandsEnabled)
        val vocabulary = vocabStore.snapshot()
        val refinement = buildRefinementPlan(s)
        val context = if (fieldAllowsContext && (refinement as? RefinementPlan.Enabled)?.config?.contextEnabled == true) {
            com.sprich.app.speech.refinement.DictationContext.beforeCursor(editorAuthority?.before)
        } else null
        val hints = if (s.personalVocabHintEnabled) vocabulary.terms().filter { it.length in 1..100 && it.none { c -> c.isISOControl() } }.take(100) else emptyList()
        return UtterancePlan(transcription, refinement, config, vocabulary, context, hints, whisperMode = s.whisperMode)
    }

    private fun localRouteReady(route: LocalAsrRoute): Boolean {
        val manager = com.sprich.app.models.manager.ModelManager(this)
        return when (route) {
            is LocalAsrRoute.AutomaticFastConformer -> manager.isAutomaticReady()
            is LocalAsrRoute.AccurateCanary -> manager.canaryStatus.value is com.sprich.app.models.manager.ModelStatus.Ready
        }
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
        val coord = TranscriptionCoordinator(localCoordinator!!, emptyMap(), apiSecretStore, DeadlinePolicy.DEFAULT)
        transcriptionCoordinator = coord
        return coord
    }

    // P0-13: Refinement must use frozen pending.plan.refinement.config only — never global mutable state nor permanently cached old provider
    private suspend fun providerForRefinementConfig(cfg: com.sprich.app.speech.refinement.RefinementConfig): TranscriptRefinementProvider? {
        val secret = apiSecretStore.loadBoundSecret(cfg.credentialRef, cfg.providerId, cfg.endpoint) ?: return null
        return RefinementProviderFactory.create(cfg, secret, sharedHttpClient)
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
