package com.creolecast.app.cast

import java.net.Socket

/**
 * The RTSP control connection of one RAOP session: the socket, the next CSeq and the
 * Session id from SETUP. Requests from the session, volume, metadata and teardown all go
 * through [send], so their CSeqs never collide. Responses after the handshake are read
 * (and discarded) by [RaopLiveness].
 */
internal class RaopConnection(val host: String, val socket: Socket) {
    private var cseq = 0

    /** RTSP Session id from the SETUP response. */
    @Volatile var session: String? = null

    /** The session's audio sender, once streaming (for FLUSH's RTP-Info). */
    @Volatile var sender: RaopAudioSender? = null

    /** Sends [method] with [headers] (plus Session and Content-Length) on the next CSeq. */
    fun send(method: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null) {
        val output = socket.getOutputStream()
        synchronized(this) {
            val all = buildMap {
                session?.let { put("Session", it) }
                putAll(headers)
                if (body != null) put("Content-Length", body.size.toString())
            }
            RtspWire.send(output, RtspRequest(method, host, socket.port, cseq++, all, body))
        }
    }
}
