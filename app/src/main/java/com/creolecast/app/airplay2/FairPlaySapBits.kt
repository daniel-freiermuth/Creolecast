// Part of the FairPlay SAP transcription (see FairPlaySap.kt): literal indices,
// rotations and constants are kept verbatim so the code stays comparable with the
// reference; naming every literal would only obscure that.
@file:Suppress("MagicNumber")

package com.creolecast.app.airplay2

/** Byte arithmetic of the SAP hash circuit. Values are Ints holding 0..255. */
internal object FairPlaySapBits {
    fun rotl8(value: Int, count: Int): Int {
        val shift = count and 7
        return ((value shl shift) or (value ushr (8 - shift))) and 0xff
    }

    fun rotateOrZero(input: Int, count: Int): Int {
        val amount = count and 0xff
        return if (amount == 0) 0 else rotl8(input, amount)
    }

    fun majority(a: Int, b: Int, c: Int): Int = a xor ((a xor b) and (a xor c))

    fun selectBits(mask: Int, ifSet: Int, ifClear: Int): Int =
        ifClear xor ((ifSet xor ifClear) and mask)

    fun square(value: Int): Int = (value * value) and 0xff

    fun cube(value: Int): Int = (value * value * value) and 0xff

    fun not8(value: Int): Int = value.inv() and 0xff
}
