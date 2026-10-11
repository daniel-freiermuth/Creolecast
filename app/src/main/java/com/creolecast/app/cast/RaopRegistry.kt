package com.creolecast.app.cast

import android.util.Log
import com.creolecast.app.CastDestination
import com.creolecast.app.TrackMetadata
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Collections
import java.util.Locale

/** User-Agent of RAOP TEARDOWN requests. */
private const val TEARDOWN_USER_AGENT = "AirPlay/366.0"

/** Length prefix of one DMAP tag value. */
private const val DMAP_LENGTH_BYTES = 4

/**
 * The live RAOP (AirPlay 1) control connections, keyed by receiver host: the RTSP socket,
 * its next CSeq and the RTSP Session id from SETUP. Volume and metadata commands and
 * teardown are sent through here while [RaopSession] streams audio.
 *
 * The maps are mutated from independent Dispatchers.IO coroutines (the session, the
 * metadata refresh timer and volume commands run concurrently); synchronizedMap rather
 * than ConcurrentHashMap because the session id is legitimately null.
 */
internal class RaopRegistry {
    private val sockets = Collections.synchronizedMap(mutableMapOf<String, Socket>())
    private val cseqs = Collections.synchronizedMap(mutableMapOf<String, Int>())
    private val sessions = Collections.synchronizedMap(mutableMapOf<String, String?>())

    fun register(host: String, socket: Socket) {
        sockets[host] = socket
    }

    fun setCSeq(host: String, cseq: Int) {
        cseqs[host] = cseq
    }

    fun setSession(host: String, session: String?) {
        sessions[host] = session
    }

    /** Sends an absolute volume in dB (-30.0 silent to 0.0 max) to every receiver. */
    fun sendVolume(dB: Double) {
        val snapshot = synchronized(sockets) { sockets.toMap() }
        snapshot.forEach { (host, socket) ->
            try {
                sendVolumeTo(host, socket, "volume: ${String.format(Locale.US, "%.6f", dB)}\r\n")
            } catch (e: IOException) {
                Log.d(LOG_TAG, "RAOP volume to $host failed: ${e.reason}")
            }
        }
    }

    /** Sends the track's DMAP-tagged title/artist/album to the receiver at [host]. */
    fun sendMetadata(host: String, metadata: TrackMetadata) {
        val socket = sockets[host] ?: return
        val output = socket.getOutputStream()
        val session = sessions[host].orEmpty()
        val dmap = encodeDmapMetadata(metadata)
        if (dmap.isNotEmpty()) {
            try {
                synchronized(output) {
                    val cseq = cseqs[host] ?: 1
                    RtspWire.send(
                        output,
                        RtspRequest(
                            "SET_PARAMETER",
                            host,
                            socket.port,
                            cseq,
                            mapOf(
                                "Session" to session,
                                "Content-Type" to "application/x-dmap-tagged",
                                "Content-Length" to dmap.size.toString()
                            )
                        )
                    )
                    output.write(dmap)
                    output.flush()
                    cseqs[host] = cseq + 1
                }
            } catch (e: IOException) {
                Log.d(LOG_TAG, "RAOP metadata to $host failed: ${e.reason}")
            }
        }
    }

    /** Sends TEARDOWN for [dest] if its control connection is still open. */
    fun teardown(dest: CastDestination) {
        captureTeardown(dest)?.invoke()
    }

    /**
     * Takes [dest]'s control connection and session id now and returns the TEARDOWN send
     * for them, or null without a connection. Stopping a cast runs that send on another
     * thread, after [clear] may already have emptied the registry; looking the connection
     * up only then would find nothing and silently skip the TEARDOWN.
     */
    fun captureTeardown(dest: CastDestination): (() -> Unit)? {
        val socket = sockets[dest.host] ?: return null
        val session = sessions[dest.host].orEmpty()
        val capturedCSeq = cseqs[dest.host]
        return send@{
            if (socket.isClosed) return@send
            try {
                val output = socket.getOutputStream()
                synchronized(output) {
                    // The live CSeq while the socket is still registered, so a volume or
                    // metadata command sent since the capture is honoured; never another
                    // connection's.
                    val cseq = if (sockets[dest.host] === socket) cseqs[dest.host] else capturedCSeq
                    RtspWire.send(
                        output,
                        RtspRequest(
                            "TEARDOWN",
                            dest.host,
                            dest.port,
                            cseq ?: 1,
                            mapOf(
                                "Session" to session,
                                "User-Agent" to TEARDOWN_USER_AGENT
                            )
                        )
                    )
                }
            } catch (e: IOException) {
                Log.d(LOG_TAG, "RAOP TEARDOWN to ${dest.host} failed: ${e.reason}")
            }
        }
    }

    /** Closes and forgets the control connection to [host] after a failed session. */
    fun forget(host: String) {
        sockets.remove(host)?.close()
        sessions.remove(host)
        cseqs.remove(host)
    }

    fun snapshotSockets(): List<Socket> = synchronized(sockets) { sockets.values.toList() }

    fun clear() {
        sockets.clear()
        sessions.clear()
        cseqs.clear()
    }

    /** SET_PARAMETER with a text/parameters [volume] line, on the next CSeq. */
    private fun sendVolumeTo(host: String, socket: Socket, volume: String) {
        val output = socket.getOutputStream()
        val session = sessions[host].orEmpty()
        synchronized(output) {
            val cseq = cseqs[host] ?: 1
            RtspWire.send(
                output,
                RtspRequest(
                    "SET_PARAMETER",
                    host,
                    socket.port,
                    cseq,
                    mapOf(
                        "Session" to session,
                        "Content-Type" to "text/parameters",
                        "Content-Length" to volume.length.toString()
                    ),
                    volume
                )
            )
            cseqs[host] = cseq + 1
        }
    }
}

/** DMAP-tagged title (minm), artist (asar) and album (asal); absent fields are omitted. */
private fun encodeDmapMetadata(metadata: TrackMetadata): ByteArray {
    val out = ByteArrayOutputStream()
    fun writeTag(tag: String, value: String?) {
        if (value == null) return
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        out.write(tag.toByteArray())
        out.write(ByteBuffer.allocate(DMAP_LENGTH_BYTES).putInt(valueBytes.size).array())
        out.write(valueBytes)
    }
    writeTag("minm", metadata.title)
    writeTag("asar", metadata.artist)
    writeTag("asal", metadata.album)
    return out.toByteArray()
}
