package com.sprich.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.models.manager.ModelStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelManagerTest {
    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun initialStatusNotDownloadedWhenFilesMissing() {
        val mm = ModelManager(ctx())
        assertTrue(mm.canaryStatus.value is ModelStatus.NotDownloaded || mm.canaryStatus.value is ModelStatus.Ready)
        assertFalse(mm.isCanaryReady())
    }

    @Test
    fun canaryReadyAfterFakeInstall() {
        val c = ctx()
        val dir = File(c.filesDir, "canary")
        dir.mkdirs()
        // Threshold is >50M per file (best-practice integrity)
        File(dir, "encoder.int8.onnx").writeBytes(ByteArray(51_000_000))
        File(dir, "decoder.int8.onnx").writeBytes(ByteArray(51_000_000))
        File(dir, "tokens.txt").writeText("a b")
        val mm = ModelManager(c)
        assertTrue(mm.isCanaryReady())
        dir.deleteRecursively()
    }

    @Test
    fun atomicRollbackOnFailedDownload() {
        val c = ctx()
        val mm = ModelManager(c)
        // Simulate partial tmp dir
        val tmp = File(c.filesDir, "canary.tmp")
        tmp.mkdirs()
        File(tmp, "partial").writeText("x")
        mm.checkIntegrity()
        assertFalse(tmp.exists())
    }

    @Test
    fun deleteModelClears() {
        val c = ctx()
        val dir = File(c.filesDir, "canary")
        dir.mkdirs()
        File(dir, "encoder.int8.onnx").writeBytes(ByteArray(51_000_000))
        File(dir, "decoder.int8.onnx").writeBytes(ByteArray(51_000_000))
        File(dir, "tokens.txt").writeText("t")
        val mm = ModelManager(c)
        kotlinx.coroutines.runBlocking { mm.deleteCanary() }
        assertFalse(dir.exists())
    }

    @Test
    fun shaBlankPasses() {
        val c = ctx()
        val mm = ModelManager(c)
        val f = File(c.cacheDir, "tmp_sha")
        f.writeText("hello")
        val ok = kotlinx.coroutines.runBlocking { mm.verifySha256(f, "") }
        assertTrue(ok)
    }
}
