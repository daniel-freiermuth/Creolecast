package com.creolecast.app.cast

import com.creolecast.app.CastDestination
import com.creolecast.app.CastPlatform
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RAOP half of ending a cast: [RaopRegistry.teardown] sends the TEARDOWN on the live
 * control connection, and [RaopRegistry.clear] leaves those connections open so that
 * [CastController] can close its snapshot only after the TEARDOWN window.
 */
class RaopRegistryTeardownTest {
    private val loopback = InetAddress.getLoopbackAddress()

    private val registry = RaopRegistry()

    /**
     * Runs [block] with a loopback control connection: the sender's socket (what a
     * [RaopSession] registers) and the receiver's end of it.
     */
    private fun withConnection(block: (sender: Socket, receiverEnd: InputStream) -> Unit) {
        ServerSocket(0, 1, loopback).use { receiver ->
            Socket(loopback, receiver.localPort).use { sender ->
                receiver.accept().use { accepted ->
                    accepted.soTimeout = READ_TIMEOUT_MS
                    block(sender, accepted.getInputStream())
                }
            }
        }
    }

    /** One RTSP request head (request line and headers), without the blank line. */
    private fun readRequestHead(input: InputStream): List<String> {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            check(b != -1) { "Stream ended inside a request: $lines $line" }
            when (b.toChar()) {
                '\r' -> Unit

                '\n' -> {
                    if (line.isEmpty()) return lines
                    lines += line.toString()
                    line.clear()
                }

                else -> line.append(b.toChar())
            }
        }
    }

    /** True when the connection is still open and the sender wrote nothing on it. */
    private fun isOpenAndSilent(input: InputStream): Boolean = try {
        input.read()
        false
    } catch (_: SocketTimeoutException) {
        true
    }

    private fun airPlay(name: String, host: String) =
        CastDestination(name, host, 5000, CastPlatform.AIRPLAY)

    @Test
    fun capturedTeardownIsSentOnTheStoppedConnectionAfterClear() {
        ServerSocket(0, 1, loopback).use { receiver ->
            Socket(loopback, receiver.localPort).use { control ->
                receiver.accept().use { received ->
                    received.soTimeout = 5_000
                    val dest =
                        CastDestination("Speaker", "speaker.local", 5000, CastPlatform.AIRPLAY)
                    val registry = RaopRegistry()
                    val stopped = RaopConnection(dest.host, control)
                    stopped.session = "OLD-SESSION"
                    registry.register(stopped)

                    val teardown = registry.captureTeardown(dest)!!
                    // Stop clears the registry before the teardown job runs, and a fast
                    // reconnect may already have registered a new connection to the host.
                    registry.clear()
                    Socket().use { reconnect ->
                        val fresh = RaopConnection(dest.host, reconnect)
                        fresh.session = "NEW-SESSION"
                        registry.register(fresh)
                        teardown()
                    }

                    val request = received.getInputStream().bufferedReader()
                        .lineSequence().takeWhile { it.isNotEmpty() }.toList()
                    assertEquals(
                        listOf(
                            "TEARDOWN rtsp://speaker.local:${control.port}/AriaCast RTSP/1.0",
                            "CSeq: 0",
                            "Session: OLD-SESSION",
                            "User-Agent: AirPlay/366.0"
                        ),
                        request
                    )
                }
            }
        }
    }

    @Test
    fun `teardown continues the CSeq of the requests the session already sent`() {
        withConnection { sender, receiverEnd ->
            val connection = RaopConnection(HOST_A, sender)
            connection.session = "ABCDEF01"
            registry.register(connection)
            // The session sends requests as it streams; TEARDOWN must not reuse their CSeqs.
            connection.send("OPTIONS")
            connection.send("OPTIONS")
            readRequestHead(receiverEnd)
            readRequestHead(receiverEnd)

            registry.teardown(airPlay("Living Room", HOST_A))

            val head = readRequestHead(receiverEnd)
            assertEquals("TEARDOWN rtsp://$HOST_A:${sender.port}/AriaCast RTSP/1.0", head.first())
            assertTrue(head.toString(), "CSeq: 2" in head)
            assertTrue(head.toString(), "Session: ABCDEF01" in head)
        }
    }

    @Test
    fun `teardown reaches only the receiver it is addressed to`() {
        withConnection { senderA, receiverEndA ->
            withConnection { senderB, receiverEndB ->
                registry.register(RaopConnection(HOST_A, senderA).apply { session = "SESSION-A" })
                registry.register(RaopConnection(HOST_B, senderB).apply { session = "SESSION-B" })

                registry.teardown(airPlay("Kitchen", HOST_B))

                val head = readRequestHead(receiverEndB)
                assertEquals(
                    "TEARDOWN rtsp://$HOST_B:${senderB.port}/AriaCast RTSP/1.0",
                    head.first()
                )
                assertTrue(head.toString(), "Session: SESSION-B" in head)
                assertTrue(isOpenAndSilent(receiverEndA))
            }
        }
    }

    @Test
    fun `clear forgets the connections but leaves the snapshotted sockets open`() {
        withConnection { sender, receiverEnd ->
            registry.register(RaopConnection(HOST_A, sender))

            val snapshot = registry.snapshotSockets()
            registry.clear()

            assertEquals(listOf(sender), snapshot)
            assertTrue(registry.snapshotSockets().isEmpty())
            // Closing is deferred until the TEARDOWN window ends; clear() must not do it.
            assertFalse(sender.isClosed)
            assertTrue(isOpenAndSilent(receiverEnd))
        }
    }

    @Test
    fun `forget closes the control connection of a failed session`() {
        withConnection { sender, receiverEnd ->
            registry.register(RaopConnection(HOST_A, sender))

            registry.forget(HOST_A)

            assertTrue(sender.isClosed)
            assertEquals(-1, receiverEnd.read())
            assertTrue(registry.snapshotSockets().isEmpty())
        }
    }

    private companion object {
        const val HOST_A = "192.0.2.10"
        const val HOST_B = "192.0.2.11"
        const val READ_TIMEOUT_MS = 300
    }
}
