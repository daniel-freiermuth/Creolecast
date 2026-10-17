package com.creolecast.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [CastRoutes.publishableServers]. MediaRoute2Info.Builder throws
 * IllegalArgumentException("name must not be empty") for an empty name, and the provider
 * builds routes on the main dispatcher with no exception handler, so a single empty-named
 * discovery entry used to crash the app.
 */
class PublishableServersTest {

    private fun server(name: String, host: String, port: Int = 0, platform: CastPlatform) = Server(
        name = name,
        host = host,
        port = port,
        version = "1.0",
        codecs = listOf("pcm"),
        sampleRate = 48000,
        channels = 2,
        platform = platform
    )

    @Test
    fun `UDP reply without server_name is not published`() {
        // AudioCastBroadcastDiscovery stores optString("server_name"), which is
        // "" when a LAN host answers DISCOVER_AUDIOCAST with just "{}".
        val nameless = server("", "192.168.188.40", 0, CastPlatform.ARIACAST)
        val named = server("Living Room", "192.168.188.41", 12889, CastPlatform.ARIACAST)

        assertEquals(listOf(named), CastRoutes.publishableServers(listOf(nameless, named)))
    }

    @Test
    fun `named servers are all published in discovery order`() {
        val servers = listOf(
            server("Zynthian", "192.168.188.25", 5000, CastPlatform.AIRPLAY),
            server("FRITZ!Box 3272", "192.168.188.1", platform = CastPlatform.DLNA),
            server("Kitchen", "192.168.188.30", 12889, CastPlatform.ARIACAST)
        )

        assertEquals(servers, CastRoutes.publishableServers(servers))
    }
}
