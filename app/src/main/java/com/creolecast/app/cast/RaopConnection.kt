package com.creolecast.app.cast

import com.creolecast.app.AudioCastService
import com.creolecast.app.TrackMetadata
import com.creolecast.app.raop.NowPlayingTracker
import com.creolecast.app.raop.artworkContentType
import com.creolecast.app.raop.progressParameter
import java.net.Socket

/** TXT `md` values: the receiver shows text (0), artwork (1) and progress (2). */
private const val MD_TEXT = 0
private const val MD_ARTWORK = 1
private const val MD_PROGRESS = 2
private const val RAOP_SAMPLE_RATE = 44100

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

    /** Set once a password-protected receiver accepted our credentials. */
    @Volatile var digest: DigestAuth? = null

    /** TXT `md`: which kinds of now-playing info the receiver takes. */
    @Volatile var metadataCaps: Set<Int> = emptySet()

    private val nowPlaying = NowPlayingTracker()

    /**
     * Sends what changed of [metadata] and [artwork], as far as the receiver's `md` wants
     * them, stamped with the RTP time they apply from; progress is always refreshed.
     */
    fun sendNowPlaying(metadata: TrackMetadata, artwork: ByteArray?) {
        val rtpNow = sender?.nextPacket?.second ?: AudioCastService.LATENCY
        val rtpInfo = mapOf("RTP-Info" to "rtptime=${rtpNow.toUInt()}")
        if (MD_TEXT in metadataCaps) {
            nowPlaying.trackInfoIfChanged(
                metadata.title,
                metadata.artist,
                metadata.album,
                metadata.durationMs
            )?.let { dmap ->
                send(
                    "SET_PARAMETER",
                    rtpInfo + ("Content-Type" to "application/x-dmap-tagged"),
                    dmap
                )
            }
        }
        if (MD_ARTWORK in metadataCaps) {
            nowPlaying.artworkIfChanged(artwork)?.let { image ->
                send(
                    "SET_PARAMETER",
                    rtpInfo + ("Content-Type" to artworkContentType(image)),
                    image
                )
            }
        }
        val duration = metadata.durationMs?.takeIf { it > 0 }
        val position = metadata.positionMs
        if (MD_PROGRESS in metadataCaps && duration != null && position != null) {
            send(
                "SET_PARAMETER",
                mapOf("Content-Type" to "text/parameters"),
                progressParameter(rtpNow, position, duration, RAOP_SAMPLE_RATE)
            )
        }
    }

    /** Sends [method] with [headers] (plus Session and Content-Length) on the next CSeq. */
    fun send(method: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null) {
        val output = socket.getOutputStream()
        synchronized(this) {
            val all = buildMap {
                session?.let { put("Session", it) }
                putAll(headers)
                digest?.authorization(method, rtspUri(host, socket.port))?.let {
                    put("Authorization", it)
                }
                if (body != null) put("Content-Length", body.size.toString())
            }
            RtspWire.send(output, RtspRequest(method, host, socket.port, cseq++, all, body))
        }
    }
}
