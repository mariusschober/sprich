package com.sprich.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.canary.CanaryEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CanaryFailClosedTest {
    @Test fun missingRecognizerFailsWithoutClaimingNativeWork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (tag in listOf("en", "de")) {
            val engine = CanaryEngine(context, ModelManager(context))
            val result = runCatching { engine.transcribeSnapshot(ShortArray(16000) { 2000 }, SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed(tag))) }
            assertTrue("Missing native engine must fail", result.isFailure)
            assertEquals(0, engine.nativeDecodeStarts)
            assertEquals(0, engine.nativeDecodeMaxConcurrency)
            engine.unload()
        }
    }
    @Test fun emptyAudioNeverStartsNativeWork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = CanaryEngine(context, ModelManager(context))
        assertEquals("", engine.transcribeSnapshot(ShortArray(0), SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("en"))).text)
        assertEquals(0, engine.nativeDecodeStarts)
        engine.unload()
    }
}
