package com.creolecast.app.airplay2

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import org.bouncycastle.crypto.InvalidCipherTextException

/**
 * The receiver-to-sender event connection: volume changes and remote commands, as binary
 * plists in RTSP-framed POSTs that we answer with 200. HAP-encrypted with the event keys
 * when the session was paired.
 */
internal class AirPlay2EventChannel(
    private val host: String,
    private val isRunning: () -> Boolean,
    private val listener: () -> AirPlay2Client.EventListener?
) : Closeable {

    private companion object {
        const val TAG = "AirPlay2Client"
        const val CONNECT_TIMEOUT_MS = 5000
    }

    private class EventMessage(
        val startLine: String,
        val headers: Map<String, String>,
        val body: ByteArray?
    )

    private var socket: Socket? = null
    private var cipher: HapCipher? = null

    /** Connects to the receiver's event [port]; [sharedSecret] keys it if the session was paired. */
    @Suppress("MissingUseCall") // The reader thread needs it until close(); `use` would end it.
    fun connect(port: Int, sharedSecret: ByteArray?) {
        if (port <= 0) return
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket = s
            cipher = sharedSecret?.let(HapCipher::forEvents)
            startReadLoop(s)
            Log.d(TAG, "Event port $port connected")
        } catch (e: IOException) {
            Log.w(TAG, "Event port connect failed", e)
        } catch (e: IllegalArgumentException) {
            // The receiver named a port outside 0..65535.
            Log.w(TAG, "Event port connect failed", e)
        }
    }

    override fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
            // Best effort: the session is ending either way.
        }
    }

    @Suppress("MissingUseCall") // Wraps the socket's stream, which close() ends.
    private fun startReadLoop(s: Socket) {
        thread(name = "ap2-event", isDaemon = true) {
            try {
                val stream = HapInputStream(s.getInputStream()) { cipher }
                val output = s.getOutputStream()
                // Runs until the receiver or close() ends the connection; it opens during
                // setup, before the client counts as running.
                var msg = readEventMessage(stream)
                while (msg != null) {
                    dispatch(msg)
                    reply(output, msg)
                    msg = readEventMessage(stream)
                }
            } catch (e: IOException) {
                if (isRunning()) Log.w(TAG, "Event loop ended", e)
            } catch (e: InvalidCipherTextException) {
                // ChaCha20-Poly1305 rejected a frame: wrong keys or a corrupted stream.
                if (isRunning()) Log.w(TAG, "Event channel undecryptable", e)
            }
        }
    }

    private fun readEventMessage(input: InputStream): EventMessage? {
        val head = RtspWire.readHead(input)
        if (!head.complete || head.lines.isEmpty()) return null
        val headers = RtspWire.parseHeaders(head.lines)
        val body = if (headers.contentLength > 0) {
            ByteArray(headers.contentLength).also { RtspWire.readFully(input, it) }
        } else {
            null
        }
        return EventMessage(head.lines[0], headers.values, body)
    }

    /** Requests get a bare 200; Apple receivers reportedly drop a session left unanswered. */
    private fun reply(output: OutputStream, msg: EventMessage) {
        if (msg.startLine.startsWith("RTSP/")) return // a response, not a request
        val cseq = msg.headers.entries.firstOrNull { it.key.equals("CSeq", ignoreCase = true) }
        val text = "RTSP/1.0 200 OK\r\n" + cseq?.let { "CSeq: ${it.value}\r\n" }.orEmpty() + "\r\n"
        val bytes = text.toByteArray(Charsets.US_ASCII)
        cipher?.write(output, bytes) ?: output.write(bytes)
        output.flush()
    }

    private fun dispatch(msg: EventMessage) {
        val body = msg.body
        val isPlist = msg.headers["Content-Type"]?.contains("apple-binary-plist") == true
        if (body != null && isPlist) {
            try {
                handle(BinaryPlist.decode(body))
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Event dispatch failed", e)
            }
        }
    }

    private fun handle(event: Map<String, Any?>) {
        Log.d(TAG, "Event: ${event["type"]}")
        when (event["type"]) {
            "volume" -> {
                val db = when (val v = event["value"]) {
                    is Double -> v
                    is Long -> v.toDouble()
                    else -> null
                }
                db?.let { listener()?.onVolumeChange(it) }
            }

            "command", "sendMediaRemoteCommand" -> {
                val params = event["params"] as? Map<*, *>
                val name = event["name"] as? String ?: params?.get("command") as? String
                name?.let { listener()?.onRemoteCommand(it) }
            }
        }
    }
}
