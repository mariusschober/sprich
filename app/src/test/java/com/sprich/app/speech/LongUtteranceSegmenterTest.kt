package com.sprich.app.speech

import com.sprich.app.speech.api.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongUtteranceSegmenterTest {

    private fun speech(samples: Int, amplitude: Int = 8000): ShortArray =
        ShortArray(samples) { i -> if ((i / 40) % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }

    private fun rms(pcm: ShortArray, start: Int, length: Int): Double {
        var sum = 0.0
        for (i in start until start + length) {
            val f = pcm[i] / 32768.0
            sum += f * f
        }
        return kotlin.math.sqrt(sum / length)
    }

    @Test
    fun shortUtteranceIsOneSegment() {
        val pcm = speech(3 * 16_000)
        assertEquals(listOf(SampleRange(0, pcm.size)), LongUtteranceSegmenter.plan(pcm, safeLimit = 4 * 16_000))
    }

    @Test
    fun longUtteranceSplitsAtQuietGapsAndCoversEverything() {
        // 12 s of speech with 0.5 s quiet gaps at 4 s, 8 s and 11 s.
        val pcm = ShortArray(12 * 16_000)
        val gaps = listOf(4 * 16_000 to 4 * 16_000 + 8000, 8 * 16_000 to 8 * 16_000 + 8000)
        for (i in pcm.indices) {
            val inGap = gaps.any { i in it.first until it.second }
            pcm[i] = if (inGap) 0 else if ((i / 40) % 2 == 0) 8000 else -8000
        }
        val segments = LongUtteranceSegmenter.plan(pcm, safeLimit = 5 * 16_000, searchWindow = 16_000)
        assertTrue("expected at least two segments, got ${segments.size}", segments.size >= 2)
        assertEquals("segments must cover the utterance exactly", pcm.size, segments.sumOf { it.length })
        assertEquals(0, segments.first().start)
        for (segment in segments) assertTrue("segment too short ${segment.length}", segment.length >= 10_000)
        for (index in 1 until segments.size) {
            val boundary = segments[index].start
            assertTrue("split at $boundary must be inside a quiet gap", gaps.any { boundary in it.first until it.second })
            val quiet = rms(pcm, boundary - 100, 200)
            assertTrue("split at $boundary is not quiet (rms=$quiet)", quiet < 0.01)
        }
    }

    @Test
    fun continuousToneStillBoundedBySafeLimitPlusWindow() {
        val pcm = speech(30 * 16_000)
        val segments = LongUtteranceSegmenter.plan(pcm, safeLimit = 8 * 16_000, searchWindow = 2 * 16_000)
        assertTrue(segments.size >= 3)
        for (segment in segments) {
            assertTrue("segment too long ${segment.length}", segment.length <= 10 * 16_000 + 320)
            assertTrue("segment too short ${segment.length}", segment.length >= 10_000)
        }
    }

    @Test
    fun joinAppliesTypographyRulesAndDropsBlankSegments() {
        val joined = LongUtteranceSegmenter.join(listOf("hello .", " ", "world"), Language.EN)
        assertEquals("hello. world", joined)
        assertEquals("", LongUtteranceSegmenter.join(emptyList(), Language.EN))
    }
}
