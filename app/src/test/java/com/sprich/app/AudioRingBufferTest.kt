package com.sprich.app

import com.sprich.app.core.audio.AudioRingBuffer
import org.junit.Assert.*
import org.junit.Test

class AudioRingBufferTest {
    @Test
    fun writeAndDrain() {
        val rb = AudioRingBuffer(100)
        rb.write(shortArrayOf(1,2,3,4,5))
        assertEquals(5, rb.available())
        val out = ShortArray(5)
        val n = rb.drain(out)
        assertEquals(5, n)
        assertArrayEquals(shortArrayOf(1,2,3,4,5), out)
        assertEquals(0, rb.available())
    }

    @Test
    fun overwritesOldestOnOverflow() {
        val rb = AudioRingBuffer(4)
        rb.write(shortArrayOf(1,2,3,4))
        rb.write(shortArrayOf(5,6))
        val out = ShortArray(4)
        rb.drain(out)
        assertArrayEquals(shortArrayOf(3,4,5,6), out)
    }

    @Test
    fun oversizedWriteKeepsNewestTail() {
        val rb = AudioRingBuffer(4)
        rb.write(shortArrayOf(1,2,3,4,5,6))
        assertEquals(4, rb.available())
        val out = ShortArray(4)
        rb.drain(out)
        assertArrayEquals(shortArrayOf(3,4,5,6), out)
    }

    @Test
    fun writeHonorsOffsetAndLength() {
        val rb = AudioRingBuffer(4)
        rb.write(shortArrayOf(9,1,2,3,8), offset = 1, len = 3)
        val out = ShortArray(3)
        rb.drain(out)
        assertArrayEquals(shortArrayOf(1,2,3), out)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidRange() {
        AudioRingBuffer(4).write(shortArrayOf(1,2), offset = 1, len = 2)
    }

    @Test
    fun snapshotLastSeconds() {
        val rb = AudioRingBuffer(16000*5)
        val chunk = ShortArray(16000) { it.toShort() }
        rb.write(chunk)
        rb.write(chunk)
        val snap = rb.snapshotLast(1.0f, 16000)
        assertEquals(16000, snap.size)
    }

    @Test
    fun snapshotLastSamplesReturnsAnImmutableExactTail() {
        val rb = AudioRingBuffer(8)
        rb.write(shortArrayOf(1,2,3,4,5,6))
        val frozen = rb.snapshotLastSamples(4)
        rb.write(shortArrayOf(7,8))
        assertArrayEquals(shortArrayOf(3,4,5,6), frozen)
        assertArrayEquals(shortArrayOf(5,6,7,8), rb.snapshotLastSamples(4))
    }

    @Test
    fun clearResets() {
        val rb = AudioRingBuffer(10)
        rb.write(shortArrayOf(1,2,3))
        rb.clear()
        assertEquals(0, rb.available())
    }
}
