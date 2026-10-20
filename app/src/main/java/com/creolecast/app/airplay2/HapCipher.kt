package com.creolecast.app.airplay2

import java.io.InputStream
import java.io.OutputStream

private const val HAP_KEY_SIZE = 32

/** Plaintext bytes per HAP frame (client.go:1100-1137). */
private const val HAP_FRAME_SIZE = 1024
private const val HAP_LENGTH_PREFIX_SIZE = 2
private const val BYTE_MASK = 0xFF

/**
 * HomeKit (HAP) session encryption of an AirPlay 2 TCP channel once pairing produced a
 * shared secret: 1024-byte plaintext chunks, each `[len LE16][ciphertext][tag]` with the
 * length prefix as AAD and an incrementing little-endian counter nonce per direction.
 */
internal class HapCipher private constructor(
    private val writeKey: ByteArray,
    private val readKey: ByteArray
) {
    private var writeNonce = 0L
    private var readNonce = 0L

    companion object {
        /** The RTSP control channel (pairing.go:766-791). */
        fun forControl(sharedSecret: ByteArray) = HapCipher(
            hkdf(sharedSecret, "Control-Salt", "Control-Write-Encryption-Key"),
            hkdf(sharedSecret, "Control-Salt", "Control-Read-Encryption-Key")
        )

        /**
         * The event channel. It is a reverse connection, the receiver sending the requests,
         * so the read and write keys swap roles (shairport-sync pair_homekit.c cipher_new).
         */
        fun forEvents(sharedSecret: ByteArray) = HapCipher(
            hkdf(sharedSecret, "Events-Salt", "Events-Read-Encryption-Key"),
            hkdf(sharedSecret, "Events-Salt", "Events-Write-Encryption-Key")
        )

        private fun hkdf(secret: ByteArray, salt: String, info: String) = AirPlay2Crypto.hkdfSha512(
            salt.toByteArray(Charsets.UTF_8),
            secret,
            info.toByteArray(Charsets.UTF_8),
            HAP_KEY_SIZE
        )
    }

    /** Writes [data] as HAP frames; the caller flushes. */
    @Synchronized
    fun write(out: OutputStream, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val n = minOf(HAP_FRAME_SIZE, data.size - offset)
            val lengthPrefix =
                byteArrayOf((n and BYTE_MASK).toByte(), ((n shr 8) and BYTE_MASK).toByte())
            val (ct, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(
                writeKey,
                WireBytes.counterNonce(writeNonce),
                data.copyOfRange(offset, offset + n),
                lengthPrefix
            )
            out.write(lengthPrefix + ct + tag)
            writeNonce++
            offset += n
        }
    }

    /** The plaintext of the next frame on [raw], or null if the stream ended inside it. */
    @Synchronized
    fun readFrame(raw: InputStream): ByteArray? {
        val lengthPrefix = ByteArray(HAP_LENGTH_PREFIX_SIZE)
        val length = if (RtspWire.readFully(raw, lengthPrefix) == lengthPrefix.size) {
            (lengthPrefix[0].toInt() and BYTE_MASK) or
                ((lengthPrefix[1].toInt() and BYTE_MASK) shl 8)
        } else {
            return null
        }
        val sealed = ByteArray(length + WireBytes.AEAD_TAG_SIZE)
        return if (RtspWire.readFully(raw, sealed) == sealed.size) {
            AirPlay2Crypto.chacha20Poly1305Decrypt(
                readKey,
                WireBytes.counterNonce(readNonce++),
                sealed.copyOfRange(0, length),
                lengthPrefix,
                sealed.copyOfRange(length, sealed.size)
            )
        } else {
            null
        }
    }
}

/** Reads [raw] in the clear until [cipher] returns one, then decrypts its HAP frames. */
internal class HapInputStream(private val raw: InputStream, private val cipher: () -> HapCipher?) :
    InputStream() {
    private var plain: ByteArray = ByteArray(0)
    private var pos = 0

    override fun read(): Int = when {
        cipher() == null -> raw.read()
        pos >= plain.size && !fill() -> -1
        else -> plain[pos++].toInt() and BYTE_MASK
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = when {
        cipher() == null -> raw.read(b, off, len)

        len == 0 -> 0

        pos >= plain.size && !fill() -> -1

        else -> {
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            n
        }
    }

    private fun fill(): Boolean {
        var frame: ByteArray
        do {
            frame = cipher()?.readFrame(raw) ?: return false
        } while (frame.isEmpty())
        plain = frame
        pos = 0
        return true
    }
}
