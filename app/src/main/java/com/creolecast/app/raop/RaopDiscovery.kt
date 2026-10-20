package com.creolecast.app.raop

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.IOException
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/** TXT keys forwarded to the session, raw (`et` is a list, see [RaopTxt]). */
val RAOP_SESSION_TXT_KEYS = listOf("et")

data class RaopDevice(
    val name: String,
    val host: String,
    val port: Int,
    /** The TXT fields of [RAOP_SESSION_TXT_KEYS], `am` (model) and `sr` that are present. */
    val txt: Map<String, String>
) {
    val model: String? get() = txt["am"]
    val sampleRate: Int get() = txt["sr"]?.toIntOrNull() ?: 44100
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

                        val txt = (RAOP_SESSION_TXT_KEYS + listOf("am", "sr")).mapNotNull { key ->
                            info.getPropertyString(key)?.let { key to it }
                        }.toMap()
                        Log.d("RaopDiscovery", "Found RAOP device: $name at $host:$port $txt")

                        if (RaopTxt.isSupported(RaopTxt.intList(txt["et"]))) {
                            onDeviceFound(RaopDevice(name, host, port, txt))
                        } else {
                            Log.w(
                                "RaopDiscovery",
                                "Device $name needs FairPlay/MFi encryption (et=${txt["et"]}), " +
                                    "skipping"
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
