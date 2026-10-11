package com.creolecast.app.cast

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RAOP (AirPlay 1) RTSP handshake's wire framing ([RtspWire]) and its parsing of the
 * receiver's SETUP reply, which decides where audio, sync and timing packets go.
 */
class RaopRtspHandshakeTest {

    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray())

    private fun reply(text: String) = RtspWire.readReply(stream(text))

    // --- Request framing ---

    @Test
    fun `send writes request line, CSeq, headers in order, blank line, then body`() {
        val output = ByteArrayOutputStream()
        val body = "volume: 0.000000\r\n"
        RtspWire.send(
            output,
            RtspRequest(
                "SET_PARAMETER",
                "192.168.1.20",
                5000,
                3,
                mapOf(
                    "Session" to "1A2B",
                    "Content-Type" to "text/parameters",
                    "Content-Length" to body.length.toString()
                ),
                body.toByteArray()
            )
        )
        assertEquals(
            "SET_PARAMETER rtsp://192.168.1.20:5000/AriaCast RTSP/1.0\r\n" +
                "CSeq: 3\r\n" +
                "Session: 1A2B\r\n" +
                "Content-Type: text/parameters\r\n" +
                "Content-Length: 18\r\n" +
                "\r\n" +
                "volume: 0.000000\r\n",
            output.toString(Charsets.UTF_8)
        )
    }

    @Test
    fun `send without body ends at the blank line`() {
        val output = ByteArrayOutputStream()
        RtspWire.send(output, RtspRequest("RECORD", "10.0.0.7", 7000, 0, emptyMap()))
        assertEquals(
            "RECORD rtsp://10.0.0.7:7000/AriaCast RTSP/1.0\r\nCSeq: 0\r\n\r\n",
            output.toString(Charsets.UTF_8)
        )
    }

    // --- Reply framing ---

    @Test
    fun `readReply consumes the body so back-to-back replies stay separate`() {
        val input = stream(
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 5\r\n\r\nhello" +
                "RTSP/1.0 200 OK\r\nCSeq: 2\r\nAudio-Latency: 11025\r\n\r\n"
        )
        val first = RtspWire.readReply(input)
        assertEquals(200, first.code)
        assertEquals("1", first["CSeq"])
        assertArrayEquals("hello".toByteArray(), first.body)

        val second = RtspWire.readReply(input)
        assertEquals("2", second["CSeq"])
        assertEquals("11025", second["Audio-Latency"])
        assertEquals(0, second.body.size)
    }

    @Test
    fun `readReply accepts bare LF endings and a stray blank line between replies`() {
        val input = stream("RTSP/1.0 200 OK\nCSeq: 4\n\n\r\nRTSP/1.0 200 OK\nCSeq: 5\n\n")
        assertEquals("4", RtspWire.readReply(input)["CSeq"])
        assertEquals("5", RtspWire.readReply(input)["CSeq"])
    }

    @Test
    fun `readReply looks headers up case-insensitively and trims values`() {
        val r = reply("RTSP/1.0 200 OK\r\nsESSION:   1A2B  \r\n\r\n")
        assertEquals("1A2B", r["Session"])
        assertEquals("1A2B", r["session"])
    }

    @Test
    fun `readReply reports non-2xx status codes as failures`() {
        val unauthorized = reply("RTSP/1.0 401 Unauthorized\r\nCSeq: 1\r\n\r\n")
        assertEquals(401, unauthorized.code)
        assertFalse(unauthorized.isSuccess)

        val unsupported = reply("RTSP/1.0 453 Not Enough Bandwidth\r\n\r\n")
        assertEquals(453, unsupported.code)
        assertFalse(unsupported.isSuccess)

        assertTrue(reply("RTSP/1.0 204 No Content\r\n\r\n").isSuccess)
    }

    @Test
    fun `readReply gives code 0 for an unreadable status line`() {
        val r = reply("garbage\r\n\r\n")
        assertEquals(0, r.code)
        assertFalse(r.isSuccess)
    }

    @Test
    fun `readReply throws when the receiver closes before or inside a reply`() {
        assertThrows(IOException::class.java) { reply("") }
        assertThrows(IOException::class.java) {
            reply("RTSP/1.0 200 OK\r\nContent-Length: 10\r\n\r\nshort")
        }
    }

    @Test
    fun `readReply rejects a negative or oversized Content-Length`() {
        assertThrows(IOException::class.java) {
            reply("RTSP/1.0 200 OK\r\nContent-Length: -1\r\n\r\n")
        }
        assertThrows(IOException::class.java) {
            reply("RTSP/1.0 200 OK\r\nContent-Length: 2097152\r\n\r\n")
        }
    }

    @Test
    fun `readReply rejects a reply head that never ends`() {
        val head = StringBuilder("RTSP/1.0 200 OK\r\n")
        repeat(100) { head.append("X-Filler-$it: y\r\n") }
        head.append("\r\n")
        assertThrows(IOException::class.java) { reply(head.toString()) }
    }

    // --- SETUP reply: Transport ports ---

    @Test
    fun `parseServerPorts reads server, control and timing ports from Transport`() {
        val setup = reply(
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\n" +
                "Transport: RTP/AVP/UDP;unicast;mode=record;" +
                "server_port=6000;control_port=6001;timing_port=6002\r\n" +
                "Session: 1A2B\r\n\r\n"
        )
        assertEquals(
            ServerPorts(audio = 6000, control = 6001, timing = 6002),
            parseServerPorts(setup["Transport"].orEmpty())
        )
    }

    @Test
    fun `parseServerPorts tolerates spaces, extra parameters, any order and header case`() {
        val setup = reply(
            "RTSP/1.0 200 OK\r\n" +
                "transport: RTP/AVP/UDP; unicast; interleaved=0-1; mode=record; " +
                "timing_port=53562; control_port=53561; server_port=53560\r\n\r\n"
        )
        assertEquals(
            ServerPorts(audio = 53560, control = 53561, timing = 53562),
            parseServerPorts(setup["Transport"].orEmpty())
        )
    }

    @Test
    fun `parseServerPorts does not read the sender's own ports from other headers`() {
        val setup = reply(
            "RTSP/1.0 200 OK\r\n" +
                "X-Echo: control_port=1111;timing_port=2222\r\n" +
                "Transport: RTP/AVP/UDP;unicast;server_port=6000;control_port=6001;" +
                "timing_port=6002\r\n\r\n"
        )
        assertEquals(
            ServerPorts(audio = 6000, control = 6001, timing = 6002),
            parseServerPorts(setup["Transport"].orEmpty())
        )
    }

    // --- SETUP reply: Session ---

    @Test
    fun `parseSessionId drops the timeout parameter`() {
        assertEquals(
            "DEADBEEF",
            parseSessionId(reply("RTSP/1.0 200 OK\r\nSession: DEADBEEF;timeout=60\r\n\r\n"))
        )
    }

    @Test
    fun `parseSessionId is null when the receiver sends no Session`() {
        assertNull(parseSessionId(reply("RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n")))
    }

    // --- Control port choice ---

    @Test
    fun `advertised AirPlay port 7000 falls back to RAOP port 5000`() {
        assertEquals(listOf(7000, 5000), controlPortCandidates(7000))
    }

    @Test
    fun `other advertised ports are tried alone`() {
        assertEquals(listOf(5000), controlPortCandidates(5000))
        assertEquals(listOf(49152), controlPortCandidates(49152))
        assertEquals(listOf(7001), controlPortCandidates(7001))
    }

    @Test
    fun `no advertised port uses RAOP port 5000`() {
        assertEquals(listOf(5000), controlPortCandidates(0))
        assertEquals(listOf(5000), controlPortCandidates(-1))
    }
}
