// Part of the FairPlay SAP transcription (see FairPlaySap.kt): literal indices,
// rotations and constants are kept verbatim so the code stays comparable with the
// reference; naming every literal would only obscure that.
@file:Suppress("MagicNumber")

package com.creolecast.app.airplay2

import com.creolecast.app.airplay2.FairPlaySapBits.cube
import com.creolecast.app.airplay2.FairPlaySapBits.majority
import com.creolecast.app.airplay2.FairPlaySapBits.not8
import com.creolecast.app.airplay2.FairPlaySapBits.rotateOrZero
import com.creolecast.app.airplay2.FairPlaySapBits.rotl8
import com.creolecast.app.airplay2.FairPlaySapBits.selectBits
import com.creolecast.app.airplay2.FairPlaySapBits.square
import com.creolecast.app.airplay2.FairPlaySapHex.hexToInts

// fairplay_sap.go
private val SAP_HASH_INIT = hexToInts("965fc653f846cc18dfbeb2f83862ec2293d1208f")
private val SAP_MATRIX_INIT =
    hexToInts("4354627a18c3d6b39a56f61c143f0c1d3b3683b139514aaa093efe44afdec3209d42b8")
private val SAP_SEED = hexToInts("ed25d1bbbc279f02a2a911000cb352c0bde31b49c7")

private fun wideSeed(input: Int, count: Int): Int {
    if ((count and 0xff) == 0) return SAP_SEED[0]
    return SAP_SEED[((input shl count) or (input ushr (8 - count))) % 21]
}

/** fairplay_sap.go: FairPlay's proprietary SAP hash. Not a standard hash. */
internal fun fairplaySAPHash(block: ByteArray, blockOffset: Int): ByteArray {
    val hash = SAP_HASH_INIT.copyOf()
    val matrix = SAP_MATRIX_INIT.copyOf()
    val aux = IntArray(10)
    val work = IntArray(210)

    // Load input in reversed four-byte groups.
    for (i in 0 until 210) work[i] = block[blockOffset + ((i and 63) xor 3)].toInt() and 0xff

    // uint32 underflow changes the first of four scramble passes.
    for (i in 0 until 840) {
        val x = work[(((i.toLong() - 155) and 0xffffffffL) % 210L).toInt()]
        val y = work[(((i.toLong() - 57) and 0xffffffffL) % 210L).toInt()]
        val z = work[(((i.toLong() - 13) and 0xffffffffL) % 210L).toInt()]
        val w = work[i % 210]
        work[i % 210] = (rotl8(y, 5) + (rotl8(z, 3) xor w) - rotl8(x, 7)) and 0xff
    }

    sapNonlinearCircuit(hash, matrix, aux, work)

    val out = IntArray(16)
    // Include terminal work XORs directly in their folded output lanes.
    for (i in 0 until 3) out[i] = aux[i]
    for (i in 0 until 7) out[4 + i] = aux[3 + i]
    for (i in 0 until 16) out[i] = (out[i] + 0xe1) and 0xff
    out[3] = 0x3d
    out[11] = 0x3c
    out[10] = out[10] xor aux[3] xor 133

    for (i in 0 until 210) {
        var value = work[i]
        if (i < 35) value = value xor matrix[i]
        if (i < 20) value = value xor hash[i]
        out[i and 15] = out[i and 15] xor value
    }

    // Reverse scramble
    for (i in 0 until 256) {
        out[i and 15] = out[i and 15] xor
            rotl8(out[(i - 7) and 15], 1) xor
            rotl8(out[(i - 5) and 15], 6) xor
            rotl8(out[(i - 1) and 15], 5)
    }

    return ByteArray(16) { out[it].toByte() }
}

/** The four SAP registers with the reference's indexed accessors. */
private class SapRegisters(
    val hash: IntArray,
    val matrix: IntArray,
    val aux: IntArray,
    val work: IntArray
) {
    // h/m/s read hash/matrix/seed through work; ma reads matrix through aux.
    fun hi(i: Int): Int = hash[i % 20]
    fun si(i: Int): Int = SAP_SEED[i % 21]
    fun h(i: Int): Int = hi(work[i])
    fun m(i: Int): Int = matrix[work[i] % 35]
    fun s(i: Int): Int = si(work[i])
    fun ma(i: Int): Int = matrix[aux[i] % 35]
}

