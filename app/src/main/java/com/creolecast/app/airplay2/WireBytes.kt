package com.creolecast.app.airplay2

/** Byte-level helpers shared by the RTP, NTP, PTP and HAP framing code. */
internal object WireBytes {
    /** ChaCha20-Poly1305 nonce size (RFC 8439). */
    const val CHACHA_NONCE_SIZE = 12

    /** AirPlay nonces carry 4 zero bytes before their 8 bytes of material. */
    const val CHACHA_NONCE_PREFIX_SIZE = 4

    /** Poly1305 authentication tag appended to every sealed message. */
    const val AEAD_TAG_SIZE = 16

    /** Writes the low [byteCount] bytes of [value] big-endian at [offset]. */
    fun putBigEndian(buf: ByteArray, offset: Int, value: Long, byteCount: Int) {
        for (i in 0 until byteCount) {
            buf[offset + i] = (value ushr ((byteCount - 1 - i) * Byte.SIZE_BITS)).toByte()
        }
    }

    fun putUInt16(buf: ByteArray, offset: Int, value: Int) =
        putBigEndian(buf, offset, value.toLong(), Short.SIZE_BYTES)

    fun putUInt32(buf: ByteArray, offset: Int, value: Long) =
        putBigEndian(buf, offset, value, Int.SIZE_BYTES)

    fun putUInt64(buf: ByteArray, offset: Int, value: Long) =
        putBigEndian(buf, offset, value, Long.SIZE_BYTES)

    /** Nonce for HAP and audio framing: [counter] as 8 little-endian bytes after the prefix. */
    fun counterNonce(counter: Long): ByteArray {
        val nonce = ByteArray(CHACHA_NONCE_SIZE)
        for (i in 0 until Long.SIZE_BYTES) {
            nonce[CHACHA_NONCE_PREFIX_SIZE + i] = (counter ushr (i * Byte.SIZE_BITS)).toByte()
        }
        return nonce
    }

    /** Nonce for pair-verify: the 8-byte ASCII [label] (`PV-Msg02`) after the prefix. */
    fun labelNonce(label: String): ByteArray {
        val nonce = ByteArray(CHACHA_NONCE_SIZE)
        System.arraycopy(
            label.toByteArray(Charsets.UTF_8),
            0,
            nonce,
            CHACHA_NONCE_PREFIX_SIZE,
            CHACHA_NONCE_SIZE - CHACHA_NONCE_PREFIX_SIZE
        )
        return nonce
    }
}
