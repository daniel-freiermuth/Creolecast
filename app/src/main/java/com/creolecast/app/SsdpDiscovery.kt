package com.creolecast.app

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Finds DLNA/UPnP media renderers: repeatedly multicasts SSDP M-SEARCH queries and turns
 * the device description behind each reply's LOCATION into a [Server] for [onRenderer].
 */
internal class SsdpDiscovery(
    private val ioDispatcher: CoroutineDispatcher,
    private val onRenderer: (Server) -> Unit
) {
    /** Searches until the calling coroutine is cancelled. */
    suspend fun run() = withContext(ioDispatcher) {
        val ssdpAddress = InetAddress.getByName(SSDP_MULTICAST_ADDRESS)
        while (isActive) {
            SEARCH_TARGETS.forEach { st ->
                try {
                    search(ssdpAddress, st)
                } catch (e: IOException) {
                    Log.e(TAG, "SSDP error for $st", e)
                }
                delay(SEARCH_TARGET_INTERVAL_MS)
            }
            delay(SEARCH_CYCLE_DELAY_MS) // Repeat full cycle
        }
    }

    /** Sends one M-SEARCH for [st] and resolves every reply received within the window. */
    private fun CoroutineScope.search(ssdpAddress: InetAddress, st: String) {
        val query = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 3\r\n" +
            "ST: $st\r\n\r\n"
        DatagramSocket().use { socket ->
            socket.soTimeout = RESPONSE_WINDOW_MS
            val packet = DatagramPacket(query.toByteArray(), query.length, ssdpAddress, SSDP_PORT)
            socket.send(packet)

            val buffer = ByteArray(2048)
            val endTime = System.currentTimeMillis() + RESPONSE_WINDOW_MS
            while (System.currentTimeMillis() < endTime && isActive) {
                val response = socket.receiveOrNull(buffer, TAG) ?: break
                val host = response.address.hostAddress
                val location = locationHeader(String(response.data, 0, response.length))
                if (host != null && location != null) {
                    launch { resolveDevice(location, host) }
                }
            }
        }
    }

    private suspend fun resolveDevice(location: String, hostAddress: String) =
        withContext(ioDispatcher) {
            try {
                val locationUri = URI(location)
                if (isTrustedLocation(locationUri, hostAddress)) {
                    val xml = fetchDescription(locationUri)
                    rendererFromDescription(xml, locationUri, hostAddress)?.let(onRenderer)
                }
            } catch (e: URISyntaxException) {
                Log.d(TAG, "Ignoring malformed SSDP LOCATION from $hostAddress", e)
            } catch (e: IOException) {
                Log.d(TAG, "Could not read device description from $hostAddress", e)
            }
        }

    private fun fetchDescription(locationUri: URI): String {
        val connection = locationUri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = DESCRIPTION_TIMEOUT_MS
        connection.readTimeout = DESCRIPTION_TIMEOUT_MS
        return connection.inputStream.use { readBounded(it, MAX_DEVICE_DESCRIPTION_BYTES) }
    }

    companion object {
        private const val TAG = "DiscoveryManager"
        private const val SSDP_MULTICAST_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val RESPONSE_WINDOW_MS = 4000
        private const val SEARCH_TARGET_INTERVAL_MS = 1000L
        private const val SEARCH_CYCLE_DELAY_MS = 5000L
        private const val DESCRIPTION_TIMEOUT_MS = 3000
        private const val MAX_DEVICE_DESCRIPTION_BYTES = 262_144
        private const val LOCATION_HEADER = "LOCATION:"
        private const val AV_TRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val RENDERING_CONTROL_SERVICE =
            "urn:schemas-upnp-org:service:RenderingControl:1"
        private val SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "ssdp:all",
            "upnp:rootdevice"
        )

        private fun locationHeader(response: String): String? = response.split("\r\n")
            .find { it.startsWith(LOCATION_HEADER, true) }
            ?.substring(LOCATION_HEADER.length)
            ?.trim()

        /**
         * The LOCATION header is attacker-controllable by anything on the LAN that can send
         * an SSDP reply. Requiring it to point back at the address the UDP reply actually
         * came from stops it being used to make this device fetch an arbitrary internal URL
         * (SSRF) instead of the description of the device that just replied.
         */
        private fun isTrustedLocation(locationUri: URI, hostAddress: String): Boolean {
            val scheme = locationUri.scheme?.lowercase()
            return (scheme == "http" || scheme == "https") && locationUri.host == hostAddress
        }

        /** Reads at most [maxBytes] from [input] as UTF-8 text, so a malicious/misbehaving
         *  device can't hang discovery by streaming an unbounded response body. */
        private fun readBounded(input: InputStream, maxBytes: Int): String {
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (buffer.size() < maxBytes) {
                val toRead = minOf(chunk.size, maxBytes - buffer.size())
                val read = input.read(chunk, 0, toRead)
                if (read < 0) break
                buffer.write(chunk, 0, read)
            }
            return buffer.toString("UTF-8")
        }

        /** The renderer a device description describes, or null if it has no friendlyName. */
        private fun rendererFromDescription(
            xml: String,
            locationUri: URI,
            hostAddress: String
        ): Server? {
            val friendlyName = xml.substringAfter(
                "<friendlyName>",
                ""
            ).substringBefore("</friendlyName>")
            if (friendlyName.isEmpty()) return null

            val extraParts = listOfNotNull(
                controlUrl(xml, AV_TRANSPORT_SERVICE, locationUri, hostAddress)
                    ?.let { ExtraFields.encode("av_control", it) },
                controlUrl(xml, RENDERING_CONTROL_SERVICE, locationUri, hostAddress)
                    ?.let { ExtraFields.encode("rc_control", it) }
            )
            return Server(
                name = friendlyName,
                host = hostAddress,
                port = 0,
                version = "1.0",
                codecs = listOf("pcm"),
                sampleRate = 48000,
                channels = 2,
                platform = "DLNA",
                extra = if (extraParts.isEmpty()) null else extraParts.joinToString(";")
            )
        }

        /** The trusted control URL of [serviceType] in the description, if it has one. */
        private fun controlUrl(
            xml: String,
            serviceType: String,
            locationUri: URI,
            hostAddress: String
        ): String? {
            if (!xml.contains(serviceType)) return null
            val raw = xml.substringAfter(serviceType)
                .substringAfter("<controlURL>", "")
                .substringBefore("</controlURL>")
            return resolveTrustedControlUrl(raw, locationUri, hostAddress)
        }

        /**
         * Resolves a UPnP <controlURL> value against the (already host-validated) device
         * description location. A relative path is joined onto that location as usual. An
         * absolute URL is only accepted if it points back at the same host that answered
         * the SSDP query - otherwise the device description itself could redirect SOAP
         * control requests to an arbitrary third-party host/port (confused-deputy SSRF),
         * so it's dropped instead of trusted. Never returns an empty string.
         */
        internal fun resolveTrustedControlUrl(
            raw: String,
            locationUri: URI,
            expectedHost: String
        ): String? = when {
            raw.isEmpty() -> null

            !isAbsoluteHttpUrl(raw) -> {
                val portPart = if (locationUri.port == -1) "" else ":${locationUri.port}"
                val separator = if (raw.startsWith("/")) "" else "/"
                "${locationUri.scheme}://${locationUri.host}$portPart$separator$raw"
            }

            hostOf(raw) == expectedHost -> raw

            else -> null
        }

        private fun isAbsoluteHttpUrl(raw: String): Boolean =
            raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true)

        private fun hostOf(url: String): String? = try {
            URI(url).host
        } catch (ignored: URISyntaxException) {
            // A malformed absolute URL has no host that could be trusted.
            null
        }
    }
}
