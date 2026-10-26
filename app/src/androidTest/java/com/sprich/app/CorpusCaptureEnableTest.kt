package com.sprich.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sprich.app.storage.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Debug-only switch for human-corpus recording (docs/CORPUS_RECORDING.md).
 *
 * The release build has no audio export by design. Debug builds save the exact frozen PCM of
 * each utterance to the app's no-backup directory when this preference is on
 * (ReplayHarness.saveWavIfEnabled). The preference has no production UI, so this instrumented
 * one-shot is the supported way for the maintainer to enable/disable capture. It writes no
 * dictation data and performs no recognition.
 */
@RunWith(AndroidJUnit4::class)
class CorpusCaptureEnableTest {

    private fun app() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp

    @Test
    fun enableDebugWavCapture() = runBlocking {
        val prefs = Preferences(app())
        prefs.setDebugWavCapture(true)
        assertTrue("debug WAV capture should be enabled", prefs.debugWavCapture.first())
    }

    @Test
    fun disableDebugWavCapture() = runBlocking {
        val prefs = Preferences(app())
        prefs.setDebugWavCapture(false)
        assertTrue("debug WAV capture should be disabled", !prefs.debugWavCapture.first())
    }
}
