package com.creolecast.app.airplay2

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlay2EventChannelTest {

    private val receiver = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val commands = LinkedBlockingQueue<String>()
    private val listener = object : AirPlay2Client.EventListener {
        override fun onRemoteCommand(command: String) {
            commands.add(command)
        }
    }

    @Volatile private var running = false
    private val channel = AirPlay2EventChannel(
        receiver.inetAddress.hostAddress!!,
        isRunning = { running },
        listener = { listener }
    )

    @After
    fun tearDown() {
        running = false
        channel.close()
        receiver.close()
    }

    @Test
    fun `commands arrive when the session starts running after the event port connects`() {
        // AirPlay2Client connects the event port during setup and only flags the
        // session running after the remaining RTSP round-trips; mirror that order.
        // connect() logs through android.util.Log, which the unit-test android.jar
        // does not implement; the reader thread is already started by then.
        runCatching { channel.connect(receiver.localPort) }
        receiver.accept().use { events ->
            Thread.sleep(SETUP_ROUND_TRIPS_MS)
            running = true

            val body = BinaryPlist.encode(mapOf("type" to "command", "name" to "playpause"))
            val head = "POST /command RTSP/1.0\r\n" +
                "Content-Type: application/x-apple-binary-plist\r\n" +
                "Content-Length: ${body.size}\r\n\r\n"
            events.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(body)
                flush()
            }

            assertEquals("playpause", commands.poll(2, TimeUnit.SECONDS))
        }
    }

    private companion object {
        const val SETUP_ROUND_TRIPS_MS = 200L
    }
}
