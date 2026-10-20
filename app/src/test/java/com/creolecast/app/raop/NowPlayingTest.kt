package com.creolecast.app.raop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingTest {
    @Test
    fun `re-publishing the same track and artwork sends nothing new`() {
        val tracker = NowPlayingTracker()
        val art = byteArrayOf(1, 2, 3)
        assertNotNull(tracker.trackInfoIfChanged("Song", "Band", "Record", 1000))
        assertNotNull(tracker.artworkIfChanged(art))
        assertNull(tracker.trackInfoIfChanged("Song", "Band", "Record", 1000))
        assertNull(tracker.artworkIfChanged(art.copyOf()))
    }

    @Test
    fun `a new track is a new item and needs its artwork again`() {
        val tracker = NowPlayingTracker()
        val art = byteArrayOf(1, 2, 3)
        val first = tracker.trackInfoIfChanged("Song", "Band", "Record", 1000)!!
        tracker.artworkIfChanged(art)
        val second = tracker.trackInfoIfChanged("Next", "Band", "Record", 1000)!!
        assertEquals(1L, itemId(first))
        assertEquals(2L, itemId(second))
        assertNotNull(tracker.artworkIfChanged(art))
    }

    @Test
    fun `progress wraps around the 32-bit RTP clock`() {
        // 30 s into a 180 s track, playing RTP time 66150 at 44.1 kHz.
        val progress = progressParameter(66150, 30_000, 180_000, 44100).decodeToString()
        assertEquals("progress: 4293710446/66150/6681150\r\n", progress)
    }

    /** The `mper` value inside an `mlit` container. */
    private fun itemId(dmap: ByteArray): Long {
        val i = dmap.decodeToString(throwOnInvalidSequence = false).indexOf("mper")
        return (0 until 8).fold(0L) { acc, k -> (acc shl 8) or (dmap[i + 8 + k].toLong() and 0xFF) }
    }
}
