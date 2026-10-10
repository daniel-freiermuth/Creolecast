package com.creolecast.app.cast

import android.util.Log
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject

private const val CONNECT_TIMEOUT_MS = 5000

/** Skipped notifications are logged truncated to this many characters. */
private const val LOGGED_MESSAGE_CHARS = 100

/** A snapserver reply that makes the session impossible, e.g. no stream could be created. */
internal class SnapcastException(message: String) : IOException(message)

/**
 * Line-delimited JSON-RPC 2.0 over snapserver's TCP control port (discovered as
 * `_snapcast-ctrl._tcp`). Calls block until the matching response arrives.
 */
internal class SnapcastRpc : Closeable {
    private val socket = Socket()
    private lateinit var output: OutputStream
    private lateinit var input: BufferedReader
    private var lastRequestId = 0

    val isOpen: Boolean get() = !socket.isClosed

    fun connect(host: String, port: Int) {
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket.tcpNoDelay = true
        output = socket.getOutputStream()
        input = socket.getInputStream().bufferedReader()
    }

    fun call(method: String, params: JSONObject? = null): JSONObject {
        val requestId = ++lastRequestId
        val request = JSONObject().apply {
            put("id", requestId)
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }.toString() + "\n"
        output.write(request.toByteArray())
        output.flush()
        return awaitResponse(requestId)
    }

    /** Snapserver sends asynchronous notifications on the same socket; skip until ours. */
    private fun awaitResponse(requestId: Int): JSONObject {
        var response: JSONObject? = null
        while (response == null) {
            val line = input.readLine() ?: throw SnapcastException("No response from snapserver")
            val json = JSONObject(line)
            if (json.isResponseTo(requestId)) {
                response = json
            } else {
                Log.d(LOG_TAG, "Snapcast: skipped message: ${line.take(LOGGED_MESSAGE_CHARS)}")
            }
        }
        return response
    }

    private fun JSONObject.isResponseTo(requestId: Int): Boolean {
        val responseId = opt("id")
        val matchesId = responseId is Int && responseId == requestId
        // Error responses sometimes carry "id": null; they answer the request just sent.
        val isAnonymousError = has("error") && (responseId == null || responseId == JSONObject.NULL)
        return matchesId || isAnonymousError
    }

    override fun close() = closeQuietly(socket, "Snapcast control socket")
}
