package com.creolecast.app.cast

import com.creolecast.app.CastDestination
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

    private val registry = RaopRegistry()

    /**
     * Runs [block] with a loopback control connection: the sender's socket (what a
     * [RaopSession] registers) and the receiver's end of it.
     */
    private fun withConnection(block: (sender: Socket, receiverEnd: InputStream) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { receiver ->
            Socket(receiver.inetAddress, receiver.localPort).use { sender ->
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

    @Test
    fun `teardown sends TEARDOWN with the session's latest CSeq and Session id`() {
        withConnection { sender, receiverEnd ->
            registry.register(HOST_A, sender)
            registry.setCSeq(HOST_A, 1)
            registry.setSession(HOST_A, "ABCDEF01")
            // The session advances the CSeq as it streams; TEARDOWN must use the current one.
            registry.setCSeq(HOST_A, 5)

            registry.teardown(CastDestination("Living Room", HOST_A, 5000, "AirPlay"))

            val head = readRequestHead(receiverEnd)
            assertEquals("TEARDOWN rtsp://$HOST_A:5000/AriaCast RTSP/1.0", head.first())
            assertTrue(head.toString(), "CSeq: 5" in head)
            assertTrue(head.toString(), "Session: ABCDEF01" in head)
        }
    }

    @Test
    fun `teardown reaches only the receiver it is addressed to`() {
        withConnection { senderA, receiverEndA ->
            withConnection { senderB, receiverEndB ->
                registry.register(HOST_A, senderA)
                registry.setSession(HOST_A, "SESSION-A")
                registry.setCSeq(HOST_A, 3)
                registry.register(HOST_B, senderB)
                registry.setSession(HOST_B, "SESSION-B")
                registry.setCSeq(HOST_B, 7)

                registry.teardown(CastDestination("Kitchen", HOST_B, 5000, "AirPlay"))

                val head = readRequestHead(receiverEndB)
                assertEquals("TEARDOWN rtsp://$HOST_B:5000/AriaCast RTSP/1.0", head.first())
                assertTrue(head.toString(), "CSeq: 7" in head)
                assertTrue(head.toString(), "Session: SESSION-B" in head)
                assertTrue(isOpenAndSilent(receiverEndA))
            }
        }
    }

    @Test
    fun `clear forgets the connections but leaves the snapshotted sockets open`() {
        withConnection { sender, receiverEnd ->
            registry.register(HOST_A, sender)

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
            registry.register(HOST_A, sender)

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