/**
 * Arithmetic wraps as bytes unless explicitly promoted before division or indexing.
 * The reference is one straight-line block; it runs here as consecutive steps in
 * the original statement order, split where no local value crosses a boundary.
 */
private fun sapNonlinearCircuit(hash: IntArray, matrix: IntArray, aux: IntArray, work: IntArray) {
    val registers = SapRegisters(hash, matrix, aux, work)
    registers.sapCircuitStep1()
    registers.sapCircuitStep2()
    registers.sapCircuitStep3()
    registers.sapCircuitStep4()
    registers.sapCircuitStep5()
    registers.sapCircuitStep6()
    registers.sapCircuitStep7()
}

private fun SapRegisters.sapCircuitStep1() {
    matrix[12] = (0x14 + (selectBits(92, work[64], work[99] / 3) and wideSeed(s(206), 4))) and 0xff
    work[4] = (2 * square(work[99] / 5)) and 0xff
    work[153] = work[153] xor ((square(m(203)) * work[190]) and 0xff)
    hash[3] = 0x13 xor ((s(205) shr 1) and 0x10)
    work[33] = (work[33] - (s(36) and 9.inv())) and 0xff
    aux[5] =
        (((m(67) and 2.inv()) or 1 or ((h(181) shr 6) and 2) or (hash[3] and 0x10)) - 15) and 0xff
    matrix[12] = 0x07
    work[2] = (work[2] - 64) and 0xff
    hash[19] = s(58)
    aux[4] = (92 - m(32)) and 0xff
    aux[9] = (m(15) + 0x9e) and 0xff
    work[34] = (work[34] + si(aux[9]) / 5) and 0xff
    hash[19] = (hash[19] + (0xe6 xor ((hi(aux[9]) shr 1) and 0x66))) and 0xff
    work[15] = work[15] xor ((3 * rotateOrZero(work[72], -s(190) and 7) - 9 * s(126)) and 0xff)
    hash[15] = hash[15] xor cube(m(181))
    matrix[4] = matrix[4] xor (work[202] / 3)
    matrix[1] =
        (matrix[1] + cube(majority((92 - hi(aux[4])) and 0xff, not8(work[105]), 0xc6))) and 0xff
    hash[19] = hash[19] xor (((224 or (s(92) and 27)) * m(41) / 3) and 0xff)
    work[140] = (work[140] + rotateOrZero(92, -work[5] and 7)) and 0xff
    matrix[12] = (matrix[12] + majority(not8(work[4]) xor m(12), work[182], 192)) and 0xff
    work[36] = (work[36] + 125) and 0xff
    work[124] = rotl8(majority(majority(work[138], hash[15], 74), h(43), 95), 4)
}

private fun SapRegisters.sapCircuitStep2() {
    val auxHash = hi(aux[9])
    aux[1] = 0x4c and (auxHash and ((s(68) shl 1) and 0xff)).inv() and 0xff
    aux[2] = (
        222 - majority(
            ((work[177] + s(79)) shr 1) and 0xff,
            (3 * work[148] / 5) and 0xff,
            matrix[1]
        )
        ) and 0xff
    matrix[16] =
        (matrix[16] + (((ma(4) and 0x60.inv()) or auxHash or 8) - (rotl8(work[33], 2) or 128))) and
        0xff
    hash[14] = hash[14] xor ma(2)
    work[19] = (
        work[19] + majority(
            rotateOrZero(si(h(201)), (m(112) shl 1) and 6),
            ((h(208) and 0x7c.inv()) or (h(164) and 0x7c)) / 5,
            37
        )
        ) and 0xff
    matrix[8] = rotateOrZero(140, -square(s(45)) and 7) xor aux[4]
    work[190] = 56
    work[53] = not8((h(83) or 204) / 5)
    hash[13] = (hash[13] + h(41)) and 0xff
    hash[10] = majority(ma(4), work[2], aux[2]) / 15
    aux[3] = (92 - square(0x28 or (ma(1) and (0x12 or (s(2) and 4))))) and 0xff
}

