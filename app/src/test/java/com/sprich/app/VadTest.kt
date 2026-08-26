package com.sprich.app

import com.sprich.app.core.vad.Vad
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class VadTest {

    private fun silence(samples: Int = 1024): ShortArray = ShortArray(samples) { 0 }
    private fun speech(samples: Int = 1024): ShortArray = ShortArray(samples) { (sin(it * 0.1) * 12000).toInt().toShort() }
    private fun quietSpeech(samples: Int = 1024): ShortArray = ShortArray(samples) { (sin(it * 0.1) * 500).toInt().toShort() }
    private fun noiseFloorCalibrate(vad: Vad) {
        repeat(5) { vad.process(silence(), 64) }
    }

    @Test
    fun calibratesAndDetectsSpeech() {
        val vad = Vad()
        noiseFloorCalibrate(vad)
        assertFalse(vad.isCalibrating())
        val r = vad.process(speech(), 64)
        // After calibration, speech should eventually be SPEECH
        var state = r.state
        repeat(3) { vad.process(speech(), 64).let { state = it.state } }
        assertTrue(state == Vad.State.SPEECH || state == Vad.State.HESITATION)
    }

    @Test
    fun silenceStaysSilence() {
        val vad = Vad()
        noiseFloorCalibrate(vad)
        repeat(10) {
            val r = vad.process(silence(), 64)
            assertTrue(r.state == Vad.State.SILENCE || r.state == Vad.State.HESITATION || r.state == Vad.State.UTTERANCE_END)
        }
    }

    @Test
    fun utteranceEndAfterSilence() {
        val vad = Vad(utteranceEndMs = 300, hesitationMs = 150)
        noiseFloorCalibrate(vad)
        // speech
        repeat(2) { vad.process(speech(), 64) }
        // silence long enough
        var res = vad.process(silence(), 64)
        repeat(5) { res = vad.process(silence(), 64) }
        assertTrue(res.state == Vad.State.UTTERANCE_END || res.state == Vad.State.LONG_SILENCE)
    }

    @Test
    fun resetsCleanly() {
        val vad = Vad()
        noiseFloorCalibrate(vad)
        vad.process(speech(), 64)
        vad.reset()
        assertTrue(vad.currentState() == Vad.State.SILENCE)
    }

    @Test
    fun immediateSpeechIsNotLearnedAsNoise() {
        val vad = Vad()
        var state = Vad.State.SILENCE
        repeat(6) { state = vad.process(quietSpeech(), 64).state }
        assertTrue(state == Vad.State.SPEECH || state == Vad.State.HESITATION)
        assertTrue(vad.hasSpeech())
    }

    @Test
    fun delayedQuietSpeechStillTriggersAfterLongSilence() {
        val vad = Vad()
        repeat(160) { vad.process(silence(), 64) }
        var state = Vad.State.SILENCE
        repeat(4) { state = vad.process(quietSpeech(), 64).state }
        assertTrue(state == Vad.State.SPEECH || state == Vad.State.HESITATION)
    }

    @Test
    fun emptyFrameIsSafe() {
        val result = Vad().process(ShortArray(0), 0)
        assertEquals(Vad.State.SILENCE, result.state)
        assertEquals(0f, result.rms)
    }
}
