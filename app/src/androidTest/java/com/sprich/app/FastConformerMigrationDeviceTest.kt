package com.sprich.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sprich.app.models.manager.BuiltinManifest
import com.sprich.app.models.manager.ModelManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T3 migration gate: the obsolete NVIDIA NGC-licensed FastConformer files from a pre-1.0
 * installation are deleted only after the license-clean replacement (Whisper Tiny language ID +
 * Canary 180M Flash) verifies from disk. Never before.
 */
@RunWith(AndroidJUnit4::class)
class FastConformerMigrationDeviceTest {

    private fun app() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp

    @Test
    fun obsoleteFilesSurviveWhenReplacementCannotVerify() = runBlocking {
        val app = app()
        val obsolete = File(app.filesDir, "fastconformer")
        val wasPresent = obsolete.exists()
        if (!wasPresent) {
            obsolete.mkdirs()
            File(obsolete, "migration-test-marker").writeText("pre-1.0 leftovers")
        }
        try {
            // A manager whose LID entry can never verify (wrong file list) models an install where
            // the replacement is incomplete. The old files must be preserved.
            val broken = BuiltinManifest.default().let { m ->
                m.copy(models = m.models.map { entry ->
                    if (entry.id == "lid") entry.copy(files = listOf("missing.onnx"), sha256 = "0".repeat(64)) else entry
                })
            }
            val mm = ModelManager(app, broken)
            mm.cleanupObsoleteFastConformer()
            assertTrue("obsolete files must be preserved while the replacement is unverified", obsolete.exists())
        } finally {
            if (!wasPresent) obsolete.deleteRecursively()
        }
    }

    @Test
    fun upgradeDeletesObsoleteFilesAfterReplacementVerifies() = runBlocking {
        val app = app()
        val mm = ModelManager(app)
        val replacementReady = mm.verifyInstalled("lid") && mm.verifyInstalled("accurate")
        assumeTrue("replacement (LID+Canary) not yet provisioned", replacementReady)
        val obsolete = File(app.filesDir, "fastconformer")
        obsolete.mkdirs()
        File(obsolete, "migration-test-marker").writeText("pre-1.0 leftovers")
        assertTrue("precondition: replacement verified", mm.isWhisperTinyReady() && mm.isCanaryReady())
        mm.cleanupObsoleteFastConformer()
        assertFalse("obsolete FastConformer files must be deleted after replacement verifies", obsolete.exists())
        assertTrue("replacement must remain ready", mm.isWhisperTinyReady() && mm.isCanaryReady())
    }
}
