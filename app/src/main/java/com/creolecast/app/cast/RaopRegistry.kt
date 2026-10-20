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
 * The live RAOP (AirPlay 1) control connections, keyed by receiver host. Volume and
 * metadata commands, FLUSH and teardown are sent through here while [RaopSession] streams
 * audio.
 *
 * Mutated from independent Dispatchers.IO coroutines (the session, the metadata refresh
 * timer and volume commands run concurrently), hence synchronized.
 */
internal class RaopRegistry {
    private val connections = Collections.synchronizedMap(mutableMapOf<String, RaopConnection>())

    fun register(connection: RaopConnection) {
        connections[connection.host] = connection
    }

    /** Sends an AirPlay volume in dB (see [airPlayVolume]) to every receiver. */
    fun sendVolume(dB: Double) {
        val body = "volume: ${String.format(Locale.US, "%.6f", dB)}\r\n".toByteArray()
        snapshot().forEach { connection ->
            sendQuietly(connection, "volume") {
                send("SET_PARAMETER", mapOf("Content-Type" to "text/parameters"), body)
            }
        }
    }

    /** Sends the track's DMAP-tagged title/artist/album to the receiver at [host]. */
    fun sendMetadata(host: String, metadata: TrackMetadata) {
        val connection = connections[host] ?: return
        val dmap = encodeDmapMetadata(metadata)
        if (dmap.isEmpty()) return
        sendQuietly(connection, "metadata") {
            send("SET_PARAMETER", mapOf("Content-Type" to "application/x-dmap-tagged"), dmap)
        }
    }

    /**
     * FLUSH for every receiver: it drops the audio it has buffered (about 1.5 s), so a
     * pause takes effect at once instead of after the buffer played out.
     */
    fun flushAll() {
        snapshot().forEach { connection ->
            val sender = connection.sender ?: return@forEach
            sendQuietly(connection, "FLUSH") {
                val (sequence, rtpTime) = sender.nextPacket
                send("FLUSH", mapOf("RTP-Info" to "seq=$sequence;rtptime=${rtpTime.toUInt()}"))
                sender.markNextPacket()
            }
        }
    }

    /** Sends TEARDOWN for [dest] if its control connection is still open. */
    fun teardown(dest: CastDestination) {
        val connection = connections[dest.host]
        if (connection == null || connection.socket.isClosed) return
        sendQuietly(connection, "TEARDOWN") {
            send("TEARDOWN", mapOf("User-Agent" to TEARDOWN_USER_AGENT))
        }
    }

    /** Closes and forgets the control connection to [host] after a failed session. */
    fun forget(host: String) {
        connections.remove(host)?.socket?.close()
    }

    fun snapshotSockets(): List<Socket> = snapshot().map { it.socket }

    fun clear() = connections.clear()

    private fun snapshot(): List<RaopConnection> =
        synchronized(connections) { connections.values.toList() }

    private fun sendQuietly(
        connection: RaopConnection,
        what: String,
        request: RaopConnection.() -> Unit
    ) {
        try {
            connection.request()
        } catch (e: IOException) {
            Log.d(LOG_TAG, "RAOP $what to ${connection.host} failed: ${e.reason}")
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
