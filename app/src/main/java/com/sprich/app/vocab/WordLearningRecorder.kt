package com.sprich.app.vocab

import android.content.Context
import com.sprich.app.api.*
import com.sprich.app.core.audio.AudioCapture
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.speech.*
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.canary.CanaryEngine
import com.sprich.app.speech.fastconformer.FastConformerEngine
import com.sprich.app.speech.lid.WhisperLidEngine
import com.sprich.app.speech.remote.*
import com.sprich.app.storage.ApiSecretStore
import com.sprich.app.storage.RuntimeConfigSnapshot
import com.sprich.app.storage.enforceProviderAvailability
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

class LearningModelsUnavailable : Exception("Download the selected voice mode first")
class LearningNoWord : Exception("No usable word was heard")

enum class LearningPhase { IDLE, PREPARING, RECORDING, RECOGNIZING }
data class LearningProgress(val phase: LearningPhase, val fraction: Float = 0f, val preview: String = "") {
    override fun toString() = "LearningProgress(phase=$phase)"
}

interface LessonRecorder {
    val profile: RecognitionProfile
    val usesApi: Boolean
    suspend fun record(finish: Deferred<Unit>, onProgress: (LearningProgress) -> Unit): String
    suspend fun release()
}

/** Independent native and capture ownership; no editor, postprocessor or cleanup provider. */
class WordLearningRecorder(context: Context, snapshot: RuntimeConfigSnapshot) : LessonRecorder {
    private val context = context.applicationContext
    private val config = snapshot.enforceProviderAvailability()
    private val localRoute = LocalAsrRoute.fromLanguage(config.speechLanguage)
    private val remote = if (config.transcriptionMode == TranscriptionMode.API_PRIMARY)
        config.apiChoice(ApiUse.VOICE).remote(LanguagePolicy.fromSpeechLanguage(config.speechLanguage),
            config.sttDeadlineMs.coerceIn(1000, 15_000)).let { it.copy(options = it.options.copy(speakerLabels = false)) }
        else null
    private val epoch = ApiHttp.currentEpoch
    private val speech = SpeechSessionConfig(language = config.speechLanguage.toLegacyLanguage(),
        speechLanguage = config.speechLanguage, enableCommands = false)
    override val profile = remote?.let { RecognitionProfile.remote(it, config.whisperMode) }
        ?: RecognitionProfile.local(localRoute, config.whisperMode)
    override val usesApi = remote != null
    private val mutex = Mutex()
    private var engines: LocalEngines? = null

    override suspend fun record(finish: Deferred<Unit>, onProgress: (LearningProgress) -> Unit): String = mutex.withLock {
        coroutineScope {
            onProgress(LearningProgress(LearningPhase.PREPARING))
            val api = remote
            val key = if (api != null) {
                ensurePermission()
                ApiSecretStore(context).loadBoundSecret(api.credentialRef, api.providerId, api.endpoint)
                    ?: throw ApiException(ApiFailure.Authentication)
            } else {
                val manager = ModelManager(context)
                val ready = when (localRoute) {
                    is LocalAsrRoute.AutomaticFastConformer -> manager.verifyInstalled("lid") && manager.verifyInstalled("fastconformer")
                    is LocalAsrRoute.AccurateCanary -> manager.verifyInstalled("accurate")
                }
                if (!ready) throw LearningModelsUnavailable()
                null
            }
            val id = System.nanoTime()
            val recording = AtomicBoolean(true)
            val live = if (api?.preferStreaming == true) LiveRemoteUtterance(this, api, epoch, id, emptyList(),
                credential = { checkNotNull(key) }, onProgress = { update ->
                    if (recording.get()) onProgress(LearningProgress(LearningPhase.RECORDING, preview = update.preview.take(256)))
                }) else null
            var pcm: ShortArray? = null
            try {
                pcm = capture(finish, onProgress) { chunk, offset, count -> live?.offerAudio(chunk, offset, count) }
                recording.set(false)
                onProgress(LearningProgress(LearningPhase.RECOGNIZING))
                val frozen = checkNotNull(pcm)
                val text = if (api != null) withTimeout(api.deadlineMs) {
                    ensurePermission()
                    val result = if (live != null) live.finish(frozen) else withContext(Dispatchers.IO) {
                        RemoteProviderFactory.create(api).transcribe(RemoteSttRequest(frozen, 16000, api.languagePolicy,
                            utteranceId = id, credential = checkNotNull(key), options = api.options,
                            isAuthorized = { epoch == ApiHttp.currentEpoch }))
                    }
                    ensurePermission()
                    result.text
                } else {
                    val local = engines ?: LocalEngines(context).also { engines = it }
                    local.coordinator.transcribe(frozen, localRoute, speech).text
                }
                ensureActive()
                if (!VocabularyText.validSample(text)) throw LearningNoWord()
                text
            } finally {
                recording.set(false)
                live?.cancel()
                pcm?.fill(0)
            }
        }
    }

    private fun ensurePermission() {
        if (epoch != ApiHttp.currentEpoch) throw CancellationException("Voice API permission changed")
    }

    private suspend fun capture(finish: Deferred<Unit>, onProgress: (LearningProgress) -> Unit,
        onAudio: (ShortArray, Int, Int) -> Unit): ShortArray = coroutineScope {
        val capture = AudioCapture()
        val buffer = ShortArray(4 * 16000)
        val ready = CompletableDeferred<Int>()
        val finishRequested = AtomicBoolean(false)
        val finishJob = launch { finish.await(); finishRequested.set(true) }
        val bufferLock = Any()
        var accepting = true
        var size = 0 // The recorder thread alone writes the buffer and size.
        try {
            val started = capture.startWithOffset({ samples, offset, count, _, _ ->
                synchronized(bufferLock) { if (accepting && !ready.isCompleted) {
                    val n = minOf(count, buffer.size - size)
                    samples.copyInto(buffer, size, offset, offset + n)
                    size += n
                    onAudio(samples, offset, n)
                    onProgress(LearningProgress(LearningPhase.RECORDING, size.toFloat() / buffer.size))
                    if (size == buffer.size || (finishRequested.get() && size >= 9600)) ready.complete(size)
                } }
            }, { ready.completeExceptionally(MicrophoneUnavailableException()) }, whisperMode = config.whisperMode)
            if (!started) throw MicrophoneUnavailableException()
            onProgress(LearningProgress(LearningPhase.RECORDING))
            val count = withTimeout(6000) { ready.await() }
            buffer.copyOf(count)
        } catch (_: SecurityException) {
            throw MicrophoneUnavailableException()
        } finally {
            finishJob.cancel()
            synchronized(bufferLock) { accepting = false }
            val handle = capture.requestStop()
            withContext(NonCancellable + Dispatchers.IO) { handle?.awaitStop(); buffer.fill(0) }
        }
    }

    override suspend fun release(): Unit = mutex.withLock {
        val old = engines
        engines = null
        withContext(NonCancellable + Dispatchers.IO) { old?.release(); Unit }
    }

    private class LocalEngines(context: Context) {
        private val manager = ModelManager(context)
        private val lid = WhisperLidEngine(context, manager)
        private val fast = FastConformerEngine(context)
        private val accurate = CanaryEngine(context, manager)
        val coordinator = LocalTranscriptionCoordinator(lid, fast, accurate)
        suspend fun release() {
            try { lid.unload() } finally { try { fast.unload() } finally { accurate.unload() } }
        }
    }
}
