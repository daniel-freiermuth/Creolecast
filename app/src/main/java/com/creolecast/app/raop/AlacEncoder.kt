package com.creolecast.app.raop

/** ALAC "verbatim" (uncompressed) frame encoder shared by RAOP and AirPlay 2. */
object AlacEncoder {

    /**
     * Encode 16-bit little-endian stereo PCM into an ALAC uncompressed frame:
     * a 23-bit channel-pair element header, the optional 32-bit sample count
     * (when [hasSize] is set, as AirPlay 2 / audio.go `encodeALACVerbatim`
     * requires), each sample byte-swapped to big-endian, then the 3-bit end tag.
     */
    fun encodeUncompressed(pcm: ByteArray, hasSize: Boolean): ByteArray {
        val samples = pcm.size / (2 * 2)
        val totalBits = 23 + (if (hasSize) 32 else 0) + pcm.size * 8 + 3
        val out = ByteArray((totalBits + 7) / 8)
        var p = 0
        var bpos = 0

        fun writeBits(v: Int, blen: Int) {
            val lb = 8 - bpos
            val rb = lb - blen
            if (rb >= 0) {
                val bd = (v shl rb) and 0xFF
                out[p] = if (bpos == 0) bd.toByte() else (out[p].toInt() or bd).toByte()
                if (rb == 0) { p++; bpos = 0 } else bpos += blen
            } else {
                out[p] = (out[p].toInt() or ((v ushr (-rb)) and 0xFF)).toByte()
                p++
                out[p] = ((v shl (8 + rb)) and 0xFF).toByte()
                bpos = -rb
            }
        }

        // writeBits carries at most one byte boundary, so nothing wider than 8
        // bits may be written in a single call.
        writeBits(1, 3)                      // tag: channel pair element (stereo)
        writeBits(0, 4)                      // elementInstanceTag
        writeBits(0, 8)                      // unused (12 bits, part 1)
        writeBits(0, 4)                      // unused (12 bits, part 2)
        writeBits(if (hasSize) 1 else 0, 1)  // hasSize
        writeBits(0, 2)                      // extraBytes (16-bit, no shift)
        writeBits(1, 1)                      // verbatim
        if (hasSize) {
            writeBits((samples ushr 24) and 0xFF, 8)
            writeBits((samples ushr 16) and 0xFF, 8)
            writeBits((samples ushr 8) and 0xFF, 8)
            writeBits(samples and 0xFF, 8)
        }

        var i = 0
        while (i < pcm.size) {
            writeBits(pcm[i + 1].toInt() and 0xFF, 8)  // L high byte
            writeBits(pcm[i + 0].toInt() and 0xFF, 8)  // L low byte
            writeBits(pcm[i + 3].toInt() and 0xFF, 8)  // R high byte
            writeBits(pcm[i + 2].toInt() and 0xFF, 8)  // R low byte
            i += 4
        }
        writeBits(7, 3)                      // end tag
        return out
    }
}
