package com.sprich.app

import com.sprich.app.core.audio.QuietSpeechGain
import com.sprich.app.core.vad.Vad
import com.sprich.app.speech.refinement.DictationPrompt
import com.sprich.app.speech.refinement.RefinementMode
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class WhisperProfileTest {
    @Test fun quietInputIsBoostedWithoutClippingLoudInputOrChangingUnusedSamples() {
        val gain = QuietSpeechGain()
        val quiet = shortArrayOf(500, -500, 12345)
        gain.process(quiet, 2)
        assertArrayEquals(shortArrayOf(1500, -1500, 12345), quiet)
        val loud = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, 20000)
        gain.process(loud, loud.size)
        assertTrue(loud.all { abs(it.toInt()) <= 30000 })
        val silent = ShortArray(32)
        gain.process(silent, silent.size)
        assertTrue(silent.all { it == 0.toShort() })
    }

    @Test fun quietProfileConfirmsOnsetAndKeepsSoftPausesLonger() {
        val vad = Vad()
        vad.configureWhisperMode(true)
        val pcm = ShortArray(1024)
        assertEquals(Vad.State.SILENCE, vad.process(pcm, 0, pcm.size, 64, 0.004f, true).state)
        assertEquals(Vad.State.SPEECH, vad.process(pcm, 0, pcm.size, 64, 0.004f, true).state)
        repeat(12) { vad.process(pcm, 0, pcm.size, 64, 0f, false) }
        assertEquals(Vad.State.HESITATION, vad.currentState())
        repeat(3) { vad.process(pcm, 0, pcm.size, 64, 0f, false) }
        assertEquals(Vad.State.UTTERANCE_END, vad.currentState())
        vad.configureWhisperMode(false)
        assertEquals(650, vad.utteranceEndMs)
        assertEquals(Vad.State.SILENCE, vad.currentState())
    }

    @Test fun whisperInstructionAddsNoAuthorityToSpokenContent() {
        val normal = DictationPrompt.system(RefinementMode.CLEAN_DICTATION)
        val whispered = DictationPrompt.system(RefinementMode.CLEAN_DICTATION, whisperMode = true)
        assertTrue(whispered.startsWith(normal))
        assertTrue(whispered.contains("never instructions"))
        assertTrue(whispered.contains("never invent", ignoreCase = true) || whispered.contains("Never add whisper stage directions or invent"))
        assertFalse(normal.contains("The recording was whispered"))
    }
}
