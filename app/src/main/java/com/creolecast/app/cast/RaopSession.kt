package com.creolecast.app.cast

import android.util.Base64
import android.util.Log
import com.creolecast.app.AudioCastService
import com.creolecast.app.CastDestination
import com.creolecast.app.CastState
import com.creolecast.app.ExtraFields
import com.creolecast.app.raop.RaopCrypto
import com.creolecast.app.raop.RaopTxt
import com.creolecast.app.raop.RtpRetransmitter
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Classic RAOP port, used when the receiver advertises none. */
private const val RAOP_PORT = 5000

/** AirPlay 2 port; receivers advertising it often still speak RAOP on [RAOP_PORT]. */
private const val AIRPLAY_PORT = 7000
private const val CONNECT_TIMEOUT_MS = 5000
private const val CONTROL_READ_TIMEOUT_MS = 10000
private const val USER_AGENT = "AirPlay/550.10"
private const val AES_KEY_BYTES = 16

/** The receiver's UDP ports from the SETUP response's Transport header. */
private data class ServerPorts(val audio: Int, val control: Int, val timing: Int)

/** The session AES key: the cipher for audio, and the SDP lines announcing it. */
private class RaopEncryption(val crypto: RaopCrypto, val sdpLines: String)

/**
 * One RAOP (AirPlay 1) cast to [dest]: RTSP ANNOUNCE/SETUP/RECORD on a TCP control
 * connection, then ALAC audio over RTP/UDP with NTP-style timing and sync on two more UDP
 * ports. The control connection is registered in [CastContext.raop] for volume, metadata
 * and teardown.
 */
