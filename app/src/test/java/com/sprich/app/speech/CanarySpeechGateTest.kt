package com.sprich.app.speech

import com.sprich.app.speech.canary.CanarySpeechGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R1 regression: quiet but valid public-corpus speech must reach the recognizer. */
class CanarySpeechGateTest {

    @Test
    fun onlyDigitalOrNearDigitalSilenceIsRejected() {
        assertTrue(CanarySpeechGate.isEffectivelySilent(ShortArray(0)))
        assertTrue(CanarySpeechGate.isEffectivelySilent(ShortArray(1600)))
        // +/-2 LSB is near-digital noise, below the defensive threshold.
        assertTrue(CanarySpeechGate.isEffectivelySilent(ShortArray(1600) { if (it % 2 == 0) 2 else -2 }))
    }

    @Test
    fun quietPublicCorpusLevelsReachTheRecognizer() {
        // The FLEURS clips that used to blank had RMS 0.00042-0.00049; +/-14..16 LSB is that level.
        for (amplitude in listOf(14, 15, 16)) {
            val pcm = ShortArray(1600) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }
            assertFalse("amplitude=$amplitude must not be gated", CanarySpeechGate.isEffectivelySilent(pcm))
        }
    }

    @Test
    fun ordinarySpeechIsNotRejected() {
        assertFalse(CanarySpeechGate.isEffectivelySilent(ShortArray(1600) { (if (it % 2 == 0) 2000 else -2000).toShort() }))
    }
}
