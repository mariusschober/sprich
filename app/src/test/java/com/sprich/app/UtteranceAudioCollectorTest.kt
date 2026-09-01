package com.sprich.app

import com.sprich.app.core.audio.UtteranceAudioCollector
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 21 — Pre-roll identity test for neutral collector.
 * Must prove collector is primitive, bounded, preRoll exactly once, no leakage.
 */
class UtteranceAudioCollectorTest {

    @Test
    fun preRollExistsExactlyOnce() {
        val collector = UtteranceAudioCollector(maxSamples = 16000 * 30)
        val preRoll = shortArrayOf(1, 2, 3)
        val chunk1 = shortArrayOf(4, 5)
        val chunk2 = shortArrayOf(6, 7)

        collector.begin(preRoll)
        collector.append(chunk1)
        collector.append(chunk2)

        val frozen = collector.freeze()
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5, 6, 7), frozen)
        assertFalse(frozen.contentEquals(shortArrayOf(1, 2, 3, 1, 2, 3, 4, 5, 6, 7)))
        assertEquals(7, frozen.size)
    }

    @Test
    fun nextUtteranceContainsZeroFromPrevious() {
        val collector = UtteranceAudioCollector(maxSamples = 16000 * 30)
        val preRollA = shortArrayOf(1, 2, 3)
        val chunkA1 = shortArrayOf(4, 5)
        val chunkA2 = shortArrayOf(6, 7)
        collector.begin(preRollA)
        collector.append(chunkA1)
        collector.append(chunkA2)
        val frozenA = collector.freeze()
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5, 6, 7), frozenA)

        // Next utterance — must not contain samples from prior
        val preRollB = shortArrayOf(8)
        val chunkB = shortArrayOf(9, 10)
        collector.begin(preRollB)
        collector.append(chunkB)
        val frozenB = collector.freeze()
        assertArrayEquals(shortArrayOf(8, 9, 10), frozenB)
        assertFalse(frozenB.any { it == 1.toShort() || it == 2.toShort() || it == 3.toShort() })
        assertEquals(3, frozenB.size)
    }

    @Test
    fun freezeIsImmutableAndPreventsFurtherAppend() {
        val collector = UtteranceAudioCollector(maxSamples = 100)
        collector.begin(shortArrayOf(1, 2))
        collector.append(shortArrayOf(3, 4))
        val frozen1 = collector.freeze()
        collector.append(shortArrayOf(5, 6)) // should be ignored after freeze
        val frozen2 = collector.freeze()
        assertArrayEquals(frozen1, frozen2)
        // Returned array is copy — mutating it must not affect collector
        frozen1[0] = 99
        val frozen3 = collector.snapshot()
        assertEquals(1.toShort(), frozen3[0])
    }

    @Test
    fun boundedMemoryDropsOldest() {
        val collector = UtteranceAudioCollector(maxSamples = 5)
        collector.begin(shortArrayOf(1, 2, 3))
        collector.append(shortArrayOf(4, 5))
        // At capacity 5
        assertEquals(5, collector.size())
        // Appending 2 more should drop oldest 2 to stay bounded
        collector.append(shortArrayOf(6, 7))
        val snap = collector.snapshot()
        assertEquals(5, snap.size)
        assertTrue(snap.contains(7))
        assertFalse(snap.size > 5)
    }

    @Test
    fun clearRemovesAll() {
        val collector = UtteranceAudioCollector()
        collector.begin(shortArrayOf(1, 2, 3))
        collector.append(shortArrayOf(4))
        assertEquals(4, collector.size())
        collector.clear()
        assertEquals(0, collector.size())
        assertTrue(collector.isEmpty())
        assertFalse(collector.isFrozen())
        val snap = collector.snapshot()
        assertEquals(0, snap.size)
    }
}
