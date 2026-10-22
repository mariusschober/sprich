package com.sprich.app.vocab

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.storage.Preferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VocabRepositoryCorruptionTest {
    private fun context(): Context = ApplicationProvider.getApplicationContext()
    private fun clearDisk(context: Context) {
        val base = File(context.noBackupFilesDir, "vocabulary.json")
        base.delete()
        File(base.path + ".bak").delete()
        context.noBackupFilesDir.listFiles { file -> file.name.startsWith("vocabulary.corrupt.") }?.forEach { it.delete() }
        context.getSharedPreferences("sprich_vocab", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun corruptFileIsPreservedBeforeANewListIsShown() = runBlocking {
        val context = context()
        clearDisk(context)
        val broken = "{\"entries\":[{\"spoken\":\"acme\""
        File(context.noBackupFilesDir, "vocabulary.json").apply {
            parentFile!!.mkdirs()
            writeText(broken)
        }
        val repo = VocabRepository(context, Preferences(context))

        repo.load()

        val notice = repo.corruption()
        assertNotNull(notice)
        assertNotNull(notice!!.preservedFileName)
        assertTrue(repo.entries().isEmpty())
        val preserved = File(context.noBackupFilesDir, notice.preservedFileName!!)
        assertTrue(preserved.isFile)
        assertEquals(broken, preserved.readText())
        assertFalse(File(context.noBackupFilesDir, "vocabulary.json").exists())

        repo.add("acme", "ACME")
        assertNull(repo.corruption())
        assertEquals("ACME", repo.apply("acme"))
        assertTrue(preserved.isFile)
    }
}
