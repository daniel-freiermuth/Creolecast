package com.creolecast.app.cast

import android.util.Log
import com.creolecast.app.MediaCommand
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

private const val CTRL_PREFIX = "/ctrl-int/1/"
private const val CLIENT_TIMEOUT_MS = 10_000
private const val MAX_HEADER_LINES = 64
private const val PERCENT_MAX = 100.0

/** AirPlay volume range the DACP `dmcp.volume` percentage maps onto. */
private const val DB_RANGE = 30.0

/** What a receiver's remote control asked for. */
internal interface DacpHandler {
    fun onMediaCommand(command: MediaCommand)

    /** One volume step up (+1) or down (-1). */
    fun onVolumeStep(direction: Int)

    /** An absolute AirPlay volume (-30.0 to 0.0 dB, -144.0 mute). */
    fun onVolumeDb(dB: Double)
}

/**
 * DACP remote-control endpoint. Every AirPlay request we send carries `DACP-ID` and
 * `Active-Remote`; receivers with a remote (AirPort Express IR, AV receivers, shairport-sync's
 * D-Bus/MPRIS/MQTT controls) find this server as `iTunes_Ctrl_<DACP-ID>` (`_dacp._tcp`, see
 * [DacpAdvertiser]) and send `GET /ctrl-int/1/<command>` with our `Active-Remote`.
 *
 * Requests without the matching `Active-Remote` are refused: the server listens on the LAN,
 * and the token is what ties a request to a receiver we stream to.
 */
internal class DacpServer(private val activeRemote: String, private val handler: DacpHandler) {
    private var server: ServerSocket? = null

    val port: Int get() = server?.localPort ?: 0

    /** Listens on [port] (0: any free one); returns the port. */
    @Suppress("MissingUseCall") // Accepts until stop(); `use` would close it on return.
    fun start(port: Int = 0): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        server = socket
        thread(name = "dacp-server", isDaemon = true) { acceptLoop(socket) }
        return socket.localPort
    }

    fun stop() {
        closeQuietly(server, "DACP server")
        server = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        try {
            while (true) {
                val client = socket.accept()
                thread(name = "dacp-client", isDaemon = true) { serve(client) }
            }
        } catch (_: IOException) {
            // stop() closed the server socket.
        }
    }

    private fun serve(client: Socket) {
        client.use { s ->
            try {
                s.soTimeout = CLIENT_TIMEOUT_MS
                val input = s.getInputStream().buffered()
                var request = readRequest(input)
                while (request != null) {
                    val status = respond(request)
                    s.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        flush()
                    }
                    request = readRequest(input)
                }
            } catch (e: IOException) {
                Log.d(LOG_TAG, "DACP client ended: ${e.reason}")
            }
        }
    }

    private class Request(val path: String, val query: String, val activeRemote: String?)

    /** Request line and headers of one HTTP request (DACP requests have no body). */
    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input)?.takeIf { it.isNotEmpty() } ?: return null
        var activeRemote: String? = null
        var lines = 0
        var line = readLine(input)
        while (!line.isNullOrEmpty() && lines++ < MAX_HEADER_LINES) {
            if (line.startsWith("Active-Remote:", ignoreCase = true)) {
                activeRemote = line.substringAfter(':').trim()
            }
            line = readLine(input)
        }
        val target = requestLine.split(' ').getOrElse(1) { "" }
        return Request(target.substringBefore('?'), target.substringAfter('?', ""), activeRemote)
    }

    private fun respond(request: Request): String {
        if (request.activeRemote != activeRemote) {
            Log.w(LOG_TAG, "DACP: refusing ${request.path} without our Active-Remote")
            return "403 Forbidden"
        }
        val known = request.path.startsWith(CTRL_PREFIX) && dispatch(
            request.path.removePrefix(CTRL_PREFIX),
            request.query
        )
        Log.d(LOG_TAG, "DACP ${request.path}${if (known) "" else " (ignored)"}")
        return if (known) "204 No Content" else "404 Not Found"
    }

    /** Runs [command]; false if it is not one we know. */
    private fun dispatch(command: String, query: String): Boolean {
        when (command) {
            "volumeup" -> handler.onVolumeStep(1)
            "volumedown" -> handler.onVolumeStep(-1)
            "setproperty" -> setProperty(query)
            "beginff" -> handler.onMediaCommand(MediaCommand.NEXT)
            "beginrew" -> handler.onMediaCommand(MediaCommand.PREVIOUS)
            "discrete-pause" -> handler.onMediaCommand(MediaCommand.PAUSE)
            else -> handler.onMediaCommand(mediaCommandFor(command) ?: return false)
        }
        return true
    }

    /** `dmcp.device-volume` (AirPlay dB) or `dmcp.volume` (0-100). */
    private fun setProperty(query: String) {
        query.split('&').forEach { part ->
            val value = URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8)
                .toDoubleOrNull() ?: return@forEach
            when (part.substringBefore('=')) {
                "dmcp.device-volume" -> handler.onVolumeDb(value)

                "dmcp.volume" -> handler.onVolumeDb(
                    value.coerceIn(0.0, PERCENT_MAX) / PERCENT_MAX * DB_RANGE - DB_RANGE
                )
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        var b = input.read()
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) line.append(b.toChar())
            b = input.read()
        }
        return if (b == -1 && line.isEmpty()) null else line.toString()
    }
}
