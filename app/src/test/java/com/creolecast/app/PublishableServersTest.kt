package com.creolecast.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [CastRouteProvider.publishableServers]. MediaRoute2Info.Builder throws
 * IllegalArgumentException("name must not be empty") for an empty name, and the provider
 * builds routes on the main dispatcher with no exception handler, so a single empty-named
 * discovery entry used to crash the app.
 */
class PublishableServersTest {

    private fun server(name: String, host: String, port: Int = 0, platform: String? = null) = Server(
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
        // DiscoveryManager.startUdpDiscoveryLoop stores optString("server_name"), which is
        // "" when a LAN host answers DISCOVER_AUDIOCAST with just "{}".
        val nameless = server("", "192.168.188.40", 0, "AriaCast")
        val named = server("Living Room", "192.168.188.41", 12889, "AriaCast")

        assertEquals(listOf(named), CastRouteProvider.publishableServers(listOf(nameless, named)))
    }

    @Test
    fun `named servers are all published in discovery order`() {
        val servers = listOf(
            server("Zynthian", "192.168.188.25", 5000, "AirPlay"),
            server("FRITZ!Box 3272", "192.168.188.1", platform = "DLNA"),
            server("Kitchen", "192.168.188.30", 12889, "AriaCast")
        )

        assertEquals(servers, CastRouteProvider.publishableServers(servers))
    }
}
