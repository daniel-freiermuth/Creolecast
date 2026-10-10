// Part of the FairPlay SAP transcription (see FairPlaySap.kt): literal indices,
// rotations and constants are kept verbatim so the code stays comparable with the
// reference; naming every literal would only obscure that.
@file:Suppress("ktlint:standard:max-line-length", "MagicNumber")

package com.creolecast.app.airplay2

import com.creolecast.app.airplay2.FairPlaySapHex.hexToInts

// fairplay_message.go
private val FAIRPLAY_MESSAGE_IV = hexToInts(
    "5752f1b7549d8f870c10485a6088cadbdf7b1563f005587752a90402b9a3929568b54611fb04de676c968efb8c9db0c9" +
        "27078b2123361e7adc9d0b115354690d"
)

/** Mode 0..3; the receiver selects one in m2. */

private val FAIRPLAY_MESSAGE_ROUND_KEY0 = hexToInts("d30dfdb563def72b012ead72884dca25")
private val FAIRPLAY_MESSAGE_ROUND_KEY10 = hexToInts("cd5ecf47e9af289b51188f68d085eb69")

// [(mode * 9 + round) * 16 + byte]
private val FAIRPLAY_MESSAGE_MIDDLE_KEYS = hexToInts(
    "6af008a6ae5118268acccb4357d1e711c6b579e0dfce8114d41d91c868513be3bafc24065ebc9cf6131652de75afd0de" +
        "81a8b516836ee6493cccc125db99e0ec781b6ceb300cfe8f3c0c54c92dc7e2a3594cc871ad4a3dd851a884b0ed048f5c" +
        "afea245eae1f2de557bb511b17ddcc3d8e7a945569c09856533872ca6a3a00def3ca31810cb6201b24b5c7e4ee108bc0" +
        "1b9018bea49613e86ab413627c84a766c94c1dc55c8bbb04a940f640f420eba5d5664239df63981372b7a8b2360b160b" +
        "462cd7af87b8005a47157e32644557cff0c0ac26f01988a8573d39e298f97f6a9407abf947788ceada25ee558333edb6" +
        "3f820f9ed823ce2f7722c4bab1d2e33571fca0ff354f8991109961878bfa7cfb566c98381eedcfe7954f7213f61fc87e" +
        "2ab2ebe11496a9655dd1b28a9910ee309cbc01d64fa41ca1f705a0ef7fb4793408f02908920c5b348d32d3470b07705b" +
        "8735404470486fa4a42b04fbfde11e3cd8eba4780f69653dfc7d2b4fbfb1ac68a74a7a6696be42e1b884e1acff1311a4" +
        "cc7a007cd8a3f330d3f17ba3437d52cd1edeff958a6254b20114cfddc02a48f83a36f4e069e1907b2c8c779be7336c09" +
        "f6a223b4f01d8a7927a7c965de554de00cd1515d43246c87fc2f494364a4cfbcd66e3bdd6c7f1b811f90291984b5eaee" +
        "530d7ae85b3040569f13130f18fbb1e50784c47a9fe10f3d5ea5432ecdd9f585b9b0368eb7d134d90ebd1b94b255a4fb" +
        "b2a4352534066fdec1ce38db3d8c3b850b317a942520622d89578e9fe5a13fc3bdf569e6befc1d2fc08baf733f1d176a"
)

private val INVERSE_AES_SBOX = hexToInts(
    "52096ad53036a538bf40a39e81f3d7fb7ce339829b2fff87348e4344c4dee9cb547b9432a6c2233dee4c950b42fac34e" +
        "082ea16628d924b2765ba2496d8bd12572f8f66486689816d4a45ccc5d65b6926c704850fdedb9da5e154657a78d9d84" +
        "90d8ab008cbcd30af7e45805b8b34506d02c1e8fca3f0f02c1afbd0301138a6b3a9111414f67dcea97f2cfcef0b4e673" +
        "96ac7422e7ad3585e2f937e81c75df6e47f11a711d29c5896fb7620eaa18be1bfc563e4bc6d279209adbc0fe78cd5af4" +
        "1fdda8338807c731b11210592780ec5f60517fa919b54a0d2de57a9f93c99cefa0e03b4dae2af5b0c8ebbb3c83539961" +
        "172b047eba77d626e169146355210c7d"
)

private val FORWARD_AES_SBOX = IntArray(256).also { table ->
    for (encrypted in 0 until 256) table[INVERSE_AES_SBOX[encrypted]] = encrypted
}

/**
 * fairplay_message.go: inverse AES rounds with custom middle round keys, so
 * javax.crypto/BouncyCastle AES cannot express this block transform.
 */
internal object FairPlaySapMessage {
    /** Mode 0..3; the receiver selects one in m2. */
    const val FAIRPLAY_MODE_COUNT = 4

    fun decryptFairPlayMessage(message: ByteArray, plaintext: ByteArray) {
        val mode = message[12].toInt() and 0xff
        for (step in 0 until 8) {
            // Mode 3 historically traverses the CBC chain backwards. This is
            // equivalent for separate buffers and also permits in-place use.
            val block = if (mode == 3) 7 - step else step
            val start = 16 + block * 16
            val state = IntArray(16) { message[start + it].toInt() and 0xff }
            decryptFairPlayMessageBlock(state, mode)

            for (i in 0 until 16) {
                val chain = if (block >
                    0
                ) {
                    message[start - 16 + i].toInt() and 0xff
                } else {
                    FAIRPLAY_MESSAGE_IV[mode * 16 + i]
                }
                plaintext[block * 16 + i] = (state[i] xor chain).toByte()
            }
        }
    }

