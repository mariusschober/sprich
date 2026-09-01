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

/**
 * Phase 7 — Auto readiness must require BOTH Tiny LID and FastConformer.
 * Neither installed, LID only, Fast only, both — verifies fail-closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticReadinessTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()
    private fun clean() {
        val c = ctx()
        File(c.filesDir, "whisper-tiny").deleteRecursively()
        File(c.filesDir, "fastconformer").deleteRecursively()
        File(c.filesDir, "canary").deleteRecursively()
    }

    private fun installLid(c: Context) {
        val dir = File(c.filesDir, "whisper-tiny")
        dir.mkdirs()
        File(dir, "tiny-encoder.int8.onnx").writeBytes(ByteArray(6_000_000))
        File(dir, "tiny-decoder.int8.onnx").writeBytes(ByteArray(51_000_000))
        File(dir, "tiny-tokens.txt").writeText("a b")
    }

    private fun installFast(c: Context) {
        val dir = File(c.filesDir, "fastconformer")
        dir.mkdirs()
        File(dir, "model.int8.onnx").writeBytes(ByteArray(51_000_000))
        File(dir, "tokens.txt").writeText("a b")
    }

    @Test
    fun neitherInstalled_notReady() {
        clean()
        val mm = ModelManager(ctx())
        assertFalse(mm.isWhisperTinyReady())
        assertFalse(mm.isFastConformerReady())
        assertFalse(mm.isAutomaticReady())
        assertFalse(mm.isAutomaticReadyStatus(ModelStatus.NotDownloaded, ModelStatus.NotDownloaded))
    }

    @Test
    fun lidOnly_notReady() {
        clean()
        val c = ctx()
        installLid(c)
        val mm = ModelManager(c)
        assertTrue(mm.isWhisperTinyReady())
        assertFalse(mm.isFastConformerReady())
        assertFalse(mm.isAutomaticReady())
        assertFalse(mm.isAutomaticReadyStatus(ModelStatus.Ready, ModelStatus.NotDownloaded))
        clean()
    }

    @Test
    fun fastOnly_notReady() {
        clean()
        val c = ctx()
        installFast(c)
        val mm = ModelManager(c)
        assertFalse(mm.isWhisperTinyReady())
        assertTrue(mm.isFastConformerReady())
        assertFalse(mm.isAutomaticReady())
        assertFalse(mm.isAutomaticReadyStatus(ModelStatus.NotDownloaded, ModelStatus.Ready))
        clean()
    }

    @Test
    fun bothInstalled_ready() {
        clean()
        val c = ctx()
        installLid(c)
        installFast(c)
        val mm = ModelManager(c)
        assertTrue(mm.isWhisperTinyReady())
        assertTrue(mm.isFastConformerReady())
        assertTrue(mm.isAutomaticReady())
        assertTrue(mm.isAutomaticReadyStatus(ModelStatus.Ready, ModelStatus.Ready))
        clean()
    }

    @Test
    fun canaryNotRequiredForAutomatic() {
        clean()
        val c = ctx()
        installLid(c)
        installFast(c)
        val mm = ModelManager(c)
        assertTrue(mm.isAutomaticReady())
        assertFalse(mm.isCanaryReady())
        // Automatic must be ready even though Canary absent — hidden dependency removed
        clean()
    }

    @Test
    fun statusFlows_agree() {
        val mm = ModelManager(ctx())
        // lid Ready + fast Ready => auto Ready, otherwise not — single derived concept
        assertTrue(mm.isAutomaticReadyStatus(ModelStatus.Ready, ModelStatus.Ready))
        assertFalse(mm.isAutomaticReadyStatus(ModelStatus.Ready, ModelStatus.Downloading(0.5f, 0, 100)))
        assertFalse(mm.isAutomaticReadyStatus(ModelStatus.Downloading(0.5f, 0, 100), ModelStatus.Ready))
        assertFalse(mm.isAutomaticReadyStatus(ModelStatus.Verifying, ModelStatus.Ready))
    }
}
