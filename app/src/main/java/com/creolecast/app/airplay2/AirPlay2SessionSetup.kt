package com.creolecast.app.airplay2

import android.util.Log
import java.net.HttpURLConnection
import java.security.SecureRandom
import java.util.UUID

/** The RTSP steps that set up a session once pairing is done: SETUP, RECORD, SETUP. */
internal class AirPlay2SessionSetup(
    private val config: AirPlay2ClientConfig,
    private val control: AirPlay2ControlChannel,
    private val clock: AirPlay2Clock,
    private val random: SecureRandom
) {
    private companion object {
        const val TAG = "AirPlay2Client"
        const val SOURCE_VERSION_NTP = "280.33"
        const val SOURCE_VERSION_PTP = "980.71.1"
        const val BPLIST_CONTENT_TYPE = "application/x-apple-binary-plist"
        const val AUDIO_KEY_SIZE = 32

        fun positivePort(value: Any?): Int? = (value as? Long)?.toInt()?.takeIf { it > 0 }

        fun connectionPort(connections: Map<*, *>?, key: String): Int? =
            positivePort((connections?.get(key) as? Map<*, *>)?.get("streamConnectionKeyPort"))
    }

    /** Data and control ports from the stream SETUP reply; 0 where it named none. */
    private class StreamPorts(val data: Int, val control: Int)

    private val sessionUuid = UUID.randomUUID()

    /** Addresses the session in [AirPlay2ControlChannel.sessionUrl] and the stream plist. */
    val streamConnectionId = random.nextLong() and Long.MAX_VALUE

    /** Receiver event port, when a SETUP reply named one. */
    var eventPort = 0
        private set

    fun setupSession(timingPort: Int): Boolean {
        val usePtp = clock.usePtp
        val localAddress = control.localAddress?.hostAddress ?: "0.0.0.0"
        val plist = BinaryPlist.makeSessionPlist(
            SessionPlistParams(
                sessionUuid = sessionUuid,
                deviceId = config.deviceId,
                name = config.clientName,
                model = "Linux",
                sourceVersion = if (usePtp) SOURCE_VERSION_PTP else SOURCE_VERSION_NTP,
                timingProtocol = if (usePtp) "PTP" else "NTP",
                timingPort = timingPort,
                timingPeerId = config.deviceId,
                timingPeerAddress = localAddress
            )
        )
        val resp = control.rtsp("SETUP", BPLIST_CONTENT_TYPE, plist)
        val receivedAt = System.nanoTime()
        if (resp.code != HttpURLConnection.HTTP_OK) {
            Log.e(TAG, "SETUP session failed: ${resp.code}")
            return false
        }
        control.sessionId = resp.headers["Session"]?.substringBefore(";")?.trim()
            ?: sessionUuid.toString().uppercase()
        val clockId = resp.body?.let { parseSessionResponse(it) } ?: 0L
        return !usePtp || clock.configurePtp(clockId, resp.headers, receivedAt)
    }

    fun record(): Boolean {
        val resp = control.rtsp(
            "RECORD",
            null,
            null,
            extraHeaders = listOf("Range" to "npt=0-", "RTP-Info" to "seq=0;rtptime=0")
        )
        Log.d(TAG, "RECORD response: code=${resp.code}")
        return resp.code == HttpURLConnection.HTTP_OK ||
            resp.code == HttpURLConnection.HTTP_CREATED
    }

    /** The audio stream's ports and key, or null when the receiver refused it. */
    fun setupStream(controlPort: Int, useStreamConnections: Boolean): AudioStreamTarget? {
        // The reference publishes a fresh random ChaCha key as `shk` for
        // encrypted sessions (mirror.go generateAudioChaChaKey); it is not
        // derived from the pairing secret. Unencrypted sessions send no key and
        // stream in the clear.
        val shk = if (control.encrypted) ByteArray(AUDIO_KEY_SIZE).also(random::nextBytes) else null

        val plist = BinaryPlist.makeStreamPlist(
            StreamPlistParams(
                controlPort = controlPort,
                shk = shk,
                streamConnectionId = streamConnectionId,
                sampleRate = config.sampleRate,
                spf = config.frameSize,
                latencyMin = 0L,
                latencyMax = config.latencySamples.toLong(),
                useStreamConnections = useStreamConnections
            )
        )
        val resp = control.rtsp("SETUP", BPLIST_CONTENT_TYPE, plist)
        clock.reanchorIfFollowing(resp.headers)
        if (resp.code != HttpURLConnection.HTTP_OK) {
            Log.e(TAG, "SETUP stream failed: ${resp.code}")
            return null
        }
        val ports = resp.body?.let { parseStreamResponse(it) } ?: StreamPorts(0, 0)
        if (ports.data <= 0) Log.e(TAG, "SETUP stream returned no dataPort")
        return if (ports.data > 0) AudioStreamTarget(ports.data, ports.control, shk) else null
    }

    /** Returns the receiver's PTP ClockID, or 0 when it publishes none. */
    private fun parseSessionResponse(body: ByteArray): Long = try {
        val dict = BinaryPlist.decode(body)
        positivePort(dict["eventPort"])?.let { eventPort = it }
        (dict["timingPeerInfo"] as? Map<*, *>)?.get("ClockID") as? Long ?: 0L
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "parseSessionResponse failed", e)
        0L
    }

    private fun parseStreamResponse(body: ByteArray): StreamPorts = try {
        val dict = BinaryPlist.decode(body)
        positivePort(dict["eventPort"])?.let { eventPort = it }
        val stream = (dict["streams"] as? List<*>)?.firstOrNull() as? Map<*, *>
        // The streamConnections layout answers with both ports nested instead
        // of dataPort/controlPort.
        val connections = stream?.get("streamConnections") as? Map<*, *>
        StreamPorts(
            data = connectionPort(connections, "streamConnectionTypeRTP")
                ?: positivePort(stream?.get("dataPort"))
                ?: 0,
            control = connectionPort(connections, "streamConnectionTypeRTCP")
                ?: positivePort(stream?.get("controlPort"))
                ?: 0
        )
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "SETUP stream plist parse failed", e)
        StreamPorts(0, 0)
    }
}
