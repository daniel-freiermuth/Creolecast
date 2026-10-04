package com.creolecast.app.cast

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Enumeration

/** Interface-name fragments of VPN and virtual interfaces receivers cannot reach. */
private val VIRTUAL_INTERFACE_MARKERS = listOf("tun", "ppp", "tap", "docker")

/** Private address ranges, most likely LAN first. */
private val PREFERRED_PREFIXES = listOf("192.168.", "10.", "172.")

/** Session-start failure reason when [localIpv4Address] finds no address to serve from. */
internal const val NO_LOCAL_ADDRESS = "no local IPv4 address to serve the stream from"

/**
 * This device's IPv4 address on the local network, as receivers should use it to fetch
 * our HTTP stream and artwork, or null when there is none. Loopback, down and
 * VPN/virtual interfaces are skipped; private ranges are preferred.
 */
internal fun localIpv4Address(): String? {
    val hosts = try {
        // Specified to return null when the device has no network interfaces at all.
        val interfaces: Enumeration<NetworkInterface>? = NetworkInterface.getNetworkInterfaces()
        interfaces?.toList().orEmpty()
            .filter { it.isLanCandidate() }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    } catch (e: SocketException) {
        Log.w(LOG_TAG, "Could not enumerate network interfaces: ${e.reason}")
        emptyList()
    }
    return PREFERRED_PREFIXES.firstNotNullOfOrNull { prefix ->
        hosts.find { it.startsWith(prefix) }
    } ?: hosts.firstOrNull()
}

private fun NetworkInterface.isLanCandidate(): Boolean {
    val lowerName = name.lowercase()
    return !isLoopback && isUp && VIRTUAL_INTERFACE_MARKERS.none { lowerName.contains(it) }
}
