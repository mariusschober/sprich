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
        // capacity 4, so should hold 3,4,5,6 or 5,6,?
        // Implementation overwrites one by one
        val out = ShortArray(4)
        rb.drain(out)
        assertArrayEquals(shortArrayOf(3,4,5,6), out)
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
    fun clearResets() {
        val rb = AudioRingBuffer(10)
        rb.write(shortArrayOf(1,2,3))
        rb.clear()
        assertEquals(0, rb.available())
    }
}
