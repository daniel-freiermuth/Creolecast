package com.creolecast.app.raop

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AlacEncoderTest {
    // One stereo frame: L = 0x0201, R = 0x0403 (little-endian).
    private val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `frame without size packs header, big-endian samples and end tag`() {
        // 23-bit header (verbatim), samples 02 01 04 03 shifted by one bit, 111 end tag.
        assertArrayEquals(
            bytes(0x20, 0x00, 0x02, 0x04, 0x02, 0x08, 0x07, 0xC0),
            AlacEncoder.encodeUncompressed(pcm, hasSize = false),
        )
    }

    @Test
    fun `frame with size sets hasSize and carries the 32-bit sample count`() {
        assertArrayEquals(
            bytes(0x20, 0x00, 0x12, 0x00, 0x00, 0x00, 0x02, 0x04, 0x02, 0x08, 0x07, 0xC0),
            AlacEncoder.encodeUncompressed(pcm, hasSize = true),
        )
    }
}
