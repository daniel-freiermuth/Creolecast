package com.creolecast.app.cast

/** ALAC syntax element ids are 3 bits wide. */
private const val ELEMENT_ID_BITS = 3

/** Channel pair element: one stereo pair. */
private const val ID_CPE = 1

/** End-of-frame element. */
private const val ID_END = 7

/** Element instance tag and the low half of the 12 unused header bits are 4 bits wide. */
private const val NIBBLE_BITS = 4

/** The 23-bit frame header occupies the first three bytes. */
private const val HEADER_BYTES = 3

/** Room for the 3-bit end tag after the samples. */
private const val TRAILER_BYTES = 1

/** Interleaved 16-bit stereo: two little-endian samples per frame. */
private const val BYTES_PER_STEREO_FRAME = 4

/** Keeps the low eight bits of an Int. */
private const val BYTE_MASK = 0xFF

/**
 * Wraps PCM for RAOP in ALAC frames that use the "uncompressed" escape: an ALAC header,
 * the samples as big-endian 16-bit values, and the end tag.
 */
internal object RaopAlac {
    /** Encodes little-endian interleaved 16-bit stereo [pcm] as one uncompressed ALAC frame. */
    fun encodeUncompressed(pcm: ByteArray): ByteArray {
        val writer = BitWriter(HEADER_BYTES + pcm.size + TRAILER_BYTES)
        writer.write(ID_CPE, ELEMENT_ID_BITS)
        writer.write(0, NIBBLE_BITS) // element instance tag
        writer.write(0, Byte.SIZE_BITS) // unused
        writer.write(0, NIBBLE_BITS) // unused
        writer.write(0, 1) // partial frame: no explicit sample count
        writer.write(0, 2) // bytes shifted
        writer.write(1, 1) // escape flag: samples are stored uncompressed
        for (frame in pcm.indices step BYTES_PER_STEREO_FRAME) {
            writer.writeSampleBigEndian(pcm, frame) // left
            writer.writeSampleBigEndian(pcm, frame + 2) // right
        }
        writer.write(ID_END, ELEMENT_ID_BITS)
        return writer.bytes
    }
}

/** Writes bit fields MSB-first into a fixed-size buffer. */
private class BitWriter(size: Int) {
    val bytes = ByteArray(size)
    private var bytePos = 0
    private var bitPos = 0

    /** Appends the low [bitCount] bits (at most 8) of [value]. */
    fun write(value: Int, bitCount: Int) {
        val shift = Byte.SIZE_BITS - bitPos - bitCount
        if (shift >= 0) {
            val bits = (value shl shift) and BYTE_MASK
            bytes[bytePos] =
                if (bitPos == 0) bits.toByte() else (bytes[bytePos].toInt() or bits).toByte()
            if (shift == 0) {
                bytePos++
                bitPos = 0
            } else {
                bitPos += bitCount
            }
        } else {
            bytes[bytePos] =
                (bytes[bytePos].toInt() or ((value ushr (-shift)) and BYTE_MASK)).toByte()
            bytePos++
            bytes[bytePos] = ((value shl (Byte.SIZE_BITS + shift)) and BYTE_MASK).toByte()
            bitPos = -shift
        }
    }

    /** Writes the little-endian 16-bit sample at [offset] of [pcm] high byte first. */
    fun writeSampleBigEndian(pcm: ByteArray, offset: Int) {
        write(pcm[offset + 1].toInt() and BYTE_MASK, Byte.SIZE_BITS)
        write(pcm[offset].toInt() and BYTE_MASK, Byte.SIZE_BITS)
    }
}
