package com.sprich.app.vocab

import android.content.Context
import android.util.AtomicFile
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.speech.LocalAsrRoute
import com.sprich.app.storage.Preferences
import com.sprich.app.ui.vocab.LearningStep
import com.sprich.app.ui.vocab.WordLearningViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WordLearningFlowTest {
    private lateinit var repo: VocabRepository
    private val owners = ViewModelStore()
    private val profile = RecognitionProfile.local(LocalAsrRoute.AutomaticFastConformer, false)
    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        repo = VocabRepository(context, Preferences(context))
        repo.clear()
    }
    @After fun tearDown() { owners.clear(); Dispatchers.resetMain() }
    private suspend fun model(recorder: LessonRecorder): WordLearningViewModel {
        val model = WordLearningViewModel(repo) { recorder }
        owners.put("lesson", model)
        withTimeout(5000) { model.state.first { it.profile != null } }
        return model
    }

    @Test fun cancelledNativeResultAndLateProgressCannotAddAnAttempt() = runBlocking {
        val answer = CompletableDeferred<String>()
        val returned = CompletableDeferred<Unit>()
        val recorder = object : LessonRecorder {
            override val profile = this@WordLearningFlowTest.profile
            override val usesApi = false
            override suspend fun record(finish: Deferred<Unit>, onProgress: (LearningProgress) -> Unit): String = withContext(NonCancellable) {
                val result = answer.await()
                onProgress(LearningProgress(LearningPhase.RECORDING, preview = "late private words"))
                returned.complete(Unit)
                result
            }
            override suspend fun release() {}
        }
        val vm = model(recorder)
        vm.record()
        vm.pause()
        answer.complete("late private words")
        returned.await()
        yield()
        assertTrue(vm.state.value.samples.isEmpty())
        assertEquals(LearningPhase.IDLE, vm.state.value.progress.phase)
        assertEquals("", vm.state.value.progress.preview)
        assertTrue(repo.learnedWords().isEmpty())
    }

    @Test fun observationsNeedReviewAndExplicitSaveBeforeTheyPersist() = runBlocking {
        val recorder = object : LessonRecorder {
            override val profile = this@WordLearningFlowTest.profile
            override val usesApi = false
            override suspend fun record(finish: Deferred<Unit>, onProgress: (LearningProgress) -> Unit) = "Sprick."
            override suspend fun release() {}
        }
        val vm = model(recorder)
        repeat(3) { vm.record(); withTimeout(5000) { vm.state.first { !it.busy } } }
        assertTrue(repo.learnedWords().isEmpty())
        vm.spell(); vm.setWritten("Sprich"); vm.review()
        assertEquals(LearningStep.REVIEW, vm.state.value.step)
        assertEquals(setOf("sprick"), vm.state.value.selected)
        assertTrue(repo.learnedWords().isEmpty())
        vm.save()
        withTimeout(5000) { vm.state.first { it.step == LearningStep.SAVED && !it.saving } }
        assertEquals(listOf("Sprick.", "Sprick.", "Sprick."), repo.learnedWords().single().samples)
        SharedVocabStore.store.clear()
        repo.load()
        assertEquals("Sprich", repo.store().snapshot().apply("sprick", profile.key))
        repo.removeLearned(repo.learnedWords().single().id)
        repo.load()
        assertEquals("sprick", repo.store().snapshot().apply("sprick", profile.key))
    }

    @Test fun pickedNameIsNormalizedButRemainsAnUnsavedDraft() = runBlocking {
        val recorder = object : LessonRecorder {
            override val profile = this@WordLearningFlowTest.profile
            override val usesApi = false
            override suspend fun record(finish: Deferred<Unit>, onProgress: (LearningProgress) -> Unit) = "Wats app"
            override suspend fun release() {}
        }
        val vm = model(recorder)
        repeat(3) { vm.record(); withTimeout(5000) { vm.state.first { !it.busy } } }
        vm.spell()
        vm.resolvePickedName { "  WhatsApp  " }
        withTimeout(5000) { vm.state.first { it.written == "WhatsApp" && !it.resolvingName } }
        assertTrue(repo.learnedWords().isEmpty())
        assertEquals(LearningStep.SPELL, vm.state.value.step)

        vm.resolvePickedName { "\u0000invalid" }
        withTimeout(5000) { vm.state.first { !it.resolvingName && it.error != null } }
        assertEquals("WhatsApp", vm.state.value.written)
        assertTrue(repo.learnedWords().isEmpty())
    }

    @Test fun simultaneousConflictingSavesDoNotOverwriteTheWinner() = runBlocking {
        fun candidate(written: String) = WordLesson.create(profile, written, List(3) { "acmee" }, setOf("acmee"))
        val results = listOf("Acme", "Other").map { async(Dispatchers.IO) { runCatching { repo.addLearned(candidate(it)) } } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is VocabularyConflictException })
        repo.load()
        assertEquals(1, repo.learnedWords().size)
        repo.clear(); repo.load()
        assertTrue(repo.document().learned.isEmpty())
    }

    @Test fun legacyEntriesAndInterruptedFileReplacementPreserveTheSavedDictionary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = AtomicFile(File(context.noBackupFilesDir, "vocabulary.json"))
        file.delete()
        val legacy = context.getSharedPreferences("sprich_vocab", Context.MODE_PRIVATE)
        legacy.edit().putString("json", """{"entries":[{"spoken":"acme","written":"ACME"}]}""").commit()
        repo.load()
        assertEquals("ACME", repo.apply("acme"))
        repo.addLearned(WordLesson.create(profile, "Sprich", List(3) { "sprick" }, setOf("sprick")))
        assertNull(legacy.getString("json", null))
        // An interrupted replacement never calls finishWrite; the last saved document remains authoritative.
        file.startWrite().use { it.write("{unfinished".toByteArray()) }
        SharedVocabStore.store.clear()
        repo.load()
        assertEquals("ACME Sprich", repo.store().snapshot().apply("acme sprick", profile.key))
        repo.clear(); repo.load()
        assertTrue(repo.entries().isEmpty())
        assertTrue(repo.learnedWords().isEmpty())
    }
}
