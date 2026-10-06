package com.creolecast.app.raop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AlacEncoderTest {
    // One stereo frame: L = 0x0201, R = 0x0403 (little-endian).
    private val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /**
     * AirPlay 2 frame built as a plain bit string (no byte-carry arithmetic):
     * element header, 32-bit sample count, BE L/R samples, end tag, zero pad.
     */
    private fun referenceBits(pcm: ByteArray): ByteArray {
        fun bin(v: Long, width: Int) = java.lang.Long.toBinaryString(v).padStart(width, '0')
        fun u8(b: Byte) = bin((b.toInt() and 0xFF).toLong(), 8)
        val sb = StringBuilder()
        sb.append("001") // channel pair element
        sb.append("0000") // elementInstanceTag
        sb.append("0".repeat(12)) // unused
        sb.append("1") // hasSize
        sb.append("00") // extraBytes
        sb.append("1") // verbatim
        sb.append(bin((pcm.size / 4).toLong(), 32))
        for (i in pcm.indices step 4) {
            sb.append(u8(pcm[i + 1])).append(u8(pcm[i])) // L, big-endian
            sb.append(u8(pcm[i + 3])).append(u8(pcm[i + 2])) // R, big-endian
        }
        sb.append("111") // end tag
        while (sb.length % 8 != 0) sb.append('0')
        return ByteArray(sb.length / 8) { sb.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
    }

    @Test
    fun `frame without size packs header, big-endian samples and end tag`() {
        // 23-bit header (verbatim), samples 02 01 04 03 shifted by one bit, 111 end tag.
        assertArrayEquals(
            bytes(0x20, 0x00, 0x02, 0x04, 0x02, 0x08, 0x07, 0xC0),
            AlacEncoder.encodeUncompressed(pcm, hasSize = false)
        )
    }

    @Test
    fun `frame with size sets hasSize and carries the 32-bit sample count`() {
        assertArrayEquals(
            bytes(0x20, 0x00, 0x12, 0x00, 0x00, 0x00, 0x02, 0x04, 0x02, 0x08, 0x07, 0xC0),
            AlacEncoder.encodeUncompressed(pcm, hasSize = true)
        )
    }

    @Test
    fun `empty pcm with size encodes header, zero sample count and end tag in 8 bytes`() {
        // (23 + 32 + 3 + 7) / 8 = 8; end tag 0b111 straddles bits 55..57.
        assertArrayEquals(
            bytes(0x20, 0x00, 0x12, 0x00, 0x00, 0x00, 0x01, 0xC0),
            AlacEncoder.encodeUncompressed(ByteArray(0), hasSize = true)
        )
    }

    @Test
    fun `sample count is written as 32 bits starting at bit 23`() {
        // 385 = 0x00000181: set bits at both edges of the low count byte, which
        // straddle output byte boundaries because of the 23-bit header.
        val frames = 0x0181
        val out = AlacEncoder.encodeUncompressed(ByteArray(frames * 4), hasSize = true)
        // Bits 23..54 hold the count, so it occupies the low bit of byte 2,
        // bytes 3..5, and the high 7 bits of byte 6.
        val bits = ((out[2].toLong() and 0x01) shl 31) or
            ((out[3].toLong() and 0xFF) shl 23) or
            ((out[4].toLong() and 0xFF) shl 15) or
            ((out[5].toLong() and 0xFF) shl 7) or
            ((out[6].toLong() and 0xFF) ushr 1)
        assertEquals(frames.toLong(), bits)
        // Header bits ahead of the count: tag=1, hasSize=1, verbatim=1.
        assertEquals(0x20, out[0].toInt() and 0xFF)
        assertEquals(0x00, out[1].toInt() and 0xFF)
        assertEquals(0x12, out[2].toInt() and 0xFE)
    }

    @Test
    fun `352-frame stereo packet with size matches the bit-string reference`() {
        val pcm = ByteArray(352 * 4) { ((it * 37 + 11) and 0xFF).toByte() }
        val out = AlacEncoder.encodeUncompressed(pcm, hasSize = true)
        assertEquals((23 + 32 + 352 * 32 + 3 + 7) / 8, out.size)
        // Spot-check against bytes derived outside this codebase.
        assertArrayEquals(
            bytes(
                0x20, 0x00, 0x12, 0x00, 0x00, 0x02, 0xC0, 0x60,
                0x16, 0xF4, 0xAB, 0x89, 0x3E, 0x1D, 0xD2, 0xB0
            ),
            out.copyOfRange(0, 16)
        )
        assertArrayEquals(
            bytes(0xC7, 0xA5, 0x5A, 0x39, 0xEE, 0xCC, 0x83, 0xC0),
            out.copyOfRange(out.size - 8, out.size)
        )
        assertArrayEquals(referenceBits(pcm), out)
    }

    @Test
    fun `pcm not a multiple of 4 bytes is rejected rather than encoded short`() {
        for (size in intArrayOf(1, 2, 3, 5, 6, 7)) {
            assertThrows("size $size", IndexOutOfBoundsException::class.java) {
                AlacEncoder.encodeUncompressed(ByteArray(size), hasSize = true)
            }
        }
    }
}
