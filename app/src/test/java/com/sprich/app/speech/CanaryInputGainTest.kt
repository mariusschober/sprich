package com.sprich.app.speech

import com.sprich.app.speech.canary.CanaryInputGain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R1 regression: quiet FLEURS-level speech must be conditioned before Canary decoding. */
class CanaryInputGainTest {

    private fun tone(amplitude: Int, samples: Int = 1600): ShortArray =
        ShortArray(samples) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }

    private fun rms(pcm: ShortArray): Double {
        var sum = 0.0
        for (s in pcm) { val f = s / 32768.0; sum += f * f }
        return kotlin.math.sqrt(sum / pcm.size)
    }

    @Test
    fun quietSpeechIsAmplifiedToTarget() {
        // +/-14 LSB is the quiet FLEURS level that blanked before the fix.
        val quiet = tone(14)
        val normalized = CanaryInputGain.normalize(quiet)
        // Target is 0.05 but gain is bounded at 40x: the quietest public-corpus level reaches
        // ~0.017, which was already sufficient to decode correctly (diagnostic gain10x).
        assertTrue("rms ${rms(normalized)} should be raised", rms(normalized) > 0.01)
        assertTrue(rms(normalized) <= CanaryInputGain.TARGET_RMS * 1.01)
    }

    @Test
    fun normalAndLoudInputPassThroughUnchanged() {
        val normal = tone(2000)
        assertArrayEquals(normal, CanaryInputGain.normalize(normal))
        assertArrayEquals(tone(32000), CanaryInputGain.normalize(tone(32000)))
    }

    @Test
    fun nearSilenceIsNotAmplified() {
        assertEquals(1.0, CanaryInputGain.gainFor(0.0), 0.0)
        assertEquals(1.0, CanaryInputGain.gainFor(0.00005), 0.0)
        assertEquals(1.0, CanaryInputGain.gainFor(0.0001), 0.0)
    }

    @Test
    fun gainIsBounded() {
        assertTrue(CanaryInputGain.gainFor(0.0003) <= CanaryInputGain.MAX_GAIN)
        assertTrue(CanaryInputGain.gainFor(0.0003) > 1.0)
    }
}
