package com.creolecast.app.airplay2

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receiver-to-sender event connection against a fake receiver on loopback:
 * the receiver accepts the channel's connection and writes RTSP-framed POSTs to it.
 */
class AirPlay2EventChannelTest {

    private companion object {
        const val PLIST = "application/x-apple-binary-plist"
        const val WAIT_SECONDS = 5L
        const val LOOP_THREAD = "ap2-event"
    }

    /** What the session reports; false until the client finishes setting up. */
    @Volatile private var running = false

    private val volumes = LinkedBlockingQueue<Double>()
    private val commands = LinkedBlockingQueue<String>()
    private val listener = object : AirPlay2Client.EventListener {
        override fun onVolumeChange(db: Double) {
            volumes.add(db)
        }

        override fun onRemoteCommand(command: String) {
            commands.add(command)
        }
    }

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val channel = AirPlay2EventChannel(
        server.inetAddress.hostAddress.orEmpty(),
        isRunning = { running },
        listener = { listener }
    )
    private var receiver: Socket? = null

    @After
    fun tearDown() {
        channel.close()
        receiver?.close()
        server.close()
    }

    @Test
    fun `volume sent before the session runs is delivered once connected during setup`() {
        // AirPlay2Client connects the event port during setup and only marks itself
        // running after further RTSP round-trips; the loop must outlive that window.
        val loop = connect()
        assertTrue("read loop ended before the session ran", loop?.isAlive == true)
        running = true
        send(event(plist("type" to "volume", "value" to -15.0)))
        assertEquals(-15.0, nextVolume(), 0.0)
    }

    @Test
    fun `integer volume value is delivered as decibels`() {
        connect()
        send(event(plist("type" to "volume", "value" to -30L)))
        assertEquals(-30.0, nextVolume(), 0.0)
    }

    @Test
    fun `remote command is delivered by name`() {
        connect()
        send(event(plist("type" to "command", "name" to "nextitem")))
        assertEquals("nextitem", nextCommand())
    }

    @Test
    fun `malformed events are skipped and later ones on the stream still arrive`() {
        connect()
        send(
            event(plist("type" to "command")),
            event(plist("type" to "volume", "value" to "loud")),
            event(plist("type" to "command", "name" to "pause"), contentType = "text/parameters"),
            event("not a plist".toByteArray()),
            event(plist("type" to "unknown", "name" to "play")),
            event(plist("type" to "command", "name" to "nextitem"))
        )
        // One reader thread dispatches in order, so the last event arriving means every
        // earlier one was already handled.
        assertEquals("nextitem", nextCommand())
        assertNull(commands.poll())
        assertNull(volumes.poll())
    }

    @Test
    fun `back-to-back messages are framed by their own Content-Length`() {
        connect()
        send(
            "POST /feedback RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray(),
            event(plist("type" to "volume", "value" to -20.0), lengthHeader = "content-length"),
            event(plist("type" to "command", "name" to "playpause"))
        )
        assertEquals(-20.0, nextVolume(), 0.0)
        assertEquals("playpause", nextCommand())
    }

    @Test
    fun `body cut short by the receiver closing is not delivered and ends the loop`() {
        val loop = checkNotNull(connect()) { "read loop not running" }
        val full = event(plist("type" to "command", "name" to "nextitem"))
        send(full.copyOf(full.size - 1))
        receiver?.shutdownOutput()
        loop.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        assertFalse(loop.isAlive)
        assertNull(commands.poll())
    }

    @Test
    fun `close ends the read loop`() {
        val loop = checkNotNull(connect()) { "read loop not running" }
        running = true
        send(event(plist("type" to "command", "name" to "play")))
        assertEquals("play", nextCommand())
        running = false
        channel.close()
        loop.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        assertFalse(loop.isAlive)
    }

    /**
     * Connects the channel, unpaired so the stream is plaintext, to the fake receiver;
     * returns its read-loop thread if still running.
     */
    private fun connect(): Thread? {
        val before = loopThreads()
        channel.connect(server.localPort, sharedSecret = null)
        receiver = server.accept()
        return (loopThreads() - before).singleOrNull()
    }

    private fun loopThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys.filterTo(mutableSetOf()) { it.name == LOOP_THREAD }

    private fun send(vararg messages: ByteArray) {
        val out = checkNotNull(receiver).getOutputStream()
        messages.forEach(out::write)
        out.flush()
    }

    /** The next delivered volume, NaN if none arrives in time. */
    private fun nextVolume(): Double = volumes.poll(WAIT_SECONDS, TimeUnit.SECONDS) ?: Double.NaN

    private fun nextCommand(): String? = commands.poll(WAIT_SECONDS, TimeUnit.SECONDS)

    private fun event(
        body: ByteArray,
        contentType: String = PLIST,
        lengthHeader: String = "Content-Length"
    ): ByteArray {
        val head = "POST /command RTSP/1.0\r\n" +
            "Content-Type: $contentType\r\n" +
            "$lengthHeader: ${body.size}\r\n\r\n"
        return head.toByteArray(Charsets.US_ASCII) + body
    }

    /**
     * A `bplist00` dictionary of short ASCII strings, 64-bit reals and 64-bit integers,
     * built by hand: [BinaryPlist.encode] writes every number as an unsigned integer,
     * but receivers send the volume as a real.
     */
    private fun plist(vararg entries: Pair<String, Any>): ByteArray {
        val leaves = entries.map { it.first } + entries.map { it.second }
        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        val offsets = mutableListOf(out.size())
        out.write(0xD0 or entries.size)
        leaves.indices.forEach { out.write(it + 1) }
        for (leaf in leaves) {
            offsets += out.size()
            when (leaf) {
                is String -> {
                    out.write(0x50 or leaf.length)
                    out.write(leaf.toByteArray(Charsets.US_ASCII))
                }

                is Double -> {
                    out.write(0x23)
                    out.write(ByteBuffer.allocate(8).putDouble(leaf).array())
                }

                is Long -> {
                    out.write(0x13)
                    out.write(ByteBuffer.allocate(8).putLong(leaf).array())
                }

                else -> error("unsupported plist value $leaf")
            }
        }
        val offsetTable = out.size()
        offsets.forEach(out::write)
        val trailer = ByteArray(6) + // unused
            byteArrayOf(1, 1) + // offset size, object ref size
            ByteBuffer.allocate(24)
                .putLong(offsets.size.toLong())
                .putLong(0) // root object
                .putLong(offsetTable.toLong())
                .array()
        out.write(trailer)
        return out.toByteArray()
    }
}
