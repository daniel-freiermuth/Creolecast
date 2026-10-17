package com.creolecast.app.cast

import com.creolecast.app.CastDestination
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Test

class RaopRegistryTeardownTest {
    private val loopback = InetAddress.getLoopbackAddress()

    @Test
    fun capturedTeardownIsSentOnTheStoppedConnectionAfterClear() {
        ServerSocket(0, 1, loopback).use { receiver ->
            Socket(loopback, receiver.localPort).use { control ->
                receiver.accept().use { received ->
                    received.soTimeout = 5_000
                    val dest = CastDestination("Speaker", "speaker.local", 5000, "AirPlay")
                    val registry = RaopRegistry()
                    registry.register(dest.host, control)
                    registry.setSession(dest.host, "OLD-SESSION")
                    registry.setCSeq(dest.host, 7)

                    val teardown = registry.captureTeardown(dest)!!
                    // Stop clears the registry before the teardown job runs, and a fast
                    // reconnect may already have registered a new connection to the host.
                    registry.clear()
                    Socket().use { reconnect ->
                        registry.register(dest.host, reconnect)
                        registry.setSession(dest.host, "NEW-SESSION")
                        registry.setCSeq(dest.host, 3)
                        teardown()
                    }

                    val request = received.getInputStream().bufferedReader()
                        .lineSequence().takeWhile { it.isNotEmpty() }.toList()
                    assertEquals(
                        listOf(
                            "TEARDOWN rtsp://speaker.local:5000/AriaCast RTSP/1.0",
                            "CSeq: 7",
                            "Session: OLD-SESSION",
                            "User-Agent: AirPlay/366.0"
                        ),
                        request
                    )
                }
            }
        }
    }
}
