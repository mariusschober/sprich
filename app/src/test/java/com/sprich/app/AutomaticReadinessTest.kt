package com.sprich.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.models.manager.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Unit checks for receipt/readiness policy, not native model or device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticReadinessTest {
    private fun manager(): ModelManager = ModelManager(ApplicationProvider.getApplicationContext<Context>())
    private suspend fun receiptFixture(mm: ModelManager, id: String) {
        // Represents the downloader's post-hash, post-extraction receipt boundary.
        // Actual archive trust and native behavior have separate tests.
        val e = mm.getManifest().models.single { it.id == id }
        val dir = mm.directory(id)
        dir.mkdirs()
        e.files.forEach { File(dir, it).writeText("unit receipt fixture $it") }
        mm.writeReceipt(dir, e)
        assertTrue(mm.verifyInstalled(id))
    }
    @Test fun automaticNeedsBothVerifiedInstallations() = runBlocking {
        val mm = manager(); mm.deleteAll()
        assertFalse(mm.isAutomaticReady())
        receiptFixture(mm, "lid")
        assertFalse(mm.isAutomaticReady())
        receiptFixture(mm, "fastconformer")
        assertTrue(mm.isAutomaticReady())
        assertFalse(mm.isCanaryReady())
        mm.deleteLid()
        assertTrue(mm.isFastConformerReady())
        assertFalse(mm.isAutomaticReady())
    }
    @Test fun oldMarkerAndPlausibleSizesAreNotAuthority() = runBlocking {
        val mm = manager(); mm.deleteAll()
        val dir = mm.directory("lid"); dir.mkdirs()
        File(dir, ".installed_ok").writeText("test")
        mm.getManifest().models.single { it.id == "lid" }.files.forEach {
            java.io.RandomAccessFile(File(dir, it), "rw").use { f -> f.setLength(90_000_000) }
        }
        assertFalse(mm.verifyInstalled("lid"))
        mm.setReady("lid")
        assertFalse(mm.isWhisperTinyReady())
    }
    @Test fun modificationInvalidatesReceiptAndReadiness() = runBlocking {
        val mm = manager(); mm.deleteAll(); receiptFixture(mm, "fastconformer")
        File(mm.directory("fastconformer"), "model.int8.onnx").appendText("corrupt")
        assertFalse(mm.isFastConformerReady())
        assertFalse(mm.verifyInstalled("fastconformer"))
    }
    @Test fun interruptedReplacementRestoresVerifiedBackup() = runBlocking {
        val mm = manager(); mm.deleteAll(); receiptFixture(mm, "lid")
        val dir = mm.directory("lid"); val old = File(dir.path + ".old")
        assertTrue(dir.renameTo(old))
        dir.mkdirs(); File(dir, "partial").writeText("incomplete replacement")
        mm.refresh()
        assertTrue(mm.isWhisperTinyReady())
        assertFalse(old.exists())
        assertFalse(File(dir, "partial").exists())
    }
    @Test fun managersShareDownloadAndReadinessState() = runBlocking {
        val first = manager(); first.deleteAll(); val second = manager()
        first.updateDownloadProgress("lid", 0.5f, 50, 100)
        assertEquals(first.lidStatus.value, second.lidStatus.value)
        receiptFixture(first, "lid")
        assertTrue(second.isWhisperTinyReady())
        second.deleteLid()
        assertFalse(first.isWhisperTinyReady())
    }
    @Test fun statusRequiresBothModelsReady() {
        val mm = manager()
        assertTrue(mm.isAutomaticReadyStatus(ModelStatus.Ready, ModelStatus.Ready))
        assertFalse(mm.isAutomaticReadyStatus(ModelStatus.Ready, ModelStatus.Downloading(0.5f, 50, 100)))
        assertFalse(mm.isAutomaticReadyStatus(ModelStatus.Verifying, ModelStatus.Ready))
        assertFalse(mm.isAutomaticReadyStatus(ModelStatus.Ready, ModelStatus.NotDownloaded))
    }
}
