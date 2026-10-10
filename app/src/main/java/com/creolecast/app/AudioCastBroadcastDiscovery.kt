package com.creolecast.app

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * Finds AriaCast receivers: repeatedly broadcasts a discovery probe and hands the [Server]
 * described by each JSON reply to [onServer].
 */
internal class AudioCastBroadcastDiscovery(
    private val ioDispatcher: CoroutineDispatcher,
    private val onServer: (Server) -> Unit
) {
    /** Probes until the calling coroutine is cancelled. */
    suspend fun run() = withContext(ioDispatcher) {
        while (isActive) {
            try {
                DatagramSocket().use { socket -> probe(socket) }
            } catch (e: IOException) {
                Log.d(TAG, "AudioCast discovery broadcast failed", e)
            }
            delay(PROBE_CYCLE_DELAY_MS)
        }
    }

    /** Broadcasts one probe and collects the replies received within the window. */
    private fun CoroutineScope.probe(socket: DatagramSocket) {
        socket.broadcast = true
        socket.soTimeout = RESPONSE_WINDOW_MS
        val probe = PROBE_MESSAGE.toByteArray()
        socket.send(
            DatagramPacket(probe, probe.size, InetAddress.getByName(BROADCAST_ADDRESS), PORT)
        )
        val buffer = ByteArray(1024)
        val endTime = System.currentTimeMillis() + RESPONSE_WINDOW_MS
        while (System.currentTimeMillis() < endTime && isActive) {
            val reply = socket.receiveOrNull(buffer, TAG) ?: break
            serverFromReply(reply)?.let(onServer)
        }
    }

    private fun serverFromReply(reply: DatagramPacket): Server? = try {
        val json = JSONObject(String(reply.data, 0, reply.length))
        Server(
            name = json.optString("server_name"),
            host = reply.address.hostAddress.orEmpty(),
            port = json.optInt("port"),
            version = "1.0",
            codecs = listOf("pcm"),
            sampleRate = 48000,
            channels = 2,
            platform = "AriaCast"
        )
    } catch (e: JSONException) {
        // Malformed reply from one sender - ignore just this packet and keep listening
        // for the rest of the window instead of ending the whole scan early on a single
        // bad/spoofed reply.
        Log.d(TAG, "Ignoring malformed AudioCast discovery reply", e)
        null
    }

    private companion object {
        const val TAG = "DiscoveryManager"
        const val PROBE_MESSAGE = "DISCOVER_AUDIOCAST"
        const val BROADCAST_ADDRESS = "255.255.255.255"
        const val PORT = 12888
        const val RESPONSE_WINDOW_MS = 3000
        const val PROBE_CYCLE_DELAY_MS = 5000L
    }
}
