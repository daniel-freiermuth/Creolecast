package com.creolecast.app.airplay2

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeAnnouncePacketTest {

    private val wallClockNtp = (3_999_609_405L shl 32) // ~2026 as seconds since 1900

    @Test
    fun `ptp time announce carries the receiver timeline, not wall clock`() {
        val receivedAt = 900_000_000_000L
        val clock = PtpMediaClock.fromSetupHeaders(
            mapOf("X-Apple-RequestReceivedTimestamp" to "27520", "X-Apple-ProcessingTime" to "1"),
            receivedAt
        )!!

        val packet = AirPlay2Client.timeAnnouncePacket(
            first = true,
            rtpTimestamp = 10_000,
            ptpClock = clock,
            ptpTimelineId = 0x4454424c54414b45,
            localNanos = receivedAt + 500_000_000L,
            ntpTime = wallClockNtp
        )

        val buf = ByteBuffer.wrap(packet)
        assertEquals(28, packet.size)
        assertEquals(0x90, packet[0].toInt() and 0xFF)
        assertEquals(0xD7, packet[1].toInt() and 0xFF)
        assertEquals(10_000 - 3748, buf.getInt(4))
        assertEquals(28_021_000_000L, buf.getLong(8))
        assertEquals(10_000, buf.getInt(16))
        assertEquals(0x4454424c54414b45, buf.getLong(20))
    }

    @Test
    fun `ntp time announce carries the ntp wall clock`() {
        val packet = AirPlay2Client.timeAnnouncePacket(
            first = false,
            rtpTimestamp = 10_000,
            ptpClock = null,
            ptpTimelineId = 0x4454424c54414b45,
            localNanos = 1L,
            ntpTime = wallClockNtp
        )

        val buf = ByteBuffer.wrap(packet)
        assertEquals(20, packet.size)
        assertEquals(0x80, packet[0].toInt() and 0xFF)
        assertEquals(0xD4, packet[1].toInt() and 0xFF)
        assertEquals(wallClockNtp, buf.getLong(8))
        assertEquals(10_000, buf.getInt(16))
    }
}
