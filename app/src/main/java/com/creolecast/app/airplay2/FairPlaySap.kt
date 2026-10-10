// This file transcribes the reference implementation verbatim: hex constant tables and
// the obfuscated transform with its literal indices and rotations. Wrapping the tables or
// naming every literal would only make it harder to compare against the reference.
@file:Suppress("ktlint:standard:max-line-length", "MagicNumber")

package com.creolecast.app.airplay2

import com.creolecast.app.airplay2.FairPlaySapBytes.fairplayWordsBigEndian
import com.creolecast.app.airplay2.FairPlaySapBytes.fairplayWordsFromLittleEndian
import com.creolecast.app.airplay2.FairPlaySapBytes.readBigEndian32
import com.creolecast.app.airplay2.FairPlaySapBytes.readLittleEndian32
import com.creolecast.app.airplay2.FairPlaySapBytes.writeBigEndian32
import com.creolecast.app.airplay2.FairPlaySapBytes.writeLittleEndian64
import com.creolecast.app.airplay2.FairPlaySapHex.hexToBytes
import com.creolecast.app.airplay2.FairPlaySapMd5.MUTATION_KDF
import com.creolecast.app.airplay2.FairPlaySapMd5.fairplayMD5Compress
import com.creolecast.app.airplay2.FairPlaySapMessage.FAIRPLAY_MODE_COUNT
import com.creolecast.app.airplay2.FairPlaySapMessage.decryptFairPlayMessage
import com.creolecast.app.airplay2.FairPlaySapMessage.encryptFairPlayMessage
import com.creolecast.app.airplay2.FairPlaySapNetwork.FAIRPLAY_INITIAL_SESSION_KEY
import com.creolecast.app.airplay2.FairPlaySapNetwork.fpsapDescriptorForSAP
import com.creolecast.app.airplay2.FairPlaySapNetwork.fpsapExchangeForSAP
import java.security.SecureRandom
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter

// Sender side of the FairPlay SAP (/fp-setup) handshake.
//
// Receivers that advertise feature bit 14 (FeatureFPSAP25) reject SETUP with
// RTSP 455 until this two-phase exchange has completed. The transform is a
// line-for-line transcription of the doubletake Go reference
// (internal/airplay/{fairplay,fpsap,fpsap_tables,fairplay_message,fairplay_md5,
// fairplay_crypto,fairplay_sap}.go), which is GPL-3 like this project.
//
// The HTTP transport is the caller's responsibility: POST message1() to
// /fp-setup with "X-Apple-ET: 32", feed the reply to exchangeM3(), POST that,
// feed the reply to finish(), then wrapKey() the stream key.
//
// Every byte value is carried in an Int masked to 0..255 so that Go's unsigned
// byte arithmetic (wrapping add/sub/multiply, unsigned divide and shift)
// transcribes directly; Kotlin's signed Byte would silently differ.

/** Result of a completed FairPlay SAP handshake. */
class FairPlayKeys(val ekey: ByteArray, val eiv: ByteArray)

// fpsap.go
private val FPSAP_M1_PAYLOAD = hexToBytes("020003bb")
private val FPSAP_M3_LABEL = hexToBytes("8f1a9c")

// fairplay_crypto.go
private val FAIRPLAY_KDF_PREFIX = hexToBytes("fa9cad4d4b68268c7ff38899de922e951e")
private val FAIRPLAY_KDF_SUFFIX = hexToBytes("ec4e275efdf2e83097ae70fbe0003f1c39")
private val FAIRPLAY_EKEY_HEADER = hexToBytes("46504c5901020100000000 3c00000000".replace(" ", ""))

// ---------------------------------------------------------------------------
// fairplay_crypto.go: session key derivation and the 72-byte ekey record.
// ---------------------------------------------------------------------------

