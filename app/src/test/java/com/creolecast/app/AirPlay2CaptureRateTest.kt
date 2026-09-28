package com.creolecast.app

import com.creolecast.app.airplay2.BinaryPlist
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The AirPlay 2 SETUP descriptor is built from the rate the service captures at
 * ([AudioCastService.captureSampleRate]), and it must describe that PCM truthfully:
 * audioFormat 0x40000 is ALAC/44100/16/2, so `sr` has to be 44100 too.
 */
class AirPlay2CaptureRateTest {

    @Test
    fun `AirPlay 2 descriptor declares the rate the service captures at`() {
        val captureRate = AudioCastService.captureSampleRate("AirPlay2")
        val decoded = BinaryPlist.decode(
            BinaryPlist.makeStreamPlist(
                controlPort = 6001,
                shk = null,
                streamConnectionId = 1L,
                sampleRate = captureRate,
                spf = 352,
                latencyMin = 0L,
                latencyMax = 3748L,
                useStreamConnections = true
            )
        )
        val stream = (decoded["streams"] as List<*>).single() as Map<*, *>
        assertEquals("audioFormat must stay ALAC/44100/16/2", 0x40000L, stream["audioFormat"])
        assertEquals("sr must match the ALAC/44100 audioFormat", 44100L, stream["sr"])
    }
}
