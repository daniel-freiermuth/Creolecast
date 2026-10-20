package com.creolecast.app.airplay2

import android.util.Log
import java.net.DatagramSocket
import java.security.SecureRandom
import java.util.Locale

class NeedsPinException(val host: String) : Exception("PIN needed for host $host")

/**
 * AirPlay 2 audio sender.
 *
 * The protocol details are transcribed from the Go reference implementation the
 * F-Droid app "Mirror" (jqssun/android-display-mirror) uses through its
 * `doubletake` submodule: `internal/airplay/{pairing,client,mirror,audio}.go`.
 * Both projects are (L)GPL-3 licensed.
 *
 * Deviation from the reference: it streams *screen mirroring* audio, so it sets
 * `isScreenMirroringSession`/`usingScreen` and negotiates a video stream. This
 * client streams audio only and omits those.
 */
class AirPlay2Client(private val config: AirPlay2ClientConfig) {
    interface EventListener {
        fun onVolumeChange(db: Double) {}
        fun onRemoteCommand(command: String) {}
    }

    companion object {
        private const val TAG = "AirPlay2Client"

        /** Receiver feature bits (discovery.go). */
        private const val FEATURE_FPSAP = 14
        private const val FEATURE_PTP = 41
        private const val FEATURE_STREAM_CONNECTIONS = 59

        private const val MILLIS_PER_SECOND = 1000L

        /** RTP times are unsigned 32-bit and wrap. */
        private const val UINT32_MASK = 0xFFFFFFFFL
    }

    var eventListener: EventListener? = null

    @Volatile private var running = false

    /** Serialises control requests and the audio nonce counter. */
    private val lock = Any()
    private val secureRandom = SecureRandom()
    private val control = AirPlay2ControlChannel(config, lock)
    private val receiver = AirPlay2ReceiverInfo(config.host, config.txtPk)
    private val pairing = AirPlay2Pairing(config, control, receiver, secureRandom)
    private val clock = AirPlay2Clock(config.host, config.deviceId)
    private val setup = AirPlay2SessionSetup(config, control, clock, secureRandom)
    private val timingSocket = DatagramSocket(0)
    private val controlSocket = DatagramSocket(0)
    private val audio = AirPlay2AudioStream(config, controlSocket, lock, secureRandom)
    private val timingResponder = NtpTimingResponder(timingSocket) { running }
    private val events = AirPlay2EventChannel(
        config.host,
        isRunning = { running },
        listener = { eventListener }
    )
    private val heartbeat = AirPlay2Heartbeat(control, clock, audio) { running }
    private var useStreamConnections = false

    /**
     * Pairs, sets up the session and starts streaming; false when the receiver
     * refused a step, after which the caller should [close] the client.
     *
     * This is the session boundary for exceptions: any failure on the way
     * (socket, crypto, plist or receiver errors) is logged, releases what was
     * opened so far and also returns false.
     *
     * @throws NeedsPinException when the receiver wants a PIN we don't have.
     */
    @Suppress("TooGenericExceptionCaught") // Deliberate session boundary, see KDoc.
    fun connect(): Boolean = try {
        handshake().also { connected -> if (connected) startStreaming() }
    } catch (e: NeedsPinException) {
        close()
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "connect failed", e)
        close()
        false
    }

    fun sendAudioFrame(pcm: ByteArray): Boolean = audio.sendFrame(pcm)

    fun setVolume(db: Double) {
        val body = "volume: ${"%.6f".format(Locale.US, db)}\r\n"
        control.rtsp("SET_PARAMETER", "text/parameters", body.toByteArray(Charsets.UTF_8))
    }

    fun sendMetadata(
        title: String?,
        artist: String?,
        album: String?,
        artworkBytes: ByteArray? = null
    ) {
        val dict = linkedMapOf<String, Any?>()
        if (!title.isNullOrEmpty()) dict["dmap.itemname"] = title
        if (!artist.isNullOrEmpty()) dict["daap.songartist"] = artist
        if (!album.isNullOrEmpty()) dict["daap.songalbum"] = album
        if (dict.isNotEmpty()) {
            control.rtsp(
                "SET_PARAMETER",
                "application/x-apple-binary-plist",
                BinaryPlist.encode(dict)
            )
        }
        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            control.rtsp("SET_PARAMETER", "image/jpeg", artworkBytes)
        }
    }

    /** Shows [positionMs] of [durationMs]; `start/now/end` are RTP times of what plays now. */
    fun sendProgress(positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val now =
            (audio.rtpTimestamp - AirPlay2AudioStream.LATENCY_SAMPLES).toLong() and UINT32_MASK
        val start = (now - positionMs * config.sampleRate / MILLIS_PER_SECOND) and UINT32_MASK
        val end = (start + durationMs * config.sampleRate / MILLIS_PER_SECOND) and UINT32_MASK
        val body = "progress: $start/$now/$end\r\n".toByteArray(Charsets.UTF_8)
        control.rtsp("SET_PARAMETER", "text/parameters", body)
    }

    /** Best-effort graceful session end. Call before [close] where possible so the
     *  receiver drops the session immediately instead of waiting out its own timeout. */
    fun teardown() {
        try {
            if (control.sessionUrl.isNotEmpty()) control.rtsp("TEARDOWN", null, null)
        } catch (_: Exception) {}
    }

    fun close() {
        running = false
        heartbeat.interrupt()
        timingResponder.interrupt()
        clock.close()
        audio.close()
        events.close()
        controlSocket.close()
        timingSocket.close()
        control.close()
    }

    /** Pairing and session setup; true once the audio stream is ready. */
    private fun handshake(): Boolean {
        // The reference addresses the session by the receiver's own address and
        // the stream connection id, not by the sender's local address
        // (mirror.go:483).
        control.sessionUrl = "rtsp://${config.host}:${config.port}/${setup.streamConnectionId}"
        audio.randomizeStart()

        control.reopen()
        val paired = receiver.refresh(control) &&
            pairing.establish {
                control.reopen()
                receiver.refresh(control)
            }
        if (paired) {
            // Capability negotiation, mirroring compatibility.go
            // selectTimingProtocol / audio connection layout.
            clock.usePtp = control.encrypted &&
                receiver.hasFeature(FEATURE_PTP) &&
                receiver.supportsPtpSourceVersion()
            useStreamConnections = receiver.hasFeature(FEATURE_STREAM_CONNECTIONS)
            Log.d(
                TAG,
                "Negotiated timing=${if (clock.usePtp) "PTP" else "NTP"} " +
                    "streamConnections=$useStreamConnections"
            )
        }
        return paired && setUpStreams()
    }

    private fun setUpStreams(): Boolean {
        val fairPlayDone = !receiver.hasFeature(FEATURE_FPSAP) ||
            FairPlayHandshake(control, secureRandom).run()
        val recording = fairPlayDone &&
            setup.setupSession(timingSocket.localPort) &&
            setup.record()
        if (recording) events.connect(setup.eventPort)
        val target = if (recording) {
            setup.setupStream(controlSocket.localPort, useStreamConnections)
        } else {
            null
        }
        target?.let(audio::open)
        return target != null
    }

    private fun startStreaming() {
        setVolume(config.initialVolumeDb)
        // Before the first 0xd7 anchor: it ties the next RTP timestamp to "now",
        // so audio must follow it immediately.
        clock.awaitReceiverLock()

        running = true
        if (!clock.usePtp) timingResponder.start()
        heartbeat.start()
        Log.d(TAG, "AirPlay 2 connected successfully")
    }
}
