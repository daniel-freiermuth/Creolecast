package com.creolecast.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit tests for [DiscoveryManager.identityOf], the tuple [DiscoveryManager.removeServer]
 * matches on. Deletion used to key off the map entry's name, which silently missed every
 * entry stored under a derived key ("name::platform", "name@host", or the bare host).
 */
class ServerIdentityTest {

    private fun server(
        name: String,
        host: String,
        port: Int = 0,
        platform: CastPlatform,
        extra: String? = null,
        version: String = "1.0"
    ) = Server(
        name = name,
        host = host,
        port = port,
        version = version,
        codecs = listOf("pcm"),
        sampleRate = 48000,
        channels = 2,
        platform = platform,
        extra = extra
    )

    @Test
    fun `DLNA renderers sharing one host stay distinct`() {
        // Every DLNA entry carries port 0, so host+port+platform collides for all three
        // of these — the exact shape that crashed the system route provider.
        val router = server("FRITZ!Box 3272", "192.168.188.1", platform = CastPlatform.DLNA)
        val mediaServer = server("AVM FRITZ!Mediaserver", "192.168.188.1", platform = CastPlatform.DLNA)
        val gateway = server("InternetGatewayDeviceV2", "192.168.188.1", platform = CastPlatform.DLNA)

        val ids = listOf(router, mediaServer, gateway).map { DiscoveryManager.identityOf(it) }
        assertEquals(3, ids.toSet().size)
    }

    @Test
    fun `identity survives merged metadata`() {
        // handleResolvedService merges extra/version into an existing entry, so the copy
        // the UI is holding when the user taps delete can differ from the stored one.
        val asListed = server("Zynthian", "192.168.188.25", 5000, CastPlatform.AIRPLAY)
        val afterMerge = asListed.copy(extra = "et=1;sr=44100", version = "366.0")

        assertEquals(DiscoveryManager.identityOf(asListed), DiscoveryManager.identityOf(afterMerge))
    }

    @Test
    fun `same name on two hosts is not the same server`() {
        val first = server("Zynthian", "192.168.188.25", 5000, CastPlatform.AIRPLAY)
        val second = server("Zynthian", "192.168.188.26", 5000, CastPlatform.AIRPLAY)

        assertNotEquals(DiscoveryManager.identityOf(first), DiscoveryManager.identityOf(second))
    }

    @Test
    fun `one host reachable over two protocols is not the same server`() {
        // A receiver answering both _raop and _airplay is two separate list entries the
        // user can cast to independently, so deleting one must not match the other.
        val raop = server("Zynthian", "192.168.188.25", 5000, CastPlatform.AIRPLAY)
        val airplay2 = server("Zynthian", "192.168.188.25", 5000, CastPlatform.AIRPLAY2)

        assertNotEquals(DiscoveryManager.identityOf(raop), DiscoveryManager.identityOf(airplay2))
    }
}