private fun deriveFairPlayWrappingKey(receiverSAP: ByteArray, message: ByteArray): ByteArray {
    val decrypted = ByteArray(128)
    decryptFairPlayMessage(message, decrypted)

    // The KDF input is a 290-byte protocol record followed by ordinary MD5
    // padding. The compression itself is FairPlay's modified MD5/SAP-hash
    // combination, not a standard MD5 digest.
    val material = ByteArray(320)
    var offset = 0
    FAIRPLAY_KDF_PREFIX.copyInto(material, offset)
    offset += FAIRPLAY_KDF_PREFIX.size
    decrypted.copyInto(material, offset)
    offset += decrypted.size
    receiverSAP.copyInto(material, offset)
    offset += receiverSAP.size
    FAIRPLAY_KDF_SUFFIX.copyInto(material, offset)
    offset += FAIRPLAY_KDF_SUFFIX.size
    material[offset] = 0x80.toByte()
    writeLittleEndian64(material, material.size - 8, offset.toLong() * 8)

    val state = fairplayWordsFromLittleEndian(FAIRPLAY_INITIAL_SESSION_KEY)
    var blockOffset = 0
    while (blockOffset < material.size) {
        val modified = fairplayMD5Compress(state, material, blockOffset, MUTATION_KDF)
        val hashed = fairplaySAPHash(material, blockOffset)
        for (word in 0 until 4) state[word] = modified[word] + readLittleEndian32(hashed, word * 4)
        blockOffset += 64
    }
    return fairplayWordsBigEndian(state)
}

/**
 * Emits the 72-byte AirPlay v3 record produced by Apple's FairPlay sender:
 *
 *     [0:16]  FPLY encrypted-key header
 *     [16:32] per-key random mask
 *     [32:36] big-endian raw-key length (16)
 *     [36:56] HMAC-SHA1(session MAC key, record[0:36] || raw key)
 *     [56:72] AES-wrapped (raw key XOR mask)
 */
private fun wrapFairPlayKey(
    receiverSAP: ByteArray,
    m3: ByteArray,
    rawKey: ByteArray,
    mask: ByteArray
): ByteArray {
    validateFPSAPRecord(m3, 3, 152, "m3")
    val mode = m3[12].toInt() and 0xff
    check(mode < FAIRPLAY_MODE_COUNT) { "unsupported FairPlay mode $mode" }

    val ekey = ByteArray(72)
    FAIRPLAY_EKEY_HEADER.copyInto(ekey, 0)
    mask.copyInto(ekey, 16)
    writeBigEndian32(ekey, 32, rawKey.size)

    val wrappingKey = deriveFairPlayWrappingKey(receiverSAP, m3)
    val masked = ByteArray(16) { (rawKey[it].toInt() xor mask[it].toInt()).toByte() }
    val cipher = AESEngine.newInstance()
    cipher.init(true, KeyParameter(wrappingKey))
    cipher.processBlock(masked, 0, ekey, 56)

    val senderSAP = ByteArray(128)
    decryptFairPlayMessage(m3, senderSAP)
    val macKey = fpsapDescriptorForSAP(senderSAP, receiverSAP)
    val mac = HMac(SHA1Digest())
    mac.init(KeyParameter(macKey))
    mac.update(ekey, 0, 36)
    mac.update(rawKey, 0, rawKey.size)
    mac.doFinal(ekey, 36)
    return ekey
}

// ---------------------------------------------------------------------------
// fpsap.go: FPLY record framing and the session lifecycle.
// ---------------------------------------------------------------------------

private fun newFPSAPRecord(messageType: Int, payloadLength: Int): ByteArray {
    val record = ByteArray(12 + payloadLength)
    record[0] = 'F'.code.toByte()
    record[1] = 'P'.code.toByte()
    record[2] = 'L'.code.toByte()
    record[3] = 'Y'.code.toByte()
    record[4] = 3
    record[5] = 1
    record[6] = messageType.toByte()
    record[7] = 0
    writeBigEndian32(record, 8, payloadLength)
    return record
}

private fun toHex(data: ByteArray, from: Int, to: Int): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder((to - from) * 2)
    for (i in from until to) {
        val value = data[i].toInt() and 0xff
        out.append(digits[value ushr 4]).append(digits[value and 15])
    }
    return out.toString()
}