    private fun decryptFairPlayMessageBlock(state: IntArray, mode: Int) {
        xorAESRoundKey(state, FAIRPLAY_MESSAGE_ROUND_KEY10, 0)
        for (round in 9 downTo 1) {
            inverseAESShiftRows(state)
            for (i in 0 until 16) state[i] = INVERSE_AES_SBOX[state[i]]
            xorAESRoundKey(state, FAIRPLAY_MESSAGE_MIDDLE_KEYS, (mode * 9 + round - 1) * 16)
            inverseAESMixColumns(state)
        }
        inverseAESShiftRows(state)
        for (i in 0 until 16) state[i] = INVERSE_AES_SBOX[state[i]]
        xorAESRoundKey(state, FAIRPLAY_MESSAGE_ROUND_KEY0, 0)
    }

    /**
     * Applies the inverse of [decryptFairPlayMessage] to one 128-byte SAP value.
     * The output is the body stored at bytes 16:144 of an FPLY message.
     */
    fun encryptFairPlayMessage(mode: Int, plaintext: ByteArray, encrypted: ByteArray, offset: Int) {
        val chain = IntArray(16) { FAIRPLAY_MESSAGE_IV[mode * 16 + it] }
        for (block in 0 until 8) {
            val start = block * 16
            val state = IntArray(16) { (plaintext[start + it].toInt() and 0xff) xor chain[it] }
            encryptFairPlayMessageBlock(state, mode)
            for (i in 0 until 16) {
                encrypted[offset + start + i] = state[i].toByte()
                chain[i] = state[i]
            }
        }
    }

    private fun encryptFairPlayMessageBlock(state: IntArray, mode: Int) {
        xorAESRoundKey(state, FAIRPLAY_MESSAGE_ROUND_KEY0, 0)
        for (i in 0 until 16) state[i] = FORWARD_AES_SBOX[state[i]]
        aesShiftRows(state)
        for (round in 0 until 9) {
            aesMixColumns(state)
            xorAESRoundKey(state, FAIRPLAY_MESSAGE_MIDDLE_KEYS, (mode * 9 + round) * 16)
            for (i in 0 until 16) state[i] = FORWARD_AES_SBOX[state[i]]
            aesShiftRows(state)
        }
        xorAESRoundKey(state, FAIRPLAY_MESSAGE_ROUND_KEY10, 0)
    }

    private fun xorAESRoundKey(state: IntArray, key: IntArray, keyOffset: Int) {
        for (i in 0 until 16) state[i] = state[i] xor key[keyOffset + i]
    }

    private fun inverseAESShiftRows(state: IntArray) {
        val previous = state.copyOf()
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                state[4 * column + row] =
                    previous[4 * ((column - row + 4) and 3) + row]
            }
        }
    }

    private fun aesShiftRows(state: IntArray) {
        val previous = state.copyOf()
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                state[4 * column + row] =
                    previous[4 * ((column + row) and 3) + row]
            }
        }
    }

    private fun inverseAESMixColumns(state: IntArray) {
        for (column in 0 until 4) {
            val offset = column * 4
            val a = state[offset]
            val b = state[offset + 1]
            val c = state[offset + 2]
            val d = state[offset + 3]
            state[offset] =
                aesGFMultiply(a, 14) xor aesGFMultiply(b, 11) xor aesGFMultiply(c, 13) xor
                aesGFMultiply(d, 9)
            state[offset + 1] =
                aesGFMultiply(a, 9) xor aesGFMultiply(b, 14) xor aesGFMultiply(c, 11) xor
                aesGFMultiply(d, 13)
            state[offset + 2] =
                aesGFMultiply(a, 13) xor aesGFMultiply(b, 9) xor aesGFMultiply(c, 14) xor
                aesGFMultiply(d, 11)
            state[offset + 3] =
                aesGFMultiply(a, 11) xor aesGFMultiply(b, 13) xor aesGFMultiply(c, 9) xor
                aesGFMultiply(d, 14)
        }
    }

    private fun aesMixColumns(state: IntArray) {
        for (column in 0 until 4) {
            val offset = column * 4
            val a = state[offset]
            val b = state[offset + 1]
            val c = state[offset + 2]
            val d = state[offset + 3]
            state[offset] = aesGFMultiply(a, 2) xor aesGFMultiply(b, 3) xor c xor d
            state[offset + 1] = a xor aesGFMultiply(b, 2) xor aesGFMultiply(c, 3) xor d
            state[offset + 2] = a xor b xor aesGFMultiply(c, 2) xor aesGFMultiply(d, 3)
            state[offset + 3] = aesGFMultiply(a, 3) xor b xor c xor aesGFMultiply(d, 2)
        }
    }

    private fun aesGFMultiply(factor: Int, multiplier: Int): Int {
        var a = factor
        var b = multiplier
        var product = 0
        while (b != 0) {
            if (b and 1 != 0) product = product xor a
            val high = a and 0x80
            a = (a shl 1) and 0xff
            if (high != 0) a = a xor 0x1b
            b = b shr 1
        }
        return product and 0xff
    }
}
