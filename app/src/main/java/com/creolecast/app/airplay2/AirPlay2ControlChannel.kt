package com.creolecast.app.airplay2

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The RTSP control connection to the receiver. Once pairing keys it, every
 * byte in both directions is HAP framed (pairing.go:766-791).
 *
 * Requests are serialised on [lock], which [AirPlay2AudioStream] shares for its
 * nonce counter.
 */
internal class AirPlay2ControlChannel(
    private val config: AirPlay2ClientConfig,
    private val lock: Any
) : Closeable {

    private companion object {
        const val USER_AGENT = "AirPlay/935.7.1"
        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 10000

        /** Plaintext bytes per HAP frame (client.go:1100-1137). */
        const val HAP_FRAME_SIZE = 1024
        const val HAP_LENGTH_PREFIX_SIZE = 2
        const val HAP_KEY_SIZE = 32
        const val BYTE_MASK = 0xFF

        val STATUS_LINE = Regex("\\w+/\\d\\.\\d (\\d+)")
    }

    /** RTSP URL of the session; empty until the client starts connecting. */
    var sessionUrl = ""

    /** `Session` header value from the session SETUP reply; reset with the connection. */
    var sessionId: String? = null

    /** True once pairing keyed the channel; reset with the connection. */
    var encrypted = false
        private set

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private var cseq = 0
    private var writeKey: ByteArray? = null
    private var readKey: ByteArray? = null
    private var writeNonce = 0L
    private var readNonce = 0L

    /** Our address on the control connection, which the receiver should reach us on. */
    val localAddress: InetAddress?
        get() = socket?.localAddress

    /**
     * Open (or replace) the connection. Replacing it resets the HAP framing
     * state, since the keys belong to the old session.
     */
    @Suppress("MissingUseCall") // Lives until the next reopen()/close(); `use` would end it here.
    fun reopen() {
        close()
        encrypted = false
        writeKey = null
        readKey = null
        writeNonce = 0
        readNonce = 0
        cseq = 0
        sessionId = null
        val fresh = Socket()
        socket = fresh
        fresh.connect(InetSocketAddress(config.host, config.port), CONNECT_TIMEOUT_MS)
        fresh.tcpNoDelay = true
        fresh.soTimeout = READ_TIMEOUT_MS
        output = fresh.getOutputStream()
        input = HapInputStream(fresh.getInputStream())
    }

    /** From here the whole RTSP channel is HAP framed (pairing.go:766-791). */
    fun enableEncryption(sharedSecret: ByteArray) {
        val controlSalt = "Control-Salt".toByteArray(Charsets.UTF_8)
        writeKey = AirPlay2Crypto.hkdfSha512(
            controlSalt,
            sharedSecret,
            "Control-Write-Encryption-Key".toByteArray(Charsets.UTF_8),
            HAP_KEY_SIZE
        )
        readKey = AirPlay2Crypto.hkdfSha512(
            controlSalt,
            sharedSecret,
            "Control-Read-Encryption-Key".toByteArray(Charsets.UTF_8),
            HAP_KEY_SIZE
        )
        writeNonce = 0
        readNonce = 0
        encrypted = true
    }

    /** A request on [sessionUrl] carrying the DACP and session headers. */
    fun rtsp(
        method: String,
        contentType: String?,
        body: ByteArray?,
        extraHeaders: List<Pair<String, String>> = emptyList()
    ): RtspResponse {
        val headers = mutableListOf(
            "Client-Instance" to config.deviceId.replace(":", ""),
            "DACP-ID" to config.dacpId,
            "Active-Remote" to config.activeRemote
        )
        sessionId?.let { headers += "Session" to it }
        headers += extraHeaders
        return request(method, sessionUrl, contentType, body, headers)
    }

    fun request(
        method: String,
        target: String,
        contentType: String?,
        body: ByteArray?,
        extraHeaders: List<Pair<String, String>> = emptyList()
    ): RtspResponse {
        synchronized(lock) {
            val out = output ?: return RtspResponse.EMPTY
            val payload = body ?: ByteArray(0)
            val head = buildString {
                append("$method $target RTSP/1.0\r\n")
                append("CSeq: ${++cseq}\r\n")
                append("User-Agent: $USER_AGENT\r\n")
                for ((k, v) in extraHeaders) append("$k: $v\r\n")
                if (contentType != null && payload.isNotEmpty()) {
                    append("Content-Type: $contentType\r\n")
                }
                append("Content-Length: ${payload.size}\r\n")
                append("\r\n")
            }
            writeFramed(out, head.toByteArray(Charsets.UTF_8) + payload)
            out.flush()
            return readResponse()
        }
    }

    override fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
            // Best effort: the connection is being dropped either way.
        }
    }

    /**
     * HAP framing: 1024-byte plaintext chunks, each `[len LE16][ciphertext][tag]`
     * with the length prefix as AAD and an incrementing LE counter nonce
     * (client.go:1100-1137).
     */
    private fun writeFramed(out: OutputStream, data: ByteArray) {
        if (!encrypted) {
            out.write(data)
            return
        }
        val key = writeKey ?: return
        var offset = 0
        while (offset < data.size) {
            val n = minOf(HAP_FRAME_SIZE, data.size - offset)
            val lengthPrefix = byteArrayOf((n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte())
            val (ct, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(
                key,
                WireBytes.counterNonce(writeNonce),
                data.copyOfRange(offset, offset + n),
                lengthPrefix
            )
            out.write(lengthPrefix)
            out.write(ct)
            out.write(tag)
            writeNonce++
            offset += n
        }
    }

    private fun readResponse(): RtspResponse {
        val stream = input
        val head = stream?.let { RtspWire.readHead(it).lines }.orEmpty()
        if (stream == null || head.isEmpty()) return RtspResponse.EMPTY
        val statusCode = STATUS_LINE.find(head.first())?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val headers = RtspWire.parseHeaders(head)
        val length = headers.contentLength
        val body = if (length > 0) {
            ByteArray(length).takeIf { RtspWire.readFully(stream, it) == length }
        } else {
            null
        }
        return RtspResponse(statusCode, headers.values, body)
    }

    /** Transparently decrypts HAP frames once the channel is encrypted. */
    private inner class HapInputStream(private val raw: InputStream) : InputStream() {
        private var plain: ByteArray = ByteArray(0)
        private var pos = 0

        override fun read(): Int = when {
            !encrypted -> raw.read()
            pos >= plain.size && !fill() -> -1
            else -> plain[pos++].toInt() and BYTE_MASK
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = when {
            !encrypted -> raw.read(b, off, len)

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
            val key = readKey
            val frame = if (key != null) readFrame() else null
            if (key != null && frame != null) {
                plain = AirPlay2Crypto.chacha20Poly1305Decrypt(
                    key,
                    WireBytes.counterNonce(readNonce),
                    frame.sealed.copyOfRange(0, frame.length),
                    frame.lengthPrefix,
                    frame.sealed.copyOfRange(frame.length, frame.sealed.size)
                )
                pos = 0
                readNonce++
            }
            return frame != null && plain.isNotEmpty()
        }

        /** The next frame off the wire, or null if the stream ended inside it. */
        private fun readFrame(): HapFrame? {
            val lengthPrefix = ByteArray(HAP_LENGTH_PREFIX_SIZE)
            if (RtspWire.readFully(raw, lengthPrefix) < lengthPrefix.size) return null
            val length = (lengthPrefix[0].toInt() and 0xFF) or
                ((lengthPrefix[1].toInt() and 0xFF) shl 8)
            val sealed = ByteArray(length + WireBytes.AEAD_TAG_SIZE)
            val complete = RtspWire.readFully(raw, sealed) == sealed.size
            return if (complete) HapFrame(lengthPrefix, sealed, length) else null
        }
    }

    /** `[len LE16]` plus `[ciphertext][tag]`, where [length] is the ciphertext size. */
    private class HapFrame(val lengthPrefix: ByteArray, val sealed: ByteArray, val length: Int)
}
