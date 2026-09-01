package com.sprich.app.speech

import com.sprich.app.core.audio.UtteranceAudioCollector
import com.sprich.app.core.audio.UtterancePcmBuffer
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class UtteranceCollectorStrictBoundTest {

    @Test
    fun collectorStrictBoundOversizedChunk() {
        val max = 16000 * 2 // 2s
        val collector = UtteranceAudioCollector(maxSamples = max)
        val oversized = ShortArray(max * 2) { it.toShort() } // 2x max in one append
        collector.append(oversized) // without begin? should still bound? begin not called, but append still
        // Actually collector starts empty, append oversized should keep final max portion
        assertTrue(collector.size() <= max)
        assertEquals(max, collector.size())
        val snap = collector.snapshot()
        assertEquals(max, snap.size)
        // Last samples should be from tail
        assertEquals((max*2-1).toShort(), snap.last())
    }

    @Test
    fun collectorBeginOversizedPreRoll() {
        val max = 100
        val collector = UtteranceAudioCollector(maxSamples = max)
        val preRoll = ShortArray(200) { (it+1).toShort() }
        collector.begin(preRoll)
        assertEquals(max, collector.size())
        val snap = collector.freeze()
        assertEquals(max, snap.size)
        // Should keep final 100 of preRoll (101..200)
        assertEquals(101.toShort(), snap.first())
        assertEquals(200.toShort(), snap.last())
    }

    @Test
    fun pcmBufferStrictBound() {
        val max = 50
        val buf = UtterancePcmBuffer(maxSamples = max)
        buf.beginWithPreRoll(ShortArray(10){1})
        buf.append(ShortArray(100){2}) // oversized
        assertEquals(max, buf.size())
        val snap = buf.snapshot()
        assertEquals(max, snap.size)
        assertTrue(snap.all { it == 2.toShort() }) // all tail from oversized
    }

    @Test
    fun randomChunksNeverExceedBound() {
        val max = 48000
        val collector = UtteranceAudioCollector(maxSamples = max)
        val rnd = Random(1234)
        repeat(100) {
            val size = rnd.nextInt(1, 8000)
            val chunk = ShortArray(size) { rnd.nextInt().toShort() }
            collector.append(chunk)
            assertTrue("size ${collector.size()} > max $max at iter $it", collector.size() <= max)
        }
        // Also test oversized single chunk 2*max
        collector.append(ShortArray(max*2){5})
        assertTrue(collector.size() <= max)
    }

    @Test
    fun utteranceBDoesNotContainA() {
        val collector = UtteranceAudioCollector(maxSamples = 16000*30)
        val a = ShortArray(1000){1}
        collector.begin(a)
        collector.append(ShortArray(500){1})
        val snapA = collector.freeze()
        collector.begin(ShortArray(800){2})
        collector.append(ShortArray(200){2})
        val snapB = collector.snapshot()
        assertFalse(snapB.any { it == 1.toShort() })
        assertEquals(1000, snapB.size)
    }
}
