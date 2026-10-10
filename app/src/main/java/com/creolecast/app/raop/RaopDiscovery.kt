package com.creolecast.app.raop

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.IOException
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

data class RaopDevice(
    val name: String,
    val host: String,
    val port: Int,
    val model: String? = null,
    // TXT "et"
    val encryptionType: Int = 0,
    // TXT "sr"
    val sampleRate: Int = 44100,
    // TXT "cn" (0=PCM, 1=ALAC)
    val codec: Int = 0
) {
    fun supportsRsa(): Boolean = (encryptionType and 1) != 0
}

class RaopDiscovery(private val context: Context) {
    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start(onDeviceFound: (RaopDevice) -> Unit) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("RaopDiscoveryLock").apply {
            setReferenceCounted(true)
            acquire()
        }

        val localAddr = RaopUtils.getLocalIpAddress() ?: run {
            Log.e("RaopDiscovery", "Could not get local IP address")
            return
        }

        Thread { startJmdns(localAddr, onDeviceFound) }.start()
    }

    // Body of the discovery thread: an exception escaping it would kill the process,
    // and JmDNS start-up can fail in ways beyond its declared IOException.
    @Suppress("TooGenericExceptionCaught")
    private fun startJmdns(localAddr: InetAddress, onDeviceFound: (RaopDevice) -> Unit) {
        try {
            jmdns = JmDNS.create(localAddr)
            jmdns?.addServiceListener(
                "_raop._tcp.local.",
                object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        jmdns?.requestServiceInfo(event.type, event.name)
                    }

                    override fun serviceRemoved(event: ServiceEvent) {
                        Log.d("RaopDiscovery", "Service removed: ${event.name}")
                    }

                    override fun serviceResolved(event: ServiceEvent) {
                        val info = event.info
                        val host = info.inetAddresses.firstOrNull()?.hostAddress ?: return
                        val port = info.port

                        // info.name is often "MAC@DeviceName", extract DeviceName
                        val name = info.name.substringAfter("@")

                        val et = info.getPropertyString("et")?.toIntOrNull() ?: 0
                        val sr = info.getPropertyString("sr")?.toIntOrNull() ?: 44100
                        val cn = info.getPropertyString("cn")?.toIntOrNull() ?: 0
                        val model = info.getPropertyString("am")

                        Log.d(
                            "RaopDiscovery",
                            "Found RAOP device: $name at $host:$port (et=$et, sr=$sr, cn=$cn)"
                        )

                        // Capability check as requested
                        if (et == 0 || (et and 1) != 0) {
                            onDeviceFound(RaopDevice(name, host, port, model, et, sr, cn))
                        } else {
                            Log.w(
                                "RaopDiscovery",
                                "Device $name does not support RSA 1 encryption (et=$et), skipping"
                            )
                        }
                    }
                }
            )
            Log.i("RaopDiscovery", "JmDNS discovery started on $localAddr")
        } catch (e: Exception) {
            Log.e("RaopDiscovery", "Failed to start JmDNS", e)
        }
    }

    fun stop() {
        Thread {
            try {
                jmdns?.close()
                jmdns = null
                // Reference-counted lock acquired once in start(); releasing it when no
                // longer held would throw "under-locked".
                multicastLock?.let { if (it.isHeld) it.release() }
                multicastLock = null
                Log.i("RaopDiscovery", "JmDNS discovery stopped")
            } catch (e: IOException) {
                Log.e("RaopDiscovery", "Error stopping JmDNS", e)
            }
        }.start()
    }
}
