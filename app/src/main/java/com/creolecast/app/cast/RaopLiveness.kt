package com.creolecast.app.cast

import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Idle time after which an OPTIONS request checks that the receiver is still there. */
private const val KEEP_ALIVE_IDLE_MS = 15_000L

/** How long the receiver has to answer the keep-alive before the connection counts as lost. */
private const val KEEP_ALIVE_REPLY_MS = 10_000L
private const val READ_POLL_MS = 1000
private const val READ_BUFFER_BYTES = 4096

/**
 * Watches a RAOP control connection while audio streams over UDP, where a dead receiver
 * would otherwise go unnoticed: reads (and discards) the receiver's responses, and sends an
 * OPTIONS keep-alive whenever the connection was idle for [KEEP_ALIVE_IDLE_MS].
 */
internal class RaopLiveness(private val socket: Socket, private val sendKeepAlive: () -> Unit) {
    /** Returns why the connection was lost; runs until then or until cancelled (throws). */
    suspend fun watch(): String {
        val input = socket.getInputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var lastReceived = System.currentTimeMillis()
        var keepAliveSentAt: Long? = null
        return try {
            socket.soTimeout = READ_POLL_MS
            var lost: String? = null
            while (lost == null) {
                currentCoroutineContext().ensureActive()
                val now = System.currentTimeMillis()
                val pending = keepAliveSentAt
                if (pending == null && now - lastReceived >= KEEP_ALIVE_IDLE_MS) {
                    sendKeepAlive()
                    keepAliveSentAt = now
                } else if (pending != null && now - pending > KEEP_ALIVE_REPLY_MS) {
                    lost = "no reply to keep-alive"
                }
                val read = try {
                    input.read(buffer)
                } catch (_: SocketTimeoutException) {
                    0
                }
                if (read < 0) {
                    lost = "closed by the receiver"
                } else if (read > 0) {
                    lastReceived = System.currentTimeMillis()
                    keepAliveSentAt = null
                }
            }
            lost
        } catch (e: IOException) {
            e.reason
        }
    }
}
