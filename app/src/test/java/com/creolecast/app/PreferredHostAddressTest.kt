package com.creolecast.app

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [DiscoveryManager.preferredHostAddress], which picks the one address a
 * [Server] connects to from every address an NSD service advertises.
 */
class PreferredHostAddressTest {

    private fun addr(literal: String): InetAddress = InetAddress.getByName(literal)

    @Test
    fun `IPv4 wins even when the receiver lists IPv6 first`() {
        val addresses = listOf(addr("fe80::1"), addr("2001:db8::7"), addr("192.168.1.20"))

        assertEquals("192.168.1.20", DiscoveryManager.preferredHostAddress(addresses))
    }

    @Test
    fun `IPv6-only receiver is still reachable`() {
        val addresses = listOf(addr("2001:db8::7"))

        assertEquals("2001:db8:0:0:0:0:0:7", DiscoveryManager.preferredHostAddress(addresses))
    }

    @Test
    fun `loopback is never chosen`() {
        val addresses = listOf(addr("127.0.0.1"), addr("::1"), addr("10.0.0.5"))

        assertEquals("10.0.0.5", DiscoveryManager.preferredHostAddress(addresses))
        assertNull(DiscoveryManager.preferredHostAddress(listOf(addr("127.0.0.1"), addr("::1"))))
    }

    @Test
    fun `no addresses yet means no entry`() {
        // handleResolvedService skips an update whose hostAddresses list is empty.
        assertNull(DiscoveryManager.preferredHostAddress(emptyList()))
    }
}
