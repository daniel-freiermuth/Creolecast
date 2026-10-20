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

        val STATUS_LINE = Regex("\\w+/\\d\\.\\d (\\d+)")
    }

    /** RTSP URL of the session; empty until the client starts connecting. */
    var sessionUrl = ""

    /** `Session` header value from the session SETUP reply; reset with the connection. */
    var sessionId: String? = null

    @Volatile private var cipher: HapCipher? = null

    /** True once pairing keyed the channel; reset with the connection. */
    val encrypted: Boolean get() = cipher != null

    /** The pairing's shared secret, which also keys the event channel. */
    var sharedSecret: ByteArray? = null
        private set

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private var cseq = 0

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
        cipher = null
        sharedSecret = null
        cseq = 0
        sessionId = null
        val fresh = Socket()
        socket = fresh
        fresh.connect(InetSocketAddress(config.host, config.port), CONNECT_TIMEOUT_MS)
        fresh.tcpNoDelay = true
        fresh.soTimeout = READ_TIMEOUT_MS
        output = fresh.getOutputStream()
        input = HapInputStream(fresh.getInputStream()) { cipher }
    }

    /** From here the whole RTSP channel is HAP framed (pairing.go:766-791). */
    fun enableEncryption(sharedSecret: ByteArray) {
        this.sharedSecret = sharedSecret
        cipher = HapCipher.forControl(sharedSecret)
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

    private fun writeFramed(out: OutputStream, data: ByteArray) {
        cipher?.write(out, data) ?: out.write(data)
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
}
