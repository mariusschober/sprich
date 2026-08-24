package com.sprich.app

import com.sprich.app.speech.stabilization.TranscriptStabilizer
import org.junit.Assert.*
import org.junit.Test

class TranscriptStabilizerTest {

    @Test
    fun stablePrefixRequiresAgreement() {
        val s = TranscriptStabilizer(requiredAgreement = 2)
        var r = s.pushHypothesis("Hello world")
        assertEquals("", r.stable) // not enough history
        assertEquals("Hello world", r.unstable)

        r = s.pushHypothesis("Hello world")
        assertEquals("Hello world", r.stable)
        assertEquals("", r.unstable)

        r = s.pushHypothesis("Hello world this is")
        // stable should remain "Hello world" until second agreement
        assertEquals("Hello world", r.stable)
        assertEquals("this is", r.unstable)

        r = s.pushHypothesis("Hello world this is")
        assertEquals("Hello world this is", r.stable)
    }

    @Test
    fun shrinkingHypothesisRevisesStable() {
        val s = TranscriptStabilizer(requiredAgreement = 2)
        s.pushHypothesis("I think we should meet tomorrow")
        s.pushHypothesis("I think we should meet tomorrow")
        var r = s.pushHypothesis("I think we should meet")
        // Current words shorter; stable should shrink to common prefix
        assertTrue(r.stable.split(" ").size <= 5)
    }

    @Test
    fun neverDuplicatesCommitted() {
        val s = TranscriptStabilizer(2)
        s.pushHypothesis("Let's meet Friday")
        s.pushHypothesis("Let's meet Friday")
        val before = s.currentStable()
        s.commitStable()
        // After commit, new hypothesis should extend, not duplicate
        val r = s.pushHypothesis("Let's meet Friday at three")
        assertTrue(r.stable.startsWith("Let's"))
    }

    @Test
    fun unstableChurnDoesNotAffectStable() {
        val s = TranscriptStabilizer(2)
        s.pushHypothesis("The quick brown fox")
        s.pushHypothesis("The quick brown fox")
        assertEquals("The quick brown fox", s.currentStable())
        // Next hypothesis changes last word
        var r = s.pushHypothesis("The quick brown dog jumps")
        assertEquals("The quick brown", r.stable) // only prefix survived 1 decode
        r = s.pushHypothesis("The quick brown dog jumps")
        assertEquals("The quick brown dog jumps", r.stable)
    }

    @Test
    fun emptyHypothesisKeepsStable() {
        val s = TranscriptStabilizer(2)
        s.pushHypothesis("hello world")
        s.pushHypothesis("hello world")
        val r = s.pushHypothesis("")
        assertEquals("hello world", r.stable)
    }
}
