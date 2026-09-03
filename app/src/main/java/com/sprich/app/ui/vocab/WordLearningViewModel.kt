package com.sprich.app.ui.vocab

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sprich.app.R
import com.sprich.app.api.ApiException
import com.sprich.app.api.MicrophoneUnavailableException
import com.sprich.app.speech.remote.ApiFailure
import com.sprich.app.storage.Preferences
import com.sprich.app.vocab.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class LearningStep { RECORD, SPELL, REVIEW, SAVED }
data class WordLearningState(
    val profile: RecognitionProfile? = null,
    val usesApi: Boolean = false,
    val step: LearningStep = LearningStep.RECORD,
    val progress: LearningProgress = LearningProgress(LearningPhase.IDLE),
    val samples: List<String> = emptyList(),
    val written: String = "",
    val selected: Set<String> = emptySet(),
    val error: Int? = null,
    val saving: Boolean = false,
    val existing: VocabJson = VocabJson(),
) {
    val busy get() = progress.phase != LearningPhase.IDLE || saving
    override fun toString() = "WordLearningState(step=$step, samples=${samples.size}, busy=$busy)"
}

/** Draft text lives only here. It is never put in saved state or diagnostics. */
class WordLearningViewModel(
    private val repository: VocabRepository,
    private val createRecorder: suspend () -> LessonRecorder,
) : ViewModel() {
    constructor(context: Context) : this(VocabRepository(context, Preferences(context)), {
        WordLearningRecorder(context, Preferences(context).runtimeConfigSnapshot.first())
    })

    private val mutable = MutableStateFlow(WordLearningState())
    val state = mutable.asStateFlow()
    private var recorder: LessonRecorder? = null
    private var attempt: Job? = null
    private var finish = CompletableDeferred<Unit>()
    @Volatile private var generation = 0L

    init {
        viewModelScope.launch {
            try {
                repository.load()
                val initialized = createRecorder()
                recorder = initialized
                mutable.update { it.copy(profile = initialized.profile, usesApi = initialized.usesApi, existing = repository.document()) }
                repository.changes().collect {
                    val latest = repository.document()
                    mutable.update { draft ->
                        val selected = draft.selected.filterNot { latest.conflicts(it, draft.written, draft.profile?.key) }.toSet()
                        draft.copy(existing = latest, selected = selected,
                            error = if (selected != draft.selected) R.string.learn_conflict else draft.error)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = R.string.learn_setup_failed) } }
        }
    }

    fun record() {
        val owner = recorder ?: return
        if (state.value.busy || state.value.step != LearningStep.RECORD || state.value.samples.size >= WordLesson.MAX_SAMPLES) return
        val token = ++generation
        finish = CompletableDeferred()
        mutable.update { it.copy(error = null, progress = LearningProgress(LearningPhase.PREPARING)) }
        attempt = viewModelScope.launch {
            try {
                val text = owner.record(finish) { progress ->
                    mutable.update {
                        if (generation != token || progress.phase.ordinal < it.progress.phase.ordinal) it else {
                            val combined = if (progress.phase == LearningPhase.RECORDING && it.progress.phase == LearningPhase.RECORDING)
                                progress.copy(fraction = maxOf(progress.fraction, it.progress.fraction), preview = progress.preview.ifEmpty { it.progress.preview })
                            else progress
                            it.copy(progress = combined)
                        }
                    }
                }
                ensureActive()
                if (token == generation && VocabularyText.validSample(text)) mutable.update { it.copy(samples = it.samples + text) }
            } catch (_: TimeoutCancellationException) {
                if (token == generation) mutable.update { it.copy(error = R.string.api_timeout) }
            } catch (cancelled: CancellationException) {
                if (token == generation) mutable.update { it.copy(error = R.string.learn_interrupted) }
            } catch (error: Exception) {
                if (token == generation) mutable.update { it.copy(error = message(error)) }
            } finally {
                if (token == generation) mutable.update { it.copy(progress = LearningProgress(LearningPhase.IDLE)) }
            }
        }
    }

    fun finishSpeaking() { finish.complete(Unit) }
    fun cancelAttempt(interrupted: Boolean = false) {
        val wasBusy = state.value.progress.phase != LearningPhase.IDLE
        ++generation // Revoke UI authority before cancellation can deliver a late native result.
        attempt?.cancel()
        mutable.update { it.copy(progress = LearningProgress(LearningPhase.IDLE), error = if (interrupted && wasBusy) R.string.learn_interrupted else null) }
        viewModelScope.launch { recorder?.release() }
    }
    fun pause() = cancelAttempt(interrupted = true)
    fun removeSample(index: Int) {
        if (!state.value.busy) mutable.update { it.copy(samples = it.samples.filterIndexed { i, _ -> i != index }) }
    }
    fun spell() {
        if (state.value.busy || state.value.samples.size < WordLesson.MIN_SAMPLES) return
        mutable.update { it.copy(step = LearningStep.SPELL, error = null) }
        viewModelScope.launch { recorder?.release() }
    }
    fun setWritten(text: String) { if (text.length <= 128 && !state.value.busy) mutable.update { it.copy(written = text, error = null) } }
    fun review() {
        val current = state.value
        if (current.busy || !VocabularyText.validTerm(VocabularyText.clean(current.written))) return
        mutable.update { it.copy(step = LearningStep.REVIEW, written = VocabularyText.clean(it.written),
            selected = WordLesson.suggested(it.samples, it.written).filterNot { key ->
                it.existing.conflicts(key, it.written, it.profile?.key)
            }.toSet(), error = null) }
    }
    fun select(key: String) {
        if (state.value.busy) return
        mutable.update { it.copy(selected = if (key in it.selected) it.selected - key else it.selected + key, error = null) }
    }
    fun previous(): Boolean {
        if (state.value.saving) return true
        cancelAttempt()
        return when (state.value.step) {
            LearningStep.REVIEW -> { mutable.update { it.copy(step = LearningStep.SPELL) }; true }
            LearningStep.SPELL -> { mutable.update { it.copy(step = LearningStep.RECORD) }; true }
            else -> false
        }
    }
    fun save() {
        val draft = state.value
        if (draft.busy || draft.step != LearningStep.REVIEW) return
        val profile = draft.profile ?: return
        mutable.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                repository.addLearned(WordLesson.create(profile, draft.written, draft.samples, draft.selected))
                mutable.update { it.copy(step = LearningStep.SAVED, samples = emptyList(), selected = emptySet()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: VocabularyConflictException) { mutable.update { it.copy(error = R.string.learn_conflict) } }
            catch (e: WordAlreadyLearnedException) { mutable.update { it.copy(error = R.string.learn_already_saved) } }
            catch (_: Exception) { mutable.update { it.copy(error = R.string.vocab_save_failed) } }
            finally { mutable.update { it.copy(saving = false, existing = repository.document()) } }
        }
    }
    override fun onCleared() {
        ++generation
        attempt?.cancel()
        val owner = recorder
        // Finite cleanup survives the ViewModel's cancellation; the recorder serializes it with decoding.
        CoroutineScope(Dispatchers.IO).launch { owner?.release() }
        super.onCleared()
    }

    private fun message(error: Exception): Int = when (error) {
        is LearningModelsUnavailable -> R.string.learn_models_missing
        is LearningNoWord -> R.string.learn_no_word
        is MicrophoneUnavailableException -> R.string.ime_mic_unavailable
        is ApiException -> when (error.failure) {
            ApiFailure.Authentication -> R.string.api_auth_error
            ApiFailure.RateLimited -> R.string.api_rate_error
            ApiFailure.ModelUnavailable -> R.string.api_model_error
            ApiFailure.Offline -> R.string.api_offline
            ApiFailure.Timeout -> R.string.api_timeout
            ApiFailure.InvalidResponse -> R.string.learn_no_word
            else -> R.string.api_unavailable
        }
        else -> R.string.learn_recognition_failed
    }
}
