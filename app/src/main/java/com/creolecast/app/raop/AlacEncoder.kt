package com.creolecast.app.raop

/**
 * ALAC "verbatim" (uncompressed) frame encoder shared by RAOP and AirPlay 2: a
 * 23-bit channel-pair element header, the optional 32-bit sample count, each
 * little-endian stereo sample byte-swapped to big-endian, then the 3-bit end tag.
 */
internal object AlacEncoder {
    private const val ELEMENT_HEADER_BITS = 23
    private const val SAMPLE_COUNT_BITS = 32
    private const val TAG_BITS = 3
    private const val ID_CHANNEL_PAIR = 1
    private const val ID_END = 7
    private const val INSTANCE_TAG_BITS = 4

    /** The 12 unused header bits, written as 8 + 4 (see [BitWriter.write]). */
    private const val UNUSED_TAIL_BITS = 4
    private const val EXTRA_BYTES_BITS = 2

    /** 16-bit little-endian stereo: L low, L high, R low, R high. */
    private const val BYTES_PER_FRAME = 4
    private const val LEFT_LOW = 0
    private const val LEFT_HIGH = 1
    private const val RIGHT_LOW = 2
    private const val RIGHT_HIGH = 3
    private const val BYTE_MASK = 0xFF

    /**
     * Encodes interleaved 16-bit little-endian stereo [pcm] as one uncompressed
     * ALAC frame. [hasSize] sets the header's hasSize bit and writes the 32-bit
     * sample count after it, as AirPlay 2 (audio.go `encodeALACVerbatim`)
     * requires; RAOP leaves both out.
     */
    fun encodeUncompressed(pcm: ByteArray, hasSize: Boolean): ByteArray {
        val samples = pcm.size / BYTES_PER_FRAME
        val sizeBits = if (hasSize) SAMPLE_COUNT_BITS else 0
        val totalBits = ELEMENT_HEADER_BITS + sizeBits + pcm.size * Byte.SIZE_BITS + TAG_BITS
        val writer = BitWriter(ByteArray((totalBits + Byte.SIZE_BITS - 1) / Byte.SIZE_BITS))

        // The writer carries at most one byte boundary, so nothing wider than 8
        // bits may be written in a single call.
        writer.write(ID_CHANNEL_PAIR, TAG_BITS) // tag: channel pair element (stereo)
        writer.write(0, INSTANCE_TAG_BITS) // elementInstanceTag
        writer.write(0, Byte.SIZE_BITS) // unused (12 bits, part 1)
        writer.write(0, UNUSED_TAIL_BITS) // unused (12 bits, part 2)
        writer.write(if (hasSize) 1 else 0, 1) // hasSize
        writer.write(0, EXTRA_BYTES_BITS) // extraBytes (16-bit, no shift)
        writer.write(1, 1) // verbatim
        var shift = sizeBits
        while (shift > 0) { // sample count, big-endian
            shift -= Byte.SIZE_BITS
            writer.write((samples ushr shift) and BYTE_MASK, Byte.SIZE_BITS)
        }

        var i = 0
        while (i < pcm.size) {
            writer.writeByte(pcm[i + LEFT_HIGH])
            writer.writeByte(pcm[i + LEFT_LOW])
            writer.writeByte(pcm[i + RIGHT_HIGH])
            writer.writeByte(pcm[i + RIGHT_LOW])
            i += BYTES_PER_FRAME
        }
        writer.write(ID_END, TAG_BITS)
        return writer.out
    }

    /** Writes bit fields MSB-first into [out]. */
    private class BitWriter(val out: ByteArray) {
        private var p = 0
        private var bpos = 0

        fun writeByte(b: Byte) = write(b.toInt() and BYTE_MASK, Byte.SIZE_BITS)

        /** Appends the low [blen] bits (at most 8) of [v]. */
        fun write(v: Int, blen: Int) {
            val lb = Byte.SIZE_BITS - bpos
            val rb = lb - blen
            if (rb >= 0) {
                val bd = (v shl rb) and BYTE_MASK
                out[p] = if (bpos == 0) bd.toByte() else (out[p].toInt() or bd).toByte()
                if (rb == 0) {
                    p++
                    bpos = 0
                } else {
                    bpos += blen
                }
            } else {
                out[p] = (out[p].toInt() or ((v ushr (-rb)) and BYTE_MASK)).toByte()
                p++
                out[p] = ((v shl (Byte.SIZE_BITS + rb)) and BYTE_MASK).toByte()
                bpos = -rb
            }
        }
    }
}
