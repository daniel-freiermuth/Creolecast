package com.creolecast.app.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The ANNOUNCE SDP must name the encoding [RaopAudioSender] actually puts in every RTP
 * packet: [RaopAlac.encodeUncompressed] frames, i.e. ALAC (`AppleLossless`). Declaring
 * `L16` makes receivers that honour the rtpmap (shairport-sync since PR #2299) decode the
 * ALAC bitstream as raw big-endian PCM and play noise.
 */
class RaopSdpTest {

    private val sdpLines = raopSdp(12345678, "192.168.1.2", "192.168.1.3", "").split("\r\n")

    @Test
    fun `ANNOUNCE declares payload type 96 as AppleLossless`() {
        val rtpmap = sdpLines.single { it.startsWith("a=rtpmap:96 ") }
        assertEquals("a=rtpmap:96 AppleLossless", rtpmap)
    }

    @Test
    fun `ANNOUNCE fmtp describes the 352-frame 16-bit stereo 44100 ALAC packets`() {
        val fmtp = sdpLines.single { it.startsWith("a=fmtp:96 ") }
        assertEquals("a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100", fmtp)
    }

    @Test
    fun `audio payload is an ALAC escape frame, not L16 PCM`() {
        // 352 stereo 16-bit little-endian frames, the size RaopAudioSender hands to the encoder.
        val pcm = ByteArray(1408) { (it * 37 + 11).toByte() }
        val frame = RaopAlac.encodeUncompressed(pcm)

        val l16 = ByteArray(pcm.size) { pcm[it xor 1] } // what an L16 payload would carry
        assertNotEquals("payload must not be plain L16", l16.toList(), frame.toList())

        // The 23-bit header fills the first 23 bits: element id 1 (channel pair element) on
        // top, the uncompressed escape flag last; the first sample starts at bit 23.
        val first24 = (frame[0].toInt() and 0xFF shl 16) or
            (frame[1].toInt() and 0xFF shl 8) or (frame[2].toInt() and 0xFF)
        assertEquals("ALAC element id", 1, first24 ushr 21)
        assertEquals("ALAC escape flag", 1, first24 ushr 1 and 1)
        val firstSample = ((first24 and 1) shl 15) or
            ((frame[3].toInt() and 0xFF) shl 7) or ((frame[4].toInt() and 0xFF) ushr 1)
        val firstPcmSample = ((pcm[1].toInt() and 0xFF) shl 8) or (pcm[0].toInt() and 0xFF)
        assertEquals("first sample, big-endian after the header", firstPcmSample, firstSample)
    }
}
