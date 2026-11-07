package com.creolecast.app.cast

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ReceiverVolume.dlnaVolumeForDb].
 */
class DlnaVolumeForDbTest {

    @Test
    fun `range endpoints map to silent and full volume`() {
        assertEquals(0, ReceiverVolume.dlnaVolumeForDb(-30.0))
        assertEquals(100, ReceiverVolume.dlnaVolumeForDb(0.0))
    }

    @Test
    fun `every volume step maps monotonically to a distinct level`() {
        // The volume key / notification / route provider paths all send whole dB steps.
        val levels = (-30..0).map { ReceiverVolume.dlnaVolumeForDb(it.toDouble()) }
        assertEquals(levels.sorted(), levels)
        assertEquals(levels.size, levels.toSet().size)
    }

    @Test
    fun `whole steps that land on integer percentages are exact`() {
        assertEquals(10, ReceiverVolume.dlnaVolumeForDb(-27.0))
        assertEquals(50, ReceiverVolume.dlnaVolumeForDb(-15.0))
        assertEquals(90, ReceiverVolume.dlnaVolumeForDb(-3.0))
    }

    @Test
    fun `values outside the dB range are clamped`() {
        assertEquals(0, ReceiverVolume.dlnaVolumeForDb(-144.0))
        assertEquals(100, ReceiverVolume.dlnaVolumeForDb(6.0))
    }
}
