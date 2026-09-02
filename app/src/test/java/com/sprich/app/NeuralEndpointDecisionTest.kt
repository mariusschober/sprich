package com.sprich.app

import com.sprich.app.core.vad.Vad
import org.junit.Assert.*
import org.junit.Test

/** Decision/endpoint integration only. Native speech accuracy is measured on devices. */
class NeuralEndpointDecisionTest {
    @Test fun loudNonSpeechCannotHoldAnUtteranceOpen() {
        val endpoint = Vad()
        val samples = ShortArray(1024) { 12000 }
        repeat(3) { endpoint.process(samples, 0, samples.size, 64, 0.36f, true) }
        assertEquals(Vad.State.SPEECH, endpoint.currentState())
        var ended = false
        repeat(12) { if (endpoint.process(samples, 0, samples.size, 64, 0.36f, false).state == Vad.State.UTTERANCE_END) ended = true }
        assertTrue("A neural non-speech decision must end speech even when RMS is high", ended)
        repeat(100) { assertNotEquals(Vad.State.SPEECH, endpoint.process(samples, 0, samples.size, 64, 0.36f, false).state) }
    }
    @Test fun softRecognizedSpeechCanStartBelowTheEnergyThreshold() {
        val endpoint = Vad()
        val samples = ShortArray(1024)
        repeat(3) { endpoint.process(samples, 0, samples.size, 64, 0.00001f, true) }
        assertEquals(Vad.State.SPEECH, endpoint.currentState())
    }
}
