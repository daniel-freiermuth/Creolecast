// Part of the FairPlay SAP transcription (see FairPlaySap.kt): literal indices,
// rotations and constants are kept verbatim so the code stays comparable with the
// reference; naming every literal would only obscure that.
@file:Suppress("MagicNumber")

package com.creolecast.app.airplay2

/** Byte and word helpers. Values are Ints holding 0..255 unless stated. */
internal object FairPlaySapBytes {
    fun readBigEndian32(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or (source[offset + 3].toInt() and 0xff)

    fun writeBigEndian32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    fun readLittleEndian32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    fun writeLittleEndian32(target: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) target[offset + i] = (value ushr (8 * i)).toByte()
    }

    fun writeLittleEndian64(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) target[offset + i] = (value ushr (8 * i)).toByte()
    }

    fun fairplayWordsFromLittleEndian(input: ByteArray): IntArray =
        IntArray(4) { readLittleEndian32(input, it * 4) }

    fun fairplayWordsBigEndian(words: IntArray): ByteArray {
        val out = ByteArray(16)
        for (i in 0 until 4) writeBigEndian32(out, i * 4, words[i])
        return out
    }
}
