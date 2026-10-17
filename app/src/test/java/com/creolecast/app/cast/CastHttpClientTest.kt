package com.creolecast.app.cast

import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.webSocket
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Keep-alive of the receiver WebSockets opened through [createCastHttpClient].
 *
 * A receiver that vanishes without closing TCP (power loss, Wi-Fi drop) must be noticed
 * while the client only listens (the control socket never sends), so the client has to
 * ping on its own.
 */
class CastHttpClientTest {

    @Test
    fun `idle WebSocket sends a ping to the receiver`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = CONNECT_WAIT_MS
            val firstOpcode = CompletableFuture.supplyAsync { receiveFirstFrameOpcode(server) }
            createCastHttpClient().use { castClient ->
                // Same engine and plugins; logging is off because android.util.Log is not mocked.
                castClient.config { install(Logging) { level = LogLevel.NONE } }.use { client ->
                    runBlocking {
                        client.webSocket(
                            host = LOOPBACK,
                            port = server.localPort,
                            path = "/control"
                        ) {
                            // Returns once the receiver drops the connection.
                            incoming.receiveCatching()
                        }
                    }
                }
            }
            assertEquals("first frame sent by an idle client", PING_OPCODE, firstOpcode.get())
        }
    }

    /**
     * Accepts one WebSocket, then waits up to [PING_WAIT_MS] for the client's first frame
     * without sending anything; returns its opcode (null if none) and drops the connection.
     */
    private fun receiveFirstFrameOpcode(server: ServerSocket): Int? =
        server.accept().use { socket ->
            val input = socket.getInputStream()
            val key = readUpgradeRequest(input).getValue("sec-websocket-key")
            socket.getOutputStream().write(switchingProtocols(key).toByteArray())
            socket.soTimeout = PING_WAIT_MS
            try {
                input.read().takeIf { it >= 0 }?.and(OPCODE_MASK)
            } catch (_: SocketTimeoutException) {
                null
            }
        }

    /** Reads the HTTP upgrade request; returns its headers with lower-case names. */
    private fun readUpgradeRequest(input: InputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        readLine(input) // Request line.
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) return headers
            val (name, value) = line.split(":", limit = 2)
            headers[name.trim().lowercase()] = value.trim()
        }
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            check(byte >= 0) { "connection closed during the upgrade request" }
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            line.append(byte.toChar())
        }
    }

    private fun switchingProtocols(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray())
        val accept = Base64.getEncoder().encodeToString(digest)
        return "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OPCODE_MASK = 0x0F
        const val PING_OPCODE = 0x9
        const val CONNECT_WAIT_MS = 10_000

        /** Twice the client's 5 s ping interval. */
        const val PING_WAIT_MS = 10_000
    }
}
