package com.creolecast.app.airplay2

/** Apple binary property lists (`bplist00`) as AirPlay 2 RTSP bodies use them. */
object BinaryPlist {

    /** RTSP Content-Type of a binary plist body. */
    const val CONTENT_TYPE = "application/x-apple-binary-plist"

    // Realtime audio stream (RTP type 96) carrying ALAC.
    private const val STREAM_TYPE_REALTIME_AUDIO = 96L
    private const val COMPRESSION_TYPE_ALAC = 2L

    // AirPlay audioFormat bit for ALAC/44100/S16/2 (262144).
    private const val AUDIO_FORMAT_ALAC_44100_S16_STEREO = 0x40000L

    fun encode(root: Map<String, Any?>): ByteArray = BinaryPlistWriter.encode(root)

    /**
     * Decodes a `bplist00` document whose root is a dictionary (else an empty map).
     * Malformed or truncated input throws [IllegalArgumentException] and nothing else.
     */
    fun decode(data: ByteArray): Map<String, Any?> = BinaryPlistReader(data).readRoot()

    // Divergence from the Go reference /tmp/doubletake/internal/airplay/mirror.go
    // (sessionPlist/controlPlist at :256-293, audio stream descriptor at :755-766
    // and addScreenAudioStreamFields at :1123-1135): that sender mirrors the
    // screen, so it also emits isScreenMirroringSession, usingScreen and
    // isMedia=false. This app streams audio only, so it omits the screen keys and
    // declares the audio stream as the main media instead.

    fun makeSessionPlist(params: SessionPlistParams): ByteArray {
        val session = LinkedHashMap<String, Any?>()
        session["deviceID"] = params.deviceId
        session["macAddress"] = params.deviceId
        session["sessionUUID"] = params.sessionUuid.toString().uppercase()
        session["sourceVersion"] = params.sourceVersion
        session["timingProtocol"] = params.timingProtocol
        session["osBuildVersion"] = "13F69"
        session["model"] = params.model
        session["name"] = params.name
        if (params.timingProtocol == "PTP") {
            // PTP receivers match the sender by peer identity instead of a
            // sender-side timing port (mirror.go:270-277).
            val peer = linkedMapOf<String, Any?>(
                "ID" to (params.timingPeerId ?: params.deviceId),
                "SupportsClockPortMatchingOverride" to true,
                "DeviceType" to 0L,
                "Addresses" to listOf(params.timingPeerAddress ?: "0.0.0.0")
            )
            session["timingPeerInfo"] = peer
            session["timingPeerList"] = listOf(peer)
        } else {
            session["timingPort"] = params.timingPort.toLong()
        }
        session["updateSessionRequest"] = false
        session["combinedGetInfoWithControlSetup"] = true
        return encode(session)
    }

    /** Body of the stream TEARDOWN that precedes the session TEARDOWN. */
    fun makeStreamTeardownPlist(): ByteArray =
        encode(mapOf("streams" to listOf(mapOf("type" to STREAM_TYPE_REALTIME_AUDIO))))

    /**
     * Audio stream descriptor. Receivers advertising feature bit 59 take the
     * `streamConnections` form and reject a top-level `controlPort`; older ones
     * require the reverse (mirror.go `addScreenAudioStreamFields`).
     */
    fun makeStreamPlist(params: StreamPlistParams): ByteArray {
        val shk = params.shk
        val stream = LinkedHashMap<String, Any?>()
        stream["type"] = STREAM_TYPE_REALTIME_AUDIO
        stream["streamConnectionID"] = params.streamConnectionId
        stream["ct"] = COMPRESSION_TYPE_ALAC
        stream["spf"] = params.spf.toLong()
        stream["sr"] = params.sampleRate.toLong()
        stream["audioFormat"] = AUDIO_FORMAT_ALAC_44100_S16_STEREO
        stream["audioMode"] = "default"
        stream["latencyMin"] = params.latencyMin
        stream["latencyMax"] = params.latencyMax
        if (shk != null) {
            stream["shk"] = shk
        }
        if (params.useStreamConnections) {
            // isMedia is true here where the reference sends false: this stream
            // is the session's main media, not a screen-mirroring side channel.
            stream["isMedia"] = true
            stream["supportsDynamicStreamID"] = true
            stream["streamConnections"] = linkedMapOf<String, Any?>(
                "streamConnectionTypeRTP" to linkedMapOf<String, Any?>(
                    "streamConnectionKeyUseStreamEncryptionKey" to (shk != null)
                ),
                "streamConnectionTypeRTCP" to linkedMapOf<String, Any?>(
                    "streamConnectionKeyPort" to params.controlPort.toLong()
                )
            )
        } else {
            stream["controlPort"] = params.controlPort.toLong()
        }
        return encode(mapOf("streams" to listOf(stream)))
    }
}
