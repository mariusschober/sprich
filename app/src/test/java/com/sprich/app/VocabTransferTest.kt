package com.sprich.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.speech.LocalAsrRoute
import com.sprich.app.storage.Preferences
import com.sprich.app.ui.vocab.MAX_VOCAB_IMPORT_BYTES
import com.sprich.app.ui.vocab.readVocabularyImport
import com.sprich.app.vocab.LearnedWord
import com.sprich.app.vocab.RecognitionProfile
import com.sprich.app.vocab.VocabRepository
import com.sprich.app.vocab.WordLesson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VocabTransferTest {
    private fun repo(): VocabRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.noBackupFilesDir, "vocabulary.json").delete()
        File(context.noBackupFilesDir, "vocabulary.json.bak").delete()
        context.getSharedPreferences("sprich_vocab", Context.MODE_PRIVATE).edit().clear().commit()
        return VocabRepository(context, Preferences(context))
    }

    @Test fun exportImportRoundTripKeepsEntriesAndLessons() = runBlocking {
        val repo = repo()
        repo.add("sprich", "Sprich")
        val profile = RecognitionProfile.local(LocalAsrRoute.AutomaticFastConformer, false)
        val lesson = WordLesson.create(profile, "Marius", List(3) { "maris" }, setOf("maris"))
        repo.addLearned(lesson)
        val exported = repo.exportJson()

        repo.clear()
        assertTrue(repo.entries().isEmpty())
        val summary = repo.importJson(exported)

        assertEquals(2, summary.imported)
        assertEquals(0, summary.skipped)
        assertEquals("Sprich", repo.apply("sprich"))
        assertEquals("Marius", repo.store().snapshot().apply("maris", profile.key))
    }

    @Test fun malformedOrOversizedImportDoesNotTouchTheDictionary() = runBlocking {
        val repo = repo()
        repo.add("sprich", "Sprich")
        val before = repo.exportJson()
        try {
            repo.importJson("{not valid")
            throw AssertionError("expected rejection")
        } catch (_: IllegalArgumentException) { }
        assertEquals(before, repo.exportJson())
        try {
            ByteArrayInputStream(ByteArray(MAX_VOCAB_IMPORT_BYTES + 1)).use { readVocabularyImport(it) }
            throw AssertionError("expected rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun conflictingEntriesAreSkippedAndExistingSpellingWins() = runBlocking {
        val repo = repo()
        repo.add("acme", "ACME")
        val imported = """{"version":1,"entries":[{"spoken":"acme","written":"Other"},{"spoken":"new","written":"New"}],"learned":[]}"""
        val summary = repo.importJson(imported)
        assertEquals(1, summary.imported)
        assertEquals(1, summary.skipped)
        assertEquals("ACME", repo.apply("acme"))
        assertEquals("New", repo.apply("new"))
    }
}
