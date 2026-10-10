// Part of the FairPlay SAP transcription (see FairPlaySap.kt): literal indices,
// rotations and constants are kept verbatim so the code stays comparable with the
// reference; naming every literal would only obscure that.
@file:Suppress("ktlint:standard:max-line-length", "MagicNumber")

package com.creolecast.app.airplay2

import com.creolecast.app.airplay2.FairPlaySapBytes.readBigEndian32
import com.creolecast.app.airplay2.FairPlaySapHex.hexToWordsBigEndian

private val FAIRPLAY_MD5_SHIFT = intArrayOf(
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
)

private val FAIRPLAY_MD5_CONSTANT = hexToWordsBigEndian(
    "d76aa478e8c7b756242070dbc1bdceeef57c0faf4787c62aa8304613fd469501698098d88b44f7afffff5bb1895cd7be" +
        "6b901122fd987193a679438e49b40821f61e2562c040b340265e5a51e9b6c7aad62f105d02441453d8a1e681e7d3fbc8" +
        "21e1cde6c33707d6f4d50d87455a14eda9e3e905fcefa3f8676f02d98d2a4c8afffa39428771f6816d9d6122fde5380c" +
        "a4beea444bdecfa9f6bb4b60bebfbc70289b7ec6eaa127fad4ef308504881d05d9d4d039e6db99e51fa27cf8c4ac5665" +
        "f4292244432aff97ab9423a7fc93a039655b59c38f0ccc92ffeff47d85845dd16fa87e4ffe2ce6e0a30143144e0811a1" +
        "f7537e82bd3af2352ad7d2bbeb86d391"
)

/**
 * fairplay_md5.go: standard MD5 rounds, big-endian message words and a
 * message-schedule mutation after round 31.
 */
internal object FairPlaySapMd5 {
    const val MUTATION_SWAP = 0
    const val MUTATION_CYCLE = 1
    const val MUTATION_KDF = 2

    fun fairplayMD5Compress(
        state: IntArray,
        block: ByteArray,
        blockOffset: Int,
        mutation: Int
    ): IntArray {
        val message = IntArray(16) { readBigEndian32(block, blockOffset + it * 4) }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        for (round in 0 until 64) {
            val f: Int
            val word: Int
            if (round < 16) {
                f = (b and c) or (b.inv() and d)
                word = round
            } else if (round < 32) {
                f = (d and b) or (d.inv() and c)
                word = (5 * round + 1) and 15
            } else if (round < 48) {
                f = b xor c xor d
                word = (3 * round + 5) and 15
            } else {
                f = c xor (b or d.inv())
                word = (7 * round) and 15
            }

            val mixed =
                b +
                    Integer.rotateLeft(
                        a + f + FAIRPLAY_MD5_CONSTANT[round] + message[word],
                        FAIRPLAY_MD5_SHIFT[round]
                    )
            a = d
            d = c
            c = b
            b = mixed

            if (round == 31) {
                when (mutation) {
                    MUTATION_SWAP, MUTATION_CYCLE ->
                        permuteFairplayMD5Message(message, fairplayMD5Indices(a, b, c, d), mutation)

                    else -> swapFairplayMD5Message(message, a, b, c, d)
                }
            }
        }

        return intArrayOf(state[0] + a, state[1] + b, state[2] + c, state[3] + d)
    }

    // The message-schedule mutation after round 31 (mutateFairplayMD5Message in the
    // reference), split by mutation kind.

    private fun fairplayMD5Indices(a: Int, b: Int, c: Int, d: Int): IntArray = intArrayOf(
        a and 15,
        b and 15,
        c and 15,
        d and 15,
        (a ushr 4) and 15,
        (b ushr 4) and 15,
        (c ushr 4) and 15,
        (d ushr 4) and 15
    )

    private fun permuteFairplayMD5Message(message: IntArray, indices: IntArray, mutation: Int) {
        if (mutation == MUTATION_SWAP) {
            for (i in indices.indices) swapFairplayMD5Words(message, i, indices[i])
        } else {
            val first = message[indices[0]]
            for (i in 0 until indices.size - 1) message[indices[i]] = message[indices[i + 1]]
            message[indices[indices.size - 1]] = first
        }
    }

    private fun swapFairplayMD5Message(message: IntArray, a: Int, b: Int, c: Int, d: Int) {
        swapFairplayMD5Words(message, a and 15, b and 15)
        swapFairplayMD5Words(message, c and 15, d and 15)
        var shift = 4
        while (shift <= 12) {
            swapFairplayMD5Words(message, (a ushr shift) and 15, (b ushr shift) and 15)
            shift += 4
        }
    }

    private fun swapFairplayMD5Words(message: IntArray, i: Int, j: Int) {
        val held = message[i]
        message[i] = message[j]
        message[j] = held
    }
}