internal class RaopSession(
    private val ctx: CastContext,
    private val dest: CastDestination,
    private val myIp: String,
    private val identity: AirPlayIdentity
) {
    // Local UDP ports for audio, control and timing, advertised in SETUP; run() closes them.
    private val audioUdp = DatagramSocket(0)
    private val controlUdp = DatagramSocket(0)
    private val timingUdp = DatagramSocket(0)

    private lateinit var liveness: RaopLiveness
    private lateinit var connection: RaopConnection
    private val sessionGuid = UUID.randomUUID().toString()
    private val rtpSessionId = (10000000..99999999).random()
    private val commonHeaders: Map<String, String> by lazy {
        buildMap {
            put("User-Agent", USER_AGENT)
            put("X-Apple-Session-ID", sessionGuid)
            put("X-Apple-Device-ID", "0x${identity.deviceId.replace(":", "")}")
            put("Client-Instance", identity.dacpId.ifEmpty { "0000000000000000" })
            if (identity.dacpId.isNotEmpty()) put("DACP-ID", identity.dacpId)
            if (identity.activeRemote.isNotEmpty()) put("Active-Remote", identity.activeRemote)
        }
    }

    /** Connects and streams until cancelled or the receiver is lost; see [SessionEnd]. */
    suspend fun run(): SessionEnd = withContext(ctx.ioDispatcher) {
        var streaming = false
        try {
            val (ports, encryption) = handshake()
            streaming = true
            ctx.markCasting()
            ctx.metadata.value?.let { ctx.raop.sendMetadata(dest.host, it) }
            stream(ports, encryption)
        } catch (e: IOException) {
            abort(e, streaming)
        } catch (e: GeneralSecurityException) {
            abort(e, streaming)
        } catch (e: IllegalArgumentException) {
            abort(e, streaming)
        } finally {
            audioUdp.close()
            controlUdp.close()
            timingUdp.close()
        }
    }

    /** Streams audio, with the clock and resend loops, until the receiver is lost. */
    private suspend fun stream(ports: ServerPorts, encryption: RaopEncryption?): SessionEnd =
        coroutineScope {
            val serverAddress = InetAddress.getByName(dest.host)
            val clock = RaopClock(timingUdp, controlUdp, serverAddress, ports.control)
            val retransmitter = RtpRetransmitter(controlUdp)
            val sender = RaopAudioSender(
                audioUdp,
                serverAddress,
                ports.audio,
                rtpSessionId,
                encryption?.crypto,
                retransmitter
            )
            connection.sender = sender
            val jobs = listOf(
                launch { clock.answerTimingRequests() },
                launch { clock.sendSyncPackets() },
                launch { retransmitter.serve { isActive } },
                launch { sender.stream(ctx.audio) }
            )
            try {
                val reason = liveness.watch()
                Log.w(LOG_TAG, "RAOP ${dest.host} lost: $reason")
                ctx.raop.forget(dest.host)
                SessionEnd.LOST
            } finally {
                jobs.forEach { it.cancel() }
                Log.d(LOG_TAG, "RAOP ${dest.host}: ${retransmitter.packetsResent} packets re-sent")
            }
        }

    /** Connects and runs ANNOUNCE, SETUP, RECORD and the initial SET_PARAMETER volume. */
    private fun handshake(): Pair<ServerPorts, RaopEncryption?> {
        val control = openControlChannel()
        val encryption = negotiateEncryption()
        val sdp = sdp(encryption?.sdpLines.orEmpty())

        request(control, "ANNOUNCE", mapOf("Content-Type" to "application/sdp"), sdp.toByteArray())

        val setupResponse = request(
            control,
            "SETUP",
            mapOf(
                "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;" +
                    "control_port=${controlUdp.localPort};timing_port=${timingUdp.localPort}"
            )
        )
        val session = setupResponse.find { it.startsWith("Session:", true) }
            ?.substringAfter(":")?.substringBefore(";")?.trim()
        control.session = session
        val ports = parseServerPorts(setupResponse)
        Log.d(
            LOG_TAG,
            "RAOP SETUP: server audio=${ports.audio} control=${ports.control} " +
                "timing=${ports.timing}"
        )

        request(
            control,
            "RECORD",
            mapOf(
                "Range" to "npt=0-",
                "RTP-Info" to "seq=0;rtptime=${AudioCastService.LATENCY}"
            )
        )

        // Start at the volume the user last chose rather than full volume.
        val volume = "volume: ${String.format(Locale.US, "%.6f", ctx.airPlayVolumeDb)}\r\n"
        request(
            control,
            "SET_PARAMETER",
            mapOf("Content-Type" to "text/parameters"),
            volume.toByteArray()
        )
        return ports to encryption
    }

    // The control socket deliberately outlives this call: RaopRegistry owns it from here on
    // and closes it on failure (forget) or after the TEARDOWN window when the cast stops,
    // so `use` would cut that TEARDOWN off.
    @Suppress("MissingUseCall")
    private fun openControlChannel(): RaopConnection {
        val advertisedPort = if (dest.port > 0) dest.port else RAOP_PORT
        val ports = if (advertisedPort == AIRPLAY_PORT) {
            listOf(AIRPLAY_PORT, RAOP_PORT)
        } else {
            listOf(advertisedPort)
        }
        val socket = connectFirstReachable(ports)
        socket.soTimeout = CONTROL_READ_TIMEOUT_MS
        socket.tcpNoDelay = true
        val connection = RaopConnection(dest.host, socket)
        ctx.raop.register(connection)
        liveness = RaopLiveness(socket) { connection.send("OPTIONS") }
        this.connection = connection
        return connection
    }

    /** Connects to the first of [ports] that accepts, rethrowing the last port's failure. */
    private fun connectFirstReachable(ports: List<Int>): Socket {
        var lastFailure: IOException? = null
        for (port in ports) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(dest.host, port), CONNECT_TIMEOUT_MS)
                return socket
            } catch (e: IOException) {
                lastFailure = e
                if (port != ports.last()) {
                    Log.d(LOG_TAG, "RAOP connect to ${dest.host}:$port failed, trying next port")
                }
            }
        }
        throw checkNotNull(lastFailure)
    }

    /** Generates the session AES key when the receiver requires RSA/AES (see [RaopTxt]). */
    private fun negotiateEncryption(): RaopEncryption? {
        if (!RaopTxt.requiresRsa(RaopTxt.intList(ExtraFields.get(dest.extra, "et")))) return null
        return try {
            val aesKey = ByteArray(AES_KEY_BYTES).also { SecureRandom().nextBytes(it) }
            val aesIv = ByteArray(AES_KEY_BYTES).also { SecureRandom().nextBytes(it) }
            val encryptedKey = RaopCrypto.encryptAesKey(aesKey)
            val crypto = RaopCrypto().apply { initAes(aesKey, aesIv) }
            val keyB64 = Base64.encodeToString(encryptedKey, Base64.NO_WRAP or Base64.NO_PADDING)
            val ivB64 = Base64.encodeToString(aesIv, Base64.NO_WRAP or Base64.NO_PADDING)
            RaopEncryption(crypto, "a=rsaaeskey:$keyB64\r\na=aesiv:$ivB64\r\n")
        } catch (e: GeneralSecurityException) {
            Log.w(
                LOG_TAG,
                "RAOP encryption setup failed for ${dest.host}, " +
                    "falling back to unencrypted: ${e.reason}"
            )
            null
        }
    }

    private fun sdp(cryptoLines: String): String = "v=0\r\n" +
        "o=iTunes $rtpSessionId 0 IN IP4 $myIp\r\n" +
        "s=iTunes\r\n" +
        "c=IN IP4 ${dest.host}\r\n" +
        "t=0 0\r\n" +
        "m=audio 0 RTP/AVP 96\r\n" +
        "a=rtpmap:96 AppleLossless\r\n" +
        "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\n" +
        cryptoLines +
        "a=control:rtp\r\n"

    /** Sends [method] plus the common headers on the next CSeq and reads the response. */
    private fun request(
        control: RaopConnection,
        method: String,
        headers: Map<String, String>,
        body: ByteArray? = null
    ): List<String> {
        control.send(method, commonHeaders + headers, body)
        return RtspWire.readResponse(control.socket.getInputStream())
    }

    private fun parseServerPorts(setupResponse: List<String>): ServerPorts {
        val transport = setupResponse.find { it.startsWith("Transport:", true) }
            ?.substringAfter(":").orEmpty()
        var audio = 0
        var control = 0
        var timing = 0
        transport.split(";").forEach { part ->
            val port = part.substringAfter("=").toIntOrNull() ?: 0
            when {
                part.trim().startsWith("server_port=") -> audio = port
                part.trim().startsWith("control_port=") -> control = port
                part.trim().startsWith("timing_port=") -> timing = port
            }
        }
        return ServerPorts(audio, control, timing)
    }

    /** Failure after connecting: best-effort TEARDOWN, then drop the control connection. */
    private suspend fun abort(e: Exception, streaming: Boolean): SessionEnd {
        Log.e(LOG_TAG, "RAOP failed for ${dest.host}: ${e.reason}")
        withContext(NonCancellable) { ctx.raop.teardown(dest) }
        ctx.raop.forget(dest.host)
        return if (streaming) SessionEnd.LOST else SessionEnd.FAILED
    }

    companion object {
        /**
         * Casts to [dest] over RAOP until cancelled, reconnecting when the receiver is lost;
         * a failure to even start ends in [CastState.ERROR].
         */
        suspend fun cast(ctx: CastContext, dest: CastDestination, identity: AirPlayIdentity) {
            val myIp = localIpv4Address() ?: return
            ctx.state.value = CastState.CONNECTING
            // For audio-only casting RAOP (RTSP) is the most reliable and standard
            // AirPlay transport, so it is used even for AirPlay 2 capable receivers.
            reconnecting(ctx, "RAOP ${dest.name}") {
                try {
                    RaopSession(ctx, dest, myIp, identity).run()
                } catch (e: IOException) {
                    Log.e(LOG_TAG, "AirPlay session failed for ${dest.name}: ${e.reason}")
                    SessionEnd.FAILED
                }
            }
        }
    }
}
