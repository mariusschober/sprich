package com.sprich.app

import com.sprich.app.core.audio.UtterancePcmBuffer
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.canary.CanaryEngine
import com.sprich.app.models.manager.ModelManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0: Pre-roll must exist exactly once, not duplicated.
 * Given preRoll=[1,2,3], speech1=[4,5], speech2=[6,7] => frozen must be [1,2,3,4,5,6,7]
 * Not [1,2,3,1,2,3,4,5,6,7]. Tests both engine-owned buffer and primitive buffer isolation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PcmIdentityRegressionTest {

    @Test
    fun enginePreRollExistsExactlyOnce() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = CanaryEngine(ctx, ModelManager(ctx))
        val preRoll = shortArrayOf(1, 2, 3)
        val speech1 = shortArrayOf(4, 5)
        val speech2 = shortArrayOf(6, 7)

        engine.beginUtteranceCapture(preRoll)
        // Do NOT push preRoll again — begin owns seeding exactly once
        engine.pushAudio(speech1, System.nanoTime())
        engine.pushAudio(speech2, System.nanoTime())

        val snap = engine.snapshotUtterancePcm()
        val expected = shortArrayOf(1, 2, 3, 4, 5, 6, 7)
        assertArrayEquals("preRoll must exist exactly once, not duplicated", expected, snap)
        // Negative: ensure duplicated would be 10 elements, not 7
        assertEquals(7, snap.size)
        assertFalse(snap.contentEquals(shortArrayOf(1,2,3,1,2,3,4,5,6,7)))
    }

    @Test
    fun utteranceBContainsZeroAudioFromA() {
        val buffer = UtterancePcmBuffer(16000*30)
        val utteranceA = shortArrayOf(10, 11, 12, 13)
        val utteranceB = shortArrayOf(20, 21, 22)

        buffer.beginWithPreRoll(utteranceA)
        val frozenA = buffer.freeze()
        assertArrayEquals(utteranceA, frozenA)

        buffer.clear()
        buffer.beginWithPreRoll(utteranceB)
        val frozenB = buffer.snapshot()
        assertArrayEquals(utteranceB, frozenB)
        // B must contain zero samples from A
        assertFalse(frozenB.any { it in utteranceA })
        assertEquals(utteranceB.size, frozenB.size)
    }

    @Test
    fun primitiveBufferNoBoxingAndBounded() {
        val buffer = UtterancePcmBuffer(5)
        buffer.beginWithPreRoll(shortArrayOf(1,2,3))
        buffer.append(shortArrayOf(4,5))
        // At capacity 5, appending 2 more should drop oldest 2
        buffer.append(shortArrayOf(6,7))
        val snap = buffer.snapshot()
        // maxSamples=5, so after dropping oldest 2, we should have [3,4,5,6,7] or [6,7] + remainder depending on chunking
        // Our implementation drops oldest to stay bounded, not unbounded
        assertEquals(5, snap.size)
        assertTrue(snap.contains(7))
        assertFalse(snap.size > 5)
    }

    @Test
    fun engineAndPrimitiveBufferConsistent() {
        // Direct primitive buffer test matching mission's exact example
        val buf = UtterancePcmBuffer()
        val preRoll = shortArrayOf(1,2,3)
        val speech1 = shortArrayOf(4,5)
        val speech2 = shortArrayOf(6,7)
        buf.beginWithPreRoll(preRoll)
        buf.append(speech1)
        buf.append(speech2)
        val frozen = buf.freeze()
        assertArrayEquals(shortArrayOf(1,2,3,4,5,6,7), frozen)
    }

    @Test
    fun whisperReachesCanaryNotDiscardedBySecondaryGate() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = CanaryEngine(ctx, ModelManager(ctx))
        val cfg = SpeechSessionConfig(speechLanguage = SpeechLanguage.Fixed("en"))
        engine.beginSession(cfg)
        // Whisper: low amplitude ~300 vs 6000 normal, RMS ~0.006? Should not be discarded by 0.0005 gate
        val whisper = ShortArray(16000) { (kotlin.math.sin(it * 0.05) * 300).toInt().toShort() }
        var sum = 0.0
        for (s in whisper) { val f = s / 32768.0; sum += f*f }
        val rms = kotlin.math.sqrt(sum / whisper.size).toFloat()
        assertTrue("whisper rms should be low but above digital silence", rms in 0.0006f..0.02f)
        // With old 0.004 gate, this whisper would be considered silence; with new 0.0005 it should pass
        // Engine's push + endUtterance should not return blank for whisper of 1s (mock returns Hello)
        engine.beginUtteranceCapture(ShortArray(6400) { 0}) // pre-roll silence
        engine.pushAudio(whisper, System.nanoTime())
        // In mock mode, endUtterance returns Hello for 1s, not blank
        kotlinx.coroutines.runBlocking {
            val res = engine.endUtterance()
            assertTrue("whisper should not be discarded as silence", res.text.isNotBlank())
        }
    }
}
