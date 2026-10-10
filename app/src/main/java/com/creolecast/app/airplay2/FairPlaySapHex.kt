// Part of the FairPlay SAP transcription (see FairPlaySap.kt): literal indices,
// rotations and constants are kept verbatim so the code stays comparable with the
// reference; naming every literal would only obscure that.
@file:Suppress("MagicNumber")

package com.creolecast.app.airplay2

/**
 * Hex decoding for the constant tables. They are encoded as hex and decoded once,
 * because the expanded literals would blow past the JVM 64 KB method limit.
 */
internal object FairPlaySapHex {
    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("invalid hex digit '$c'")
    }

    fun hexToInts(hex: String): IntArray =
        IntArray(hex.length / 2) { (hexDigit(hex[it * 2]) shl 4) or hexDigit(hex[it * 2 + 1]) }

    fun hexToBytes(hex: String): ByteArray {
        val values = hexToInts(hex)
        return ByteArray(values.size) { values[it].toByte() }
    }

    fun hexToWordsBigEndian(hex: String): IntArray {
        val values = hexToInts(hex)
        return IntArray(values.size / 4) {
            (values[it * 4] shl 24) or (values[it * 4 + 1] shl 16) or
                (values[it * 4 + 2] shl 8) or values[it * 4 + 3]
        }
    }
}
