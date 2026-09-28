package com.creolecast.app.airplay2

import org.junit.Assert.*
import org.junit.Test

class PtpMediaClockTest {

    @Test
    fun `setup headers anchor the receiver timeline and local time advances it`() {
        val receivedAt = 123_456_789_000L
        val clock = PtpMediaClock.fromSetupHeaders(
            mapOf("X-Apple-RequestReceivedTimestamp" to "27520", "X-Apple-ProcessingTime" to "1"),
            receivedAt
        )!!
        assertEquals(27_521_000_000L, clock.nanosAt(receivedAt))
        assertEquals(29_021_000_000L, clock.nanosAt(receivedAt + 1_500_000_000L))
    }

    @Test
    fun `setup header names are matched case-insensitively`() {
        val clock = PtpMediaClock.fromSetupHeaders(
            mapOf("x-apple-requestreceivedtimestamp" to "5000", "x-apple-processingtime" to "2"),
            0L
        )!!
        assertEquals(5_002_000_000L, clock.nanosAt(0L))
    }

    @Test
    fun `setup headers without processing time are rejected`() {
        assertNull(PtpMediaClock.fromSetupHeaders(mapOf("X-Apple-RequestReceivedTimestamp" to "1"), 0L))
    }

    @Test
    fun `out of range setup headers are rejected`() {
        assertNull(PtpMediaClock.fromSetupHeaders(
            mapOf("X-Apple-RequestReceivedTimestamp" to "-1", "X-Apple-ProcessingTime" to "1"), 0L))
        assertNull(PtpMediaClock.fromSetupHeaders(
            mapOf("X-Apple-RequestReceivedTimestamp" to Long.MAX_VALUE.toString(), "X-Apple-ProcessingTime" to "1"), 0L))
    }

    @Test
    fun `local fallback reports the sender boot clock`() {
        val clock = PtpMediaClock.fromLocalClock(42_000_000_000L)
        assertEquals(43_000_000_000L, clock.nanosAt(43_000_000_000L))
    }
}
