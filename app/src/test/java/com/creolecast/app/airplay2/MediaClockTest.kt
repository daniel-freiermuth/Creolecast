package com.creolecast.app.airplay2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClockTest {

    private var nowNs = 0L
    private val clock = MediaClock { nowNs }

    private fun headers(receivedMs: Long, processingMs: Long) = mapOf(
        "X-Apple-RequestReceivedTimestamp" to receivedMs.toString(),
        "X-Apple-ProcessingTime" to processingMs.toString()
    )

    private fun seconds(timestamp: Long) = (timestamp ushr 32).toDouble() + (timestamp and 0xFFFFFFFFL) / 4294967296.0

    @Test
    fun `compactTimestamp encodes 32_32 fixed point seconds`() {
        assertEquals((3L shl 32) or 0x80000000L, MediaClock.compactTimestamp(3_500_000_000L))
        assertEquals(0L, MediaClock.compactTimestamp(-5))
    }

    @Test
    fun `unconfigured clock has no time`() {
        assertNull(clock.now())
    }

    @Test
    fun `setup anchor is receiver received plus processing time, then advances locally`() {
        nowNs = 1_000_000_000L
        assertTrue(clock.configureFromSetup(0x1234, headers(5_000, 2), receivedAtNs = nowNs))
        assertEquals(0x1234L, clock.timelineId)
        assertEquals(5.002, seconds(clock.now()!!), 1e-6)

        nowNs += 250_000_000L
        assertEquals(5.252, seconds(clock.now()!!), 1e-6)
    }

    @Test
    fun `clock headers are matched case-insensitively`() {
        val lower = mapOf("x-apple-requestreceivedtimestamp" to "10", "x-apple-processingtime" to "0")
        assertTrue(clock.configureFromSetup(1, lower, receivedAtNs = 0))
    }

    @Test
    fun `missing headers keep the timeline identity but leave the clock unanchored`() {
        assertFalse(clock.configureFromSetup(0x99, emptyMap(), receivedAtNs = 0))
        assertEquals(0x99L, clock.timelineId)
        assertNull(clock.now())
    }

    @Test
    fun `local clock maps the monotonic source one to one`() {
        nowNs = 42_000_000_000L
        clock.configureFromLocalClock(0x77)
        nowNs += 1_500_000_000L
        assertEquals(43.5, seconds(clock.now()!!), 1e-6)
    }

    @Test
    fun `reanchor never moves the timeline backwards`() {
        nowNs = 0
        clock.configureFromSetup(1, headers(10_000, 0), receivedAtNs = 0)
        nowNs = 2_000_000_000L
        // A delayed sample claims 11.0 s, but the current mapping already says 12.0 s.
        assertTrue(clock.reanchor(headers(11_000, 0), receivedAtNs = nowNs))
        assertEquals(12.0, seconds(clock.now()!!), 1e-6)
    }

    @Test
    fun `reanchor moves forward to a fresher sample`() {
        nowNs = 0
        clock.configureFromSetup(1, headers(10_000, 0), receivedAtNs = 0)
        nowNs = 1_000_000_000L
        clock.reanchor(headers(11_300, 0), receivedAtNs = nowNs)
        assertEquals(11.3, seconds(clock.now()!!), 1e-6)
    }

    @Test
    fun `reanchor without clock headers changes nothing`() {
        nowNs = 0
        clock.configureFromSetup(1, headers(10_000, 0), receivedAtNs = 0)
        assertFalse(clock.reanchor(mapOf("CSeq" to "3"), receivedAtNs = 0))
        assertEquals(10.0, seconds(clock.now()!!), 1e-6)
    }
}