private fun validateFPSAPRecord(
    record: ByteArray,
    messageType: Int,
    payloadLength: Int,
    label: String
) {
    val wantLength = 12 + payloadLength
    require(record.size == wantLength) {
        "invalid $label: length ${record.size}, want $wantLength"
    }
    val magic = record[0] == 'F'.code.toByte() && record[1] == 'P'.code.toByte() &&
        record[2] == 'L'.code.toByte() && record[3] == 'Y'.code.toByte()
    require(magic) { "invalid $label: invalid magic ${toHex(record, 0, 4)}" }
    val version = record[4].toInt() == 3 && record[5].toInt() == 1
    val type = (record[6].toInt() and 0xff) == messageType && record[7].toInt() == 0
    require(version && type) { "invalid $label: invalid version/type ${toHex(record, 4, 8)}" }
    val declared = readBigEndian32(record, 8)
    require(declared == payloadLength) {
        "invalid $label: declared payload length $declared, want $payloadLength"
    }
}

private fun decryptFPSAPBody(mode: Int, source: ByteArray, offset: Int): ByteArray {
    require(mode < FAIRPLAY_MODE_COUNT) { "unsupported FairPlay mode $mode" }
    val message = ByteArray(144)
    message[12] = mode.toByte()
    source.copyInto(message, 16, offset, offset + 128)
    val out = ByteArray(128)
    decryptFairPlayMessage(message, out)
    return out
}

/**
 * One FairPlay SAP authentication attempt.
 *
 * Apple's sender creates a single opaque context before m1 and reuses it for m3
 * and key wrapping; the same state is kept together here. The native
 * implementation fills the local SAP from an arc4random-seeded internal
 * generator and then overwrites its first two bytes with 00 01. Using the
 * caller's entropy source for the remaining 126 opaque bytes preserves those
 * protocol semantics without porting that PRNG.
 */
class FairPlaySapSession(private val random: SecureRandom = SecureRandom()) {
    private val localSAP = ByteArray(128)
    private val remoteSAP = ByteArray(128)
    private var m3: ByteArray? = null

    init {
        localSAP[1] = 1
        val entropy = ByteArray(126)
        random.nextBytes(entropy)
        entropy.copyInto(localSAP, 2)
    }

    /** First `/fp-setup` body to POST (with header `X-Apple-ET: 32`). */
    fun message1(): ByteArray {
        val m1 = newFPSAPRecord(1, FPSAP_M1_PAYLOAD.size)
        FPSAP_M1_PAYLOAD.copyInto(m1, 12)
        return m1
    }

    /** Feeds the receiver's m2 reply and returns the m3 body to POST next. */
    fun exchangeM3(m2: ByteArray): ByteArray {
        validateFPSAPRecord(m2, 2, 130, "m2")
        require(m2[12].toInt() == 2) { "invalid m2 payload marker ${m2[12].toInt() and 0xff}" }
        val mode = m2[13].toInt() and 0xff
        require(mode < FAIRPLAY_MODE_COUNT) { "m2 selected unsupported mode $mode" }

        val m3 = newFPSAPRecord(3, 152)
        m3[12] = mode.toByte()
        FPSAP_M3_LABEL.copyInto(m3, 13)
        encryptFairPlayMessage(mode, localSAP, m3, 16)

        val m2SAP = decryptFPSAPBody(mode, m2, 14)
        fpsapExchangeForSAP(localSAP, m2SAP).copyInto(m3, 144)
        m2SAP.copyInto(remoteSAP)
        this.m3 = m3
        return m3.copyOf()
    }

    /** Feeds the receiver's m4 reply to finish the handshake. */
    fun finish(m4: ByteArray) {
        val m3 = checkNotNull(this.m3) { "m3 has not been generated" }
        validateFPSAPRecord(m4, 4, 20, "m4")
        for (i in 0 until 20) {
            check(m4[12 + i] == m3[144 + i]) { "m4 confirmation does not match m3" }
        }
    }

    /**
     * Wraps a 16-byte stream key once the handshake has completed.
     *
     * Draws 32 bytes from the session entropy source: the stream IV first, then
     * the per-key mask that the ekey record carries at bytes 16:32.
     */
    fun wrapKey(streamKey: ByteArray): FairPlayKeys {
        val m3 = checkNotNull(this.m3) { "m3 has not been generated" }
        require(streamKey.size == 16) { "stream key length ${streamKey.size}, want 16" }
        val eiv = ByteArray(16)
        random.nextBytes(eiv)
        val mask = ByteArray(16)
        random.nextBytes(mask)
        return FairPlayKeys(wrapFairPlayKey(remoteSAP, m3, streamKey, mask), eiv)
    }
}
