package com.creolecast.app.raop

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

object RaopUtils {
    private const val TAG = "RaopUtils"

    /** First IPv4 address of an up, non-loopback, non-VPN interface, or null if none. */
    fun getLocalIpAddress(): java.net.InetAddress? = try {
        NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty()
            .filter(::isLanInterface)
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address }
    } catch (e: SocketException) {
        Log.w(TAG, "Could not enumerate network interfaces", e)
        null
    }

    private fun isLanInterface(iface: NetworkInterface): Boolean {
        if (iface.isLoopback || !iface.isUp) return false
        val name = iface.name.lowercase()
        return !(name.contains("tun") || name.contains("ppp") || name.contains("tap"))
    }
}
