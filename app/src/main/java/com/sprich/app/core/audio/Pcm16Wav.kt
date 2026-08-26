package com.sprich.app.core.audio

import java.io.InputStream

/** Minimal, strict reader for deterministic PCM16 mono test fixtures. */
object Pcm16Wav {
    data class Audio(val samples: ShortArray, val sampleRate: Int)

    fun read(input: InputStream): Audio {
        val bytes = input.use { it.readBytes() }
        require(bytes.size >= 44) { "WAV is too short" }
        require(ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") {
            "Not a RIFF/WAVE file"
        }

        var offset = 12
        var audioFormat = -1
        var channels = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var dataOffset = -1
        var dataSize = -1

        while (offset + 8 <= bytes.size) {
            val id = ascii(bytes, offset, 4)
            val size = littleEndianInt(bytes, offset + 4)
            val payload = offset + 8
            require(size >= 0 && payload + size <= bytes.size) { "Invalid WAV chunk $id" }
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "Invalid WAV fmt chunk" }
                    audioFormat = littleEndianShort(bytes, payload)
                    channels = littleEndianShort(bytes, payload + 2)
                    sampleRate = littleEndianInt(bytes, payload + 4)
                    bitsPerSample = littleEndianShort(bytes, payload + 14)
                }
                "data" -> {
                    dataOffset = payload
                    dataSize = size
                }
            }
            offset = payload + size + (size and 1)
        }

        require(audioFormat == 1) { "Only PCM WAV is supported" }
        require(channels == 1) { "Only mono WAV is supported" }
        require(bitsPerSample == 16) { "Only PCM16 WAV is supported" }
        require(sampleRate == 16_000) { "Expected 16 kHz WAV, got $sampleRate" }
        require(dataOffset >= 0 && dataSize >= 2) { "WAV data chunk missing" }

        val samples = ShortArray(dataSize / 2)
        for (index in samples.indices) {
            samples[index] = littleEndianShort(bytes, dataOffset + index * 2).toShort()
        }
        return Audio(samples, sampleRate)
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
