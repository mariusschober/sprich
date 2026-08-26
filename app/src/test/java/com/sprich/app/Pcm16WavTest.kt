package com.sprich.app

import com.sprich.app.core.audio.Pcm16Wav
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Pcm16WavTest {
    @Test
    fun readsPcm16MonoFixture() {
        val expected = shortArrayOf(-1, 0, 1, 1024)
        val dataBytes = expected.size * 2
        val bytes = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(16_000)
            putInt(32_000)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataBytes)
            expected.forEach(::putShort)
        }.array()

        val audio = Pcm16Wav.read(ByteArrayInputStream(bytes))
        assertEquals(16_000, audio.sampleRate)
        assertArrayEquals(expected, audio.samples)
    }
}