private fun SapRegisters.sapCircuitStep3() {
    val seedBits = si(aux[4])
    matrix[13] = matrix[13] xor seedBits
    aux[6] = (92 + square(majority((m(179) - 38) and 0xff, aux[2], 177))) and 0xff
    val expansionBits = majority((aux[3] + (aux[4] and 74)) and 0xff, not8(seedBits), 121)
    work[47] = work[47] xor ((m(89) + majority(expansionBits xor 0xa6, aux[4], 4)) and 0xff)
    aux[7] = (
        seedBits / 3 - ma(9) -
            (0x14 or (work[151] and ((aux[4] and 0x88) or 0x62)) or (aux[4] and 0x22))
        ) and 0xff
    val expandedSelector = expansionBits xor ((aux[4] and 0xca) shr 1) xor 75
    aux[9] = (
        aux[9] + (
            0x80 or (majority(aux[7], work[151], 0x20) and 0x64) or
                (seedBits and 0x44) or (ma(9) and 0x1b)
            )
        ) and 0xff
    matrix[33] = matrix[33] xor work[26]
    matrix[30] = ((aux[9] / 3 - ((aux[4] and 8.inv()) or 0x13)) and 0xff) xor h(122)
    work[22] = (m(90) and 0x1b) or 0x44
    val wide = selectBits(71, matrix[expandedSelector % 35], si(aux[5]))
    matrix[18] = (matrix[18] + ((wide * wide * wide shr 1) and 0xff)) and 0xff
    matrix[5] = (matrix[5] - s(92)) and 0xff
    matrix[18] = matrix[18] xor (
        (
            selectBits(aux[3], ma(3), selectBits(16, m(183), work[41])) *
                selectBits(expandedSelector, h(59), work[17])
            ) and 0xff
        )
    matrix[22] = (
        majority(
            selectBits(hash[14] or 28, (work[7] and 28) or 0x82, h(93)),
            rotateOrZero(ma(4), rotateOrZero(work[11], -m(28) and 7) and 7),
            matrix[33]
        ) + 74
        ) and 0xff
    hash[15] =
        (hash[15] - majority(majority(aux[3], aux[4], 214), si(h(39) xor 217), aux[6])) and 0xff
}

private fun SapRegisters.sapCircuitStep4() {
    val hash9 = hi(aux[9])
    val indexedHash = hi(
        (((aux[4] / 3) - (aux[9] or work[22])) and 0xff) xor aux[6] xor
            (
                ((m(57) or hash9) and (0x52 or (aux[9] and 0x0d))) or
                    (((m(57) and hash9) or aux[9]) and 0x20)
                )
    )
    aux[6] = square(square(h(99))) or ma(9)
    aux[1] = (
        aux[1] + rotateOrZero(h(151) or s(202), h(50) and 7) + majority(
            h(4),
            (
                (
                    selectBits(
                        matrix[16],
                        indexedHash,
                        m(138)
                    ) + selectBits(17, work[33], s(39))
                    ) / 5
                ) and
                0xff,
            147
        )
        ) and 0xff
    aux[0] = selectBits(
        hash[10] and 7,
        ma(6) and h(209),
        selectBits(0x47, rotateOrZero(s(127), ma(6) and 7), (si(ma(5)) shl 1) and 0xff)
    )
}

