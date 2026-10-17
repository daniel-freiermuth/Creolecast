package com.creolecast.app.cast

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The RAOP (AirPlay 1) RTSP handshake's wire framing ([RtspWire]) and its parsing of the
 * receiver's SETUP response, which decides where audio, sync and timing packets go.
 */
class RaopRtspHandshakeTest {

    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray())

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
                body
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

    // --- Response framing ---

    @Test
    fun `readResponse returns status and headers and stops at the blank line`() {
        val input = stream(
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\nSession: 1A2B\r\n\r\n" +
                "RTSP/1.0 200 OK\r\nCSeq: 2\r\nAudio-Latency: 11025\r\n\r\n"
        )
        assertEquals(
            listOf("RTSP/1.0 200 OK", "CSeq: 1", "Session: 1A2B"),
            RtspWire.readResponse(input)
        )
        assertEquals(
            listOf("RTSP/1.0 200 OK", "CSeq: 2", "Audio-Latency: 11025"),
            RtspWire.readResponse(input)
        )
    }

    @Test
    fun `readResponse accepts bare LF line endings`() {
        val input = stream("RTSP/1.0 200 OK\nCSeq: 4\n\nRTSP/1.0 200 OK\nCSeq: 5\n\n")
        assertEquals(listOf("RTSP/1.0 200 OK", "CSeq: 4"), RtspWire.readResponse(input))
        assertEquals(listOf("RTSP/1.0 200 OK", "CSeq: 5"), RtspWire.readResponse(input))
    }

    @Test
    fun `readResponse returns the lines read so far when the receiver closes mid-head`() {
        val input = stream("RTSP/1.0 200 OK\r\nCSeq: 1")
        assertEquals(listOf("RTSP/1.0 200 OK", "CSeq: 1"), RtspWire.readResponse(input))
        assertEquals(emptyList<String>(), RtspWire.readResponse(input))
    }

    // --- SETUP response: Transport ports ---

    @Test
    fun `parseServerPorts reads server, control and timing ports from Transport`() {
        val ports = parseServerPorts(
            listOf(
                "RTSP/1.0 200 OK",
                "CSeq: 1",
                "Transport: RTP/AVP/UDP;unicast;mode=record;" +
                    "server_port=6000;control_port=6001;timing_port=6002",
                "Session: 1A2B"
            )
        )
        assertEquals(ServerPorts(audio = 6000, control = 6001, timing = 6002), ports)
    }

    @Test
    fun `parseServerPorts tolerates spaces, extra parameters, any order and header case`() {
        val ports = parseServerPorts(
            listOf(
                "RTSP/1.0 200 OK",
                "transport: RTP/AVP/UDP; unicast; interleaved=0-1; mode=record; " +
                    "timing_port=53562; control_port=53561; server_port=53560"
            )
        )
        assertEquals(ServerPorts(audio = 53560, control = 53561, timing = 53562), ports)
    }

    @Test
    fun `parseServerPorts does not read the sender's own ports from other headers`() {
        val ports = parseServerPorts(
            listOf(
                "RTSP/1.0 200 OK",
                "X-Echo: control_port=1111;timing_port=2222",
                "Transport: RTP/AVP/UDP;unicast;server_port=6000;control_port=6001;timing_port=6002"
            )
        )
        assertEquals(ServerPorts(audio = 6000, control = 6001, timing = 6002), ports)
    }

    // --- SETUP response: Session ---

    @Test
    fun `parseSessionId drops the timeout parameter`() {
        assertEquals(
            "DEADBEEF",
            parseSessionId(listOf("RTSP/1.0 200 OK", "Session: DEADBEEF;timeout=60"))
        )
    }

    @Test
    fun `parseSessionId matches the header case-insensitively and trims`() {
        assertEquals("1", parseSessionId(listOf("RTSP/1.0 200 OK", "session:   1  ")))
    }

    @Test
    fun `parseSessionId is null when the receiver sends no Session`() {
        assertNull(parseSessionId(listOf("RTSP/1.0 200 OK", "CSeq: 1")))
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