private fun SapRegisters.sapCircuitStep5() {
    val selectedSquare = selectBits(198, square(m(14)), h(145) xor aux[0])
    val seed9 = si(aux[9])
    val hash3 = hi(aux[3])
    matrix[2] =
        (
            matrix[2] + (
                (((hash3 shl 1) and 0xff) and ((work[25] and 0x96) or (seed9 and 8))) or
                    (seed9 and 0x40)
                )
            ) and 0xff
    matrix[14] = (matrix[14] - selectBits(34, work[97], ma(3) and (aux[0] xor m(100)))) and 0xff
    work[23] =
        work[23] xor ((majority(majority(s(17), hash3, aux[0]), work[50] / 3, 0x76) shl 1) and 0xff)
    hash[17] = 115
    hash[13] = ((majority(hi(aux[7]), work[10], 82) shr 1) and 0x68) or (h(39) and 0x17)
    matrix[33] = (matrix[33] - (work[113] and 9)) and 0xff
    matrix[28] = (matrix[28] - ((aux[3] and 0x20.inv()) or ((work[110] shr 1) and 0x20))) and 0xff
    work[95] = si(aux[3])
    hash[15] = majority((work[95] - 48) and 0xff, not8(work[184]), 189) and
        cube(majority(aux[7], si(aux[1]), 0xaa))
    matrix[22] = (matrix[22] + work[183]) and 0xff
    aux[4] = aux[4] xor ((3 * s(1)) and 0xff)
    aux[5] = (aux[5] + 198 * majority(s(178), ma(1), 209) * h(13) * (s(26) shr 1)) and 0xff
    aux[8] = selectBits(10, ma(3), ma(9))
    matrix[18] = (matrix[18] - selectBits(hash[15], aux[5] / 15, cube(hi(aux[6]) or 81))) and 0xff
    aux[1] = (aux[1] + ((si(hi(aux[1])) / 3 - h(160)) and 0xff)) and 0xff
    hash[16] = (
        147 - majority(
            aux[0],
            majority(s(69), work[172], (aux[2] - selectedSquare + 77) and 0xff),
            0xc2 or (aux[0] and 5)
        )
        ) and 0xff
    hash[3] =
        (hash[3] - wideSeed(majority(s(155), work[105], 141), majority(s(168), h(29), 6) and 7)) and
        0xff
    work[5] = rotateOrZero(0x38, -(h(61) / 5) and 7) xor (not8(ma(8)) / 5)
    work[198] = (work[198] + work[3]) and 0xff
    val wide = 162 or ma(9)
    work[164] = (work[164] + ((wide * wide / 5) and 0xff)) and 0xff
    aux[2] = majority(rotateOrZero(139, -aux[5] and 6), hi(aux[3]), 12) or
        selectBits(95, cube(seed9), hi(aux[7]))
}

private fun SapRegisters.sapCircuitStep6() {
    matrix[12] =
        (matrix[12] + (16 or ((work[103] or 60) and (aux[2] or (work[103] and 32)))) / 3) and 0xff
    work[143] = (
        work[143] - (
            0x12 or (
                selectBits(
                    aux[9],
                    selectBits(matrix[8], work[35], aux[7]),
                    aux[8] / 3
                ) and (0x4d or ((work[172] shr 1) and 0x20))
                )
            )
        ) and 0xff
    matrix[29] = 162
    hash[15] = (
        hash[15] + majority(
            m(149) xor square(work[43]),
            selectBits(95, h(125), si(aux[1])) shr 1,
            115
        )
        ) and 0xff
    aux[9] = (aux[9] - hi(aux[7])) and 0xff
    hash[7] = (hash[7] - square(rotateOrZero(ma(5), -m(17) * (m(17) and 1)))) and 0xff
    matrix[8] = (matrix[8] + cube(s(202)) - work[184]) and 0xff
    hash[16] = (m(102) shl 1) and 0x84
    aux[6] = aux[6] xor (si(aux[7]) shr 1)
}

private fun SapRegisters.sapCircuitStep7() {
    hash[7] =
        (
            hash[7] - (
                (
                    h(
                        191
                    ) - selectBits(177, si(si(aux[1])), (s(80) shl 1) and 0xff)
                    ) and 0xff
                )
            ) and
        0xff
    hash[6] = h(119)
    hash[12] = (
        (hi(aux[8]) xor ((m(71) + m(15)) and 0xff)) and
            majority((work[118] and 0x2c.inv()) or 2, square(hi(aux[9])), 27)
        )
    val digestIndex = selectBits(0xa9, (s(57) * 231) and 0xff, majority(work[32], ma(1), 23)) / 5
    val seedSample = si(aux[6])
    aux[5] = majority(
        (seedSample and 0x1c) or (h(82) and 0xa2) or (si(digestIndex) and 0x41),
        majority(cube(hi(aux[7])), work[82], 92),
        192
    ) xor digestIndex
    matrix[25] = matrix[25] xor (
        (
            (2 * hi(aux[9]) * work[5]) -
                (rotateOrZero(aux[4], seedSample and 7) and ((aux[3] + 110) and 0xff))
            ) and 0xff
        )
}
