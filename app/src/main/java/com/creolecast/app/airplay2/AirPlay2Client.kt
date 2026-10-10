package com.creolecast.app.airplay2

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import kotlin.concurrent.thread

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
class AirPlay2Client(
    private val host: String,
    private val port: Int,
    private val deviceId: String,
    private val dacpId: String,
    private val activeRemote: String,
    private val sampleRate: Int = 44100,
    private val frameSize: Int = 352,
    private val password: String? = null,
    private val txtPk: ByteArray? = null,
    private val credentialStore: AirPlay2CredentialStore = InMemoryCredentialStore(),
    private val clientName: String = "CreoleCast"
) {
    interface EventListener {
        fun onVolumeChange(db: Double) {}
        fun onRemoteCommand(command: String) {}
    }

    companion object {
        private const val TAG = "AirPlay2Client"

        /** X-Apple-HKP values (pairing.go pairingType* constants). */
        private const val HKP_LEGACY = 3
        private const val HKP_TRANSIENT = 4
        private const val HKP_SCREEN_CAPTURE = 5

        /** Fixed SRP password for HomeKit transient pair-setup. */
        private const val TRANSIENT_PIN = "3939"

        private const val USER_AGENT = "AirPlay/935.7.1"
        private const val SOURCE_VERSION_NTP = "280.33"
        private const val SOURCE_VERSION_PTP = "980.71.1"
        private const val HAP_FRAME_SIZE = 1024

        /** Receiver feature bits (discovery.go). */
        private const val FEATURE_FPSAP = 14
        private const val FEATURE_PTP = 41
        private const val FEATURE_STREAM_CONNECTIONS = 59

        /** 85 ms at 44100 Hz, truncated — latency.go samplesFor44k1. */
        private const val LATENCY_SAMPLES = 3748
    }

    var eventListener: EventListener? = null

    private val secureRandom = SecureRandom()
    private var socket = Socket()
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private var sessionUrl: String = ""
    private val sessionUuid = UUID.randomUUID()
    private val streamConnectionId = secureRandom.nextLong() and 0x7FFFFFFFFFFFFFFFL
    private var cseq = 0

    private val timingSocket = DatagramSocket(0)
    private val controlSocket = DatagramSocket(0)
    private var audioSocket: DatagramSocket? = null
    private var eventSocket: Socket? = null
    private var eventPort = 0

    private var controlRemotePort = 0
    private var audioRemotePort = 0

    private var sequence = 0
    private var rtpTimestamp = 0
    private var syncPacketSent = false
    private val ssrc = secureRandom.nextInt().toUInt().toLong()

    /** Receiver identity from /info; the key our pairing credentials are stored under. */
    private var receiverDeviceId: String = host
    private var lastStatusFlags = 0L
    private var deviceEd25519PubKey: ByteArray? = null
    private var credentials: AirPlay2Credentials? = null
    private var sessionId: String? = null
    private var pairType = HKP_TRANSIENT

    /** Audio stream key published as `shk`; null means the audio goes out in the clear. */
    private var audioKey: ByteArray? = null
    private var audioNonceCounter = 0L

    /** Negotiated receiver capabilities. */
    private var receiverFeatures = 0L
    private var receiverSourceVersion = ""
    private var usePtp = false
    private var useStreamConnections = false

    /** PTP timeline our 0xd7 sync packets are stamped on. */
    private val mediaClock = MediaClock()
    /** Running only when the receiver follows our clock instead of publishing one. */
    private var ptpMaster: PtpMaster? = null


    /** HAP control-channel encryption, enabled once pair-verify completes. */
    private var channelEncrypted = false
    private var hapWriteKey: ByteArray? = null
    private var hapReadKey: ByteArray? = null
    private var hapWriteNonce = 0L
    private var hapReadNonce = 0L

    @Volatile private var running = false
    private var syncThread: Thread? = null
    private var keepAliveThread: Thread? = null
    private var timingThread: Thread? = null

    class HttpResponse(val code: Int, val headers: Map<String, String>, val body: ByteArray?)

    fun connect(): Boolean {
        try {
            // The reference addresses the session by the receiver's own address and
            // the stream connection id, not by the sender's local address
            // (mirror.go:483).
            sessionUrl = "rtsp://$host:$port/$streamConnectionId"
            sequence = secureRandom.nextInt(0xFFFF)
            rtpTimestamp = secureRandom.nextInt()

            if (txtPk != null) {
                deviceEd25519PubKey = txtPk
            }

            openControlConnection()
            if (!readReceiverInfo()) return false
            if (!establishPairing()) return false

            // Capability negotiation, mirroring compatibility.go
            // selectTimingProtocol / audio connection layout.
            usePtp = channelEncrypted &&
                hasFeature(FEATURE_PTP) &&
                supportsPtpSourceVersion(receiverSourceVersion)
            useStreamConnections = hasFeature(FEATURE_STREAM_CONNECTIONS)
            Log.d(TAG, "Negotiated timing=${if (usePtp) "PTP" else "NTP"} streamConnections=$useStreamConnections")

            if (hasFeature(FEATURE_FPSAP) && !doFairPlaySetup()) return false

            if (!sendSetupSession()) return false
            if (!sendRecord()) return false
            connectEventPort()
            if (!sendSetupStream()) return false

            setVolume(0.0)
            // Before the first 0xd7 anchor: it ties the next RTP timestamp to "now",
            // so audio must follow it immediately.
            ptpMaster?.awaitReceiverLock()

            running = true
            if (!usePtp) startTimingResponder()
            startSyncLoop()
            startKeepAliveLoop()
            Log.d(TAG, "AirPlay 2 connected successfully")
            return true
        } catch (e: NeedsPinException) {
            close()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            close()
            return false
        }
    }

    // ---------------------------------------------------------------- pairing

    /**
     * Reuse the stored long-term identity when we have one, exactly as
     * airplay2.go `setupAirPlay2` does: pair-verify with the saved keys, and
     * only fall back to a fresh pair-setup when that verification fails.
     */
    private fun establishPairing(): Boolean {
        val saved = credentialStore.load(receiverDeviceId)
        if (saved != null) {
            credentials = saved
            pairType = if (password != null) HKP_SCREEN_CAPTURE else HKP_TRANSIENT
            if (doPairVerify()) return true
            Log.w(TAG, "pair-verify with saved credentials failed, re-pairing")
            credentialStore.clear(receiverDeviceId)
            // A rejected pair-verify usually leaves the receiver's connection
            // unusable, so start a fresh one before re-pairing (airplay2.go
            // setupAirPlay2 reconnects on this path).
            openControlConnection()
            if (!readReceiverInfo()) return false
        }

        val fresh = AirPlay2Credentials.generate()
        credentials = fresh

        if (!doPairSetup(password)) {
            if (password == null) {
                // Ask the receiver to show its PIN before the UI prompts for it,
                // otherwise an Apple TV never displays one (pairing.go
                // StartPINDisplay).
                startPinDisplay()
                throw NeedsPinException(host)
            }
            return false
        }

        // Transient pair-setup ends at M4 with the channel already keyed from the
        // SRP session key; pair-verify only follows a persistent PIN pairing.
        if (pairType == HKP_TRANSIENT) return true

        if (!doPairVerify()) {
            Log.e(TAG, "pair-verify failed after pair-setup")
            return false
        }

        credentialStore.save(receiverDeviceId, fresh)
        return true
    }

    /** Ask the receiver to display a pairing PIN. HTTP 453 means "accepted". */
    private fun startPinDisplay() {
        try {
            pairType = HKP_SCREEN_CAPTURE
            val resp = sendRequest(
                "POST", "/pair-pin-start", null, null,
                listOf(
                    "X-Apple-HKP" to pairType.toString(),
                    "X-Apple-SupportedPINLengths" to "4"
                )
            )
            Log.d(TAG, "pair-pin-start: ${resp.code}")
        } catch (e: Exception) {
            Log.w(TAG, "pair-pin-start failed: ${e.message}")
        }
    }

    /**
     * Open (or replace) the RTSP control connection. Replacing it resets the
     * HAP framing state, since the keys belong to the old session.
     */
    private fun openControlConnection() {
        try { socket.close() } catch (_: Exception) {}
        channelEncrypted = false
        hapWriteKey = null
        hapReadKey = null
        hapWriteNonce = 0
        hapReadNonce = 0
        cseq = 0
        sessionId = null
        socket = Socket()
        socket.connect(InetSocketAddress(host, port), 5000)
        socket.tcpNoDelay = true
        socket.soTimeout = 10000
        output = socket.getOutputStream()
        input = HapInputStream(socket.getInputStream())
    }

    /** GET /info and cache everything later steps negotiate against. */
    private fun readReceiverInfo(): Boolean {
        val info = getInfo() ?: return false
        lastStatusFlags = info["statusFlags"] as? Long ?: 0L
        (info["deviceID"] as? String)?.let { if (it.isNotEmpty()) receiverDeviceId = it }
        if (deviceEd25519PubKey == null) {
            deviceEd25519PubKey = when (val pkRaw = info["pk"]) {
                is ByteArray -> pkRaw
                is String -> try {
                    android.util.Base64.decode(pkRaw, android.util.Base64.NO_WRAP)
                } catch (e: IllegalArgumentException) {
                    null
                }
                else -> null
            }
        }
        receiverFeatures = parseFeatures(info["features"])
        receiverSourceVersion = info["sourceVersion"] as? String ?: ""
        Log.d(TAG, "Receiver $receiverDeviceId flags=$lastStatusFlags " +
            "features=0x${receiverFeatures.toString(16)} src=$receiverSourceVersion " +
            "hasPk=${deviceEd25519PubKey != null}")
        return true
    }

    /**
     * Transient pairing first (no PIN), PIN pairing when we have one. Transient
     * pair-setup ends after M4: the SRP session key keys the control channel and
     * no long-term identity is exchanged (HAP; shairport-sync rtsp.c
     * handle_pair_setup). PIN pairing runs the full M1..M6 exchange, because M5
     * is what registers our long-term key that pair-verify later signs with.
     */
    private fun doPairSetup(pin: String?): Boolean {
        val attempts = if (pin != null) {
            listOf(HKP_SCREEN_CAPTURE to pin, HKP_LEGACY to pin)
        } else {
            // HomeKit transient pairing still runs SRP, with the fixed password
            // "3939" (shairport-sync, pyatv and owntone all use it); an empty
            // password makes the receiver's M4 proof fail to verify.
            listOf(HKP_TRANSIENT to TRANSIENT_PIN)
        }

        for ((type, srpPin) in attempts) {
            pairType = type
            val transient = type == HKP_TRANSIENT
            Log.d(TAG, "Pair-setup attempt: hkp=$type transient=$transient")

            val srp = SRP6aClient(srpPin, "Pair-Setup", secureRandom)
            val resp1 = sendPairingRequest("/pair-setup", srp.buildM1(transient))
            if (resp1.code != 200) {
                Log.e(TAG, "pair-setup M1 failed: ${resp1.code}")
                continue
            }
            val m2 = resp1.body ?: continue
            val m2Error = TlvUtil.parse(m2)[TlvUtil.TLV_ERROR]?.firstOrNull()
            if (m2Error != null) {
                Log.e(TAG, "pair-setup M2 error: ${m2Error[0].toInt()}")
                continue
            }

            val m3 = srp.processM2(m2) ?: continue
            val resp2 = sendPairingRequest("/pair-setup", m3)
            if (resp2.code != 200) { Log.e(TAG, "pair-setup M3 failed: ${resp2.code}"); continue }
            if (!srp.verifyM4(resp2.body ?: continue)) {
                Log.e(TAG, "pair-setup M4 verification failed"); continue
            }

            if (transient) {
                enableControlEncryption(srp.sharedKeyBytes ?: continue)
                Log.d(TAG, "Pair-setup complete (transient), control channel encrypted")
                return true
            }

            val creds = credentials ?: return false
            val m5 = srp.buildM5(creds, includeScreenCaptureAcl = type == HKP_SCREEN_CAPTURE) ?: continue
            val resp3 = sendPairingRequest("/pair-setup", m5)
            if (resp3.code != 200) { Log.e(TAG, "pair-setup M5 failed: ${resp3.code}"); continue }
            if (!srp.verifyM6(resp3.body ?: continue)) {
                Log.e(TAG, "pair-setup M6 reported an error"); continue
            }

            Log.d(TAG, "Pair-setup complete (hkp=$type)")
            return true
        }
        Log.e(TAG, "Pair-setup: all attempts failed")
        return false
    }

    /**
     * HAP pair-verify (pairing.go `hapPairVerify`). M3 is signed with the
     * persisted long-term key — the same one pair-setup M5 registered.
     */
    private fun doPairVerify(): Boolean {
        val creds = credentials ?: return false

        val keyPair = AirPlay2Crypto.generateCurve25519KeyPair()
        val clientPub = AirPlay2Crypto.getPublicKeyBytes(keyPair)

        val m1 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(1),
            TlvUtil.TLV_PUBLIC_KEY to clientPub
        )
        val resp1 = sendPairingRequest("/pair-verify", m1, verify = true)
        if (resp1.code != 200) { Log.e(TAG, "pair-verify M1 failed: ${resp1.code}"); return false }

        val parsed1 = TlvUtil.parse(resp1.body ?: return false)
        if (parsed1[TlvUtil.TLV_ERROR]?.firstOrNull() != null) {
            Log.e(TAG, "pair-verify M2 error"); return false
        }
        val serverPubKey = parsed1[TlvUtil.TLV_PUBLIC_KEY]?.firstOrNull() ?: return false
        val encryptedData = parsed1[TlvUtil.TLV_ENCRYPTED_DATA]?.firstOrNull() ?: return false
        if (encryptedData.size < 16) return false

        val shared = AirPlay2Crypto.curve25519Agree(
            keyPair.private as org.bouncycastle.crypto.params.X25519PrivateKeyParameters, serverPubKey
        )

        val verifyKey = AirPlay2Crypto.hkdfSha512(
            "Pair-Verify-Encrypt-Salt".toByteArray(Charsets.UTF_8),
            shared,
            "Pair-Verify-Encrypt-Info".toByteArray(Charsets.UTF_8),
            32
        )

        val m2Nonce = ByteArray(12)
        System.arraycopy("PV-Msg02".toByteArray(Charsets.UTF_8), 0, m2Nonce, 4, 8)
        val ciphertext = encryptedData.copyOfRange(0, encryptedData.size - 16)
        val tag = encryptedData.copyOfRange(encryptedData.size - 16, encryptedData.size)

        val decrypted = try {
            AirPlay2Crypto.chacha20Poly1305Decrypt(verifyKey, m2Nonce, ciphertext, ByteArray(0), tag)
        } catch (e: Exception) {
            // A tag mismatch means the peer did not derive the same shared
            // secret, so the exchange is already unauthenticated — fail hard.
            Log.e(TAG, "pair-verify M2 decrypt failed", e)
            return false
        }

        // Authenticate the receiver when /info or the mDNS TXT record gave us
        // its long-term key. The reference skips this because it keeps no
        // accessory key; we have one, so we enforce it.
        val serverLtpk = deviceEd25519PubKey
        if (serverLtpk != null && serverLtpk.size == 32) {
            val inner = TlvUtil.parse(decrypted)
            val serverId = inner[TlvUtil.TLV_IDENTIFIER]?.firstOrNull()
            val serverSig = inner[TlvUtil.TLV_SIGNATURE]?.firstOrNull()
            if (serverId == null || serverSig == null) {
                Log.e(TAG, "pair-verify M2 missing identifier/signature"); return false
            }
            val signed = serverPubKey + serverId + clientPub
            if (!AirPlay2Crypto.ed25519Verify(serverLtpk, signed, serverSig)) {
                Log.e(TAG, "pair-verify M2 server signature rejected")
                return false
            }
        }

        val identifier = creds.pairingId.toByteArray(Charsets.UTF_8)
        val m3SignData = clientPub + identifier + serverPubKey
        val m3Sig = AirPlay2Crypto.ed25519SignWithSeed(creds.ed25519Seed, m3SignData)

        val m3Inner = TlvUtil.build(
            TlvUtil.TLV_IDENTIFIER to identifier,
            TlvUtil.TLV_SIGNATURE to m3Sig
        )
        val m3Nonce = ByteArray(12)
        System.arraycopy("PV-Msg03".toByteArray(Charsets.UTF_8), 0, m3Nonce, 4, 8)
        val (m3Ct, m3Tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(verifyKey, m3Nonce, m3Inner, ByteArray(0))

        val m3 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(3),
            TlvUtil.TLV_ENCRYPTED_DATA to (m3Ct + m3Tag)
        )
        val resp3 = sendPairingRequest("/pair-verify", m3, verify = true)
        if (resp3.code != 200) { Log.e(TAG, "pair-verify M3 failed: ${resp3.code}"); return false }
        resp3.body?.let { body ->
            if (body.isNotEmpty() && TlvUtil.parse(body)[TlvUtil.TLV_ERROR]?.firstOrNull() != null) {
                Log.e(TAG, "pair-verify M4 error"); return false
            }
        }

        enableControlEncryption(shared)
        Log.d(TAG, "Pair-verify complete, control channel encrypted")
        return true
    }

    /** From here the whole RTSP channel is HAP framed (pairing.go:766-791). */
    private fun enableControlEncryption(sharedSecret: ByteArray) {
        val controlSalt = "Control-Salt".toByteArray(Charsets.UTF_8)
        hapWriteKey = AirPlay2Crypto.hkdfSha512(
            controlSalt, sharedSecret, "Control-Write-Encryption-Key".toByteArray(Charsets.UTF_8), 32
        )
        hapReadKey = AirPlay2Crypto.hkdfSha512(
            controlSalt, sharedSecret, "Control-Read-Encryption-Key".toByteArray(Charsets.UTF_8), 32
        )
        hapWriteNonce = 0
        hapReadNonce = 0
        channelEncrypted = true
    }

    // ----------------------------------------------------------- capabilities

    private fun parseFeatures(value: Any?): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is String -> parseFeatureString(value)
        else -> 0L
    }

    /** mDNS-style feature strings are either "0x1234" or "0xLOW,0xHIGH". */
    private fun parseFeatureString(text: String): Long {
        val parts = text.split(",")
        fun hex(s: String): Long =
            s.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: 0L
        return when (parts.size) {
            0 -> 0L
            1 -> hex(parts[0])
            else -> (hex(parts[1]) shl 32) or (hex(parts[0]) and 0xFFFFFFFFL)
        }
    }

    private fun hasFeature(bit: Int): Boolean = (receiverFeatures shr bit) and 1L == 1L

    /**
     * PTP needs SourceVersion >= 354.54.6, and 377.40.x is a known-bad
     * advertisement (compatibility.go supportsPTPSourceVersion).
     */
    private fun supportsPtpSourceVersion(version: String): Boolean {
        if (version.isEmpty()) return false
        val parts = version.split(".").map { it.toIntOrNull() ?: return false }
        if (parts.size >= 2 && parts[0] == 377 && parts[1] == 40) return false
        val minimum = listOf(354, 54, 6)
        for (i in minimum.indices) {
            val actual = parts.getOrElse(i) { 0 }
            if (actual > minimum[i]) return true
            if (actual < minimum[i]) return false
        }
        return true
    }

    /**
     * FairPlay SAP. Receivers advertising bit 14 reject SETUP with RTSP 455
     * until /fp-setup completes (fairplay.go FairPlaySetup).
     */
    private fun doFairPlaySetup(): Boolean {
        return try {
            val session = FairPlaySapSession(secureRandom)
            val header = listOf("X-Apple-ET" to "32")
            val r1 = sendRequest("POST", "/fp-setup", "application/octet-stream", session.message1(), header)
            if (r1.code == 404) {
                Log.w(TAG, "/fp-setup not implemented despite the FPSAP feature bit; continuing")
                return true
            }
            if (r1.code != 200) { Log.e(TAG, "fp-setup m1 failed: ${r1.code}"); return false }
            val m3 = session.exchangeM3(r1.body ?: return false)
            val r2 = sendRequest("POST", "/fp-setup", "application/octet-stream", m3, header)
            if (r2.code != 200) { Log.e(TAG, "fp-setup m3 failed: ${r2.code}"); return false }
            session.finish(r2.body ?: return false)

            Log.d(TAG, "FairPlay SAP handshake complete")
            true
        } catch (e: Exception) {
            Log.e(TAG, "FairPlay SAP failed", e)
            false
        }
    }

    // ---------------------------------------------------------------- session

    private fun getInfo(): Map<String, Any?>? {
        val resp = sendRequest("GET", "/info", null, null)
        if (resp.code != 200) return null
        val body = resp.body ?: return null
        return try {
            BinaryPlist.decode(body)
        } catch (e: Exception) {
            Log.e(TAG, "Binary plist parse failed: ${e.message}")
            parseXmlPlist(body.toString(Charsets.UTF_8))
        }
    }

    private fun parseXmlPlist(xml: String): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        val statusRegex = Regex("<key>statusFlags</key>\\s*<integer>(\\d+)</integer>", RegexOption.DOT_MATCHES_ALL)
        val pkRegex = Regex("<key>pk</key>\\s*<data>\\s*([A-Za-z0-9+/=]+)\\s*</data>", RegexOption.DOT_MATCHES_ALL)
        val deviceRegex = Regex("<key>deviceID</key>\\s*<string>([^<]*)</string>", RegexOption.DOT_MATCHES_ALL)
        val nameRegex = Regex("<key>name</key>\\s*<string>([^<]*)</string>", RegexOption.DOT_MATCHES_ALL)
        statusRegex.find(xml)?.let { result["statusFlags"] = it.groupValues[1].toLong() }
        pkRegex.find(xml)?.let { result["pk"] = it.groupValues[1].replace(Regex("\\s+"), "") }
        deviceRegex.find(xml)?.let { result["deviceID"] = it.groupValues[1] }
        nameRegex.find(xml)?.let { result["name"] = it.groupValues[1] }
        return result
    }

    private fun sendSetupSession(): Boolean {
        val localAddress = socket.localAddress?.hostAddress ?: "0.0.0.0"
        val plist = BinaryPlist.makeSessionPlist(
            sessionUuid = sessionUuid,
            deviceId = deviceId,
            name = clientName,
            model = "Linux",
            sourceVersion = if (usePtp) SOURCE_VERSION_PTP else SOURCE_VERSION_NTP,
            timingProtocol = if (usePtp) "PTP" else "NTP",
            timingPort = timingSocket.localPort,
            timingPeerId = deviceId,
            timingPeerAddress = localAddress
        )
        val resp = sendRtspRequest("SETUP", sessionUrl, "application/x-apple-binary-plist", plist)
        val receivedAt = System.nanoTime()
        if (resp.code != 200) { Log.e(TAG, "SETUP session failed: ${resp.code}"); return false }
        sessionId = resp.headers["Session"]?.substringBefore(";")?.trim()
            ?: sessionUuid.toString().uppercase()
        val clockId = resp.body?.let { parseSessionResponse(it) } ?: 0L
        return !usePtp || configurePtpClock(clockId, resp.headers, receivedAt)
    }

    /**
     * Receivers that publish a ClockID (Apple) own the timeline: follow it via
     * their clock headers (mirror.go configurePTPClock). Receivers that don't
     * (shairport-sync + nqptp) follow the sender, so become the PTP master.
     */
    private fun configurePtpClock(clockId: Long, headers: Map<String, String>, receivedAt: Long): Boolean {
        if (clockId != 0L) {
            if (!mediaClock.configureFromSetup(clockId, headers, receivedAt)) {
                Log.d(TAG, "SETUP lacks receiver clock headers; local clock on timeline 0x${clockId.toULong().toString(16)}")
                mediaClock.configureFromLocalClock(clockId)
            }
            return true
        }
        val master = PtpMaster(InetAddress.getByName(host), PtpMaster.clockIdFromDeviceId(deviceId))
        try {
            master.start()
        } catch (e: Exception) {
            Log.e(TAG, "Receiver needs a sender PTP clock but UDP 319/320 cannot be bound: ${e.message}")
            return false
        }
        ptpMaster = master
        mediaClock.configureFromLocalClock(master.clockId)
        return true
    }

    private fun sendRecord(): Boolean {
        val resp = sendRtspRequest(
            "RECORD", sessionUrl, null, null,
            extraHeaders = listOf("Range" to "npt=0-", "RTP-Info" to "seq=0;rtptime=0")
        )
        Log.d(TAG, "RECORD response: code=${resp.code}")
        return resp.code == 200 || resp.code == 201
    }

    private fun sendSetupStream(): Boolean {
        // The reference publishes a fresh random ChaCha key as `shk` for
        // encrypted sessions (mirror.go generateAudioChaChaKey); it is not
        // derived from the pairing secret. Unencrypted sessions send no key and
        // stream in the clear.
        val shk = if (channelEncrypted) ByteArray(32).also(secureRandom::nextBytes) else null

        val plist = BinaryPlist.makeStreamPlist(
            controlPort = controlSocket.localPort,
            shk = shk,
            streamConnectionId = streamConnectionId,
            sampleRate = sampleRate,
            spf = frameSize,
            latencyMin = 0L,
            latencyMax = LATENCY_SAMPLES.toLong(),
            useStreamConnections = useStreamConnections
        )
        val resp = sendRtspRequest("SETUP", sessionUrl, "application/x-apple-binary-plist", plist)
        if (ptpMaster == null) mediaClock.reanchor(resp.headers, System.nanoTime())
        if (resp.code != 200) { Log.e(TAG, "SETUP stream failed: ${resp.code}"); return false }

        resp.body?.let { body ->
            try {
                val dict = BinaryPlist.decode(body)
                val streams = dict["streams"]
                if (streams is List<*> && streams.isNotEmpty()) {
                    (streams[0] as? Map<*, *>)?.let { stream ->
                        (stream["dataPort"] as? Long)?.toInt()?.let { if (it > 0) audioRemotePort = it }
                        (stream["controlPort"] as? Long)?.toInt()?.let { if (it > 0) controlRemotePort = it }
                        // The streamConnections layout answers with both ports
                        // nested instead of dataPort/controlPort.
                        val connections = stream["streamConnections"] as? Map<*, *>
                        connectionPort(connections, "streamConnectionTypeRTP")?.let { audioRemotePort = it }
                        connectionPort(connections, "streamConnectionTypeRTCP")?.let { controlRemotePort = it }
                    }
                }
                (dict["eventPort"] as? Long)?.toInt()?.let { if (it > 0) eventPort = it }
            } catch (e: Exception) {
                Log.w(TAG, "SETUP stream plist parse failed: ${e.message}")
            }
        }

        if (audioRemotePort <= 0) {
            Log.e(TAG, "SETUP stream returned no dataPort")
            return false
        }

        audioSocket = DatagramSocket(0)
        audioKey = shk
        audioNonceCounter = 0
        Log.d(TAG, "Audio stream: data=$audioRemotePort ctrl=$controlRemotePort encrypted=${shk != null}")
        return true
    }

    private fun connectionPort(connections: Map<*, *>?, key: String): Int? {
        val port = (connections?.get(key) as? Map<*, *>)?.get("streamConnectionKeyPort") as? Long
        return port?.toInt()?.takeIf { it > 0 }
    }

    /** Returns the receiver's PTP ClockID, or 0 when it publishes none. */
    private fun parseSessionResponse(body: ByteArray): Long {
        return try {
            val dict = BinaryPlist.decode(body)
            (dict["eventPort"] as? Long)?.toInt()?.let { if (it > 0) eventPort = it }
            (dict["timingPeerInfo"] as? Map<*, *>)?.get("ClockID") as? Long ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "parseSessionResponse failed: ${e.message}")
            0L
        }
    }

    // ------------------------------------------------------------------ audio

    /**
     * ALAC "verbatim" (uncompressed) frame, transcribed from audio.go
     * `encodeALACVerbatim`: a 23-bit element header with hasSize set, the
     * 32-bit sample count, each little-endian stereo sample byte-swapped to
     * big-endian, then the 3-bit end tag.
     */
    private fun alacEncodeUncompressed(pcm: ByteArray): ByteArray {
        val samples = pcm.size / (2 * 2)
        val totalBits = 23 + 32 + pcm.size * 8 + 3
        val out = ByteArray((totalBits + 7) / 8)
        var p = 0
        var bpos = 0

        fun writeBits(v: Int, blen: Int) {
            val lb = 8 - bpos
            val rb = lb - blen
            if (rb >= 0) {
                val bd = (v shl rb) and 0xFF
                out[p] = if (bpos == 0) bd.toByte() else (out[p].toInt() or bd).toByte()
                if (rb == 0) { p++; bpos = 0 } else bpos += blen
            } else {
                out[p] = (out[p].toInt() or ((v ushr (-rb)) and 0xFF)).toByte()
                p++
                out[p] = ((v shl (8 + rb)) and 0xFF).toByte()
                bpos = -rb
            }
        }

        // writeBits carries at most one byte boundary, so nothing wider than 8
        // bits may be written in a single call.
        writeBits(1, 3)          // tag: channel pair element (stereo)
        writeBits(0, 4)          // elementInstanceTag
        writeBits(0, 8)          // unused (12 bits, part 1)
        writeBits(0, 4)          // unused (12 bits, part 2)
        writeBits(1, 1)          // hasSize
        writeBits(0, 2)          // extraBytes (16-bit, no shift)
        writeBits(1, 1)          // verbatim
        writeBits((samples ushr 24) and 0xFF, 8)
        writeBits((samples ushr 16) and 0xFF, 8)
        writeBits((samples ushr 8) and 0xFF, 8)
        writeBits(samples and 0xFF, 8)

        var i = 0
        while (i < pcm.size) {
            writeBits(pcm[i + 1].toInt() and 0xFF, 8)  // L high byte
            writeBits(pcm[i + 0].toInt() and 0xFF, 8)  // L low byte
            writeBits(pcm[i + 3].toInt() and 0xFF, 8)  // R high byte
            writeBits(pcm[i + 2].toInt() and 0xFF, 8)  // R low byte
            i += 4
        }
        writeBits(7, 3)
        return out
    }

    fun sendAudioFrame(pcm: ByteArray): Boolean {
        try {
            val payload = alacEncodeUncompressed(pcm)
            val packet = buildRtpPacket(payload)
            val socket = audioSocket ?: return false

            val key = audioKey
            val datagram = if (key != null) {
                // ChaCha20-Poly1305 with an 8-byte LE counter nonce, AAD = the
                // RTP timestamp+SSRC bytes, nonce appended after the tag
                // (audio.go sendAudioPacketWithSeqAndNonce).
                val counter = synchronized(this) { audioNonceCounter++ }
                val nonce = ByteArray(12)
                for (i in 0..7) nonce[4 + i] = ((counter shr (i * 8)) and 0xFF).toByte()
                val aad = packet.copyOfRange(4, 12)
                val (encrypted, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(
                    key, nonce, packet.copyOfRange(12, packet.size), aad
                )
                packet.copyOfRange(0, 12) + encrypted + tag + nonce.copyOfRange(4, 12)
            } else {
                packet
            }

            socket.send(DatagramPacket(datagram, datagram.size, InetAddress.getByName(host), audioRemotePort))

            sequence = (sequence + 1) and 0xFFFF
            rtpTimestamp += frameSize
            return true
        } catch (e: Exception) {
            Log.e(TAG, "sendAudioFrame failed", e)
            return false
        }
    }

    private fun buildRtpPacket(payload: ByteArray): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = 0x60.toByte()
        header[2] = ((sequence shr 8) and 0xFF).toByte()
        header[3] = (sequence and 0xFF).toByte()
        writeUInt32(header, 4, rtpTimestamp)
        writeUInt32(header, 8, ssrc.toInt())
        return header + payload
    }

    /**
     * TimeAnnounce on the control port: 20 bytes / payload type 0xd4 for NTP,
     * 28 bytes / 0xd7 plus the timeline id for PTP (audio.go sendSyncPacketAt).
     * The frame at `rtpTimestamp - latency` is anchored to the network time.
     */
    private fun sendSyncPacket() {
        if (controlRemotePort <= 0) return
        val networkTime = if (usePtp) mediaClock.now() ?: return else currentNtpTime()
        val packet = ByteArray(if (usePtp) 28 else 20)
        packet[0] = (if (syncPacketSent) 0x80 else 0x90).toByte()
        packet[1] = (if (usePtp) 0xD7 else 0xD4).toByte()
        packet[2] = 0
        packet[3] = 4
        syncPacketSent = true
        writeUInt32(packet, 4, rtpTimestamp - LATENCY_SAMPLES)
        if (usePtp) {
            writeUInt64(packet, 8, ptpNanoseconds(networkTime))
            writeUInt32(packet, 16, rtpTimestamp)
            writeUInt64(packet, 20, mediaClock.timelineId)
        } else {
            writeUInt64(packet, 8, networkTime)
            writeUInt32(packet, 16, rtpTimestamp)
        }
        try {
            controlSocket.send(
                DatagramPacket(packet, packet.size, InetAddress.getByName(host), controlRemotePort)
            )
        } catch (_: Exception) {}
    }

    /** seconds.32 fixed point -> nanoseconds (audio.go ptpNanoseconds). */
    private fun ptpNanoseconds(timestamp: Long): Long {
        val seconds = timestamp ushr 32
        val fraction = timestamp and 0xFFFFFFFFL
        return seconds * 1_000_000_000L + (fraction * 1_000_000_000L ushr 32)
    }

    /**
     * Answer the receiver's NTP timing requests (0xd2) with 0xd3 responses.
     * Without this the receiver never establishes a media clock and silently
     * renders nothing (mirror.go NTP timing responder).
     */
    private fun startTimingResponder() {
        timingThread = thread(name = "ap2-timing", isDaemon = true) {
            val buf = ByteArray(64)
            while (running) {
                try {
                    val request = DatagramPacket(buf, buf.size)
                    timingSocket.receive(request)
                    if (request.length < 32 || (buf[1].toInt() and 0xFF) != 0xD2) continue
                    val receiveTime = currentNtpTime()
                    val response = ByteArray(32)
                    response[0] = 0x80.toByte()
                    response[1] = 0xD3.toByte()
                    response[2] = buf[2]
                    response[3] = buf[3]
                    System.arraycopy(buf, 24, response, 8, 8)     // their transmit -> our reference
                    writeUInt64(response, 16, receiveTime)
                    writeUInt64(response, 24, currentNtpTime())
                    timingSocket.send(
                        DatagramPacket(response, response.size, request.address, request.port)
                    )
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "timing responder: ${e.message}")
                }
            }
        }
    }

    fun setVolume(db: Double) {
        val body = "volume: ${"%.6f".format(java.util.Locale.US, db)}\r\n"
        sendRtspRequest("SET_PARAMETER", sessionUrl, "text/parameters", body.toByteArray(Charsets.UTF_8))
    }

    fun sendMetadata(title: String?, artist: String?, album: String?, artworkBytes: ByteArray? = null) {
        val dict = linkedMapOf<String, Any?>()
        if (!title.isNullOrEmpty()) dict["dmap.itemname"] = title
        if (!artist.isNullOrEmpty()) dict["daap.songartist"] = artist
        if (!album.isNullOrEmpty()) dict["daap.songalbum"] = album
        if (dict.isNotEmpty()) {
            sendRtspRequest("SET_PARAMETER", sessionUrl,
                "application/x-apple-binary-plist", BinaryPlist.encode(dict))
        }
        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            sendRtspRequest("SET_PARAMETER", sessionUrl, "image/jpeg", artworkBytes)
        }
    }

    fun sendProgress(positionMs: Long, durationMs: Long) {
        val start = 0L
        val current = (positionMs * sampleRate / 1000L) + rtpTimestamp
        val end = (durationMs * sampleRate / 1000L)
        val body = "progress: $start/$current/$end\r\n".toByteArray(Charsets.UTF_8)
        sendRtspRequest("SET_PARAMETER", sessionUrl, "text/parameters", body)
    }

    // ------------------------------------------------------------ event channel

    private fun connectEventPort() {
        if (eventPort <= 0) return
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, eventPort), 5000)
            eventSocket = s
            startEventReadLoop(s)
            Log.d(TAG, "Event port $eventPort connected")
        } catch (e: Exception) {
            Log.w(TAG, "Event port connect failed: ${e.message}")
        }
    }

    private fun startEventReadLoop(s: Socket) {
        thread(name = "ap2-event", isDaemon = true) {
            try {
                val stream = s.getInputStream()
                while (running) {
                    val msg = readEventMessage(stream) ?: break
                    dispatchEventMessage(msg)
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Event loop ended: ${e.message}")
            }
        }
    }

    private data class EventMessage(val method: String, val headers: Map<String, String>, val body: ByteArray?)

    private fun readEventMessage(input: InputStream): EventMessage? {
        val headerLines = mutableListOf<String>()
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buf.append(b.toChar())
            if (b == '\n'.code) {
                val line = buf.toString().trimEnd('\r', '\n')
                buf.clear()
                if (line.isEmpty()) break
                headerLines.add(line)
            }
        }
        if (headerLines.isEmpty()) return null
        val method = headerLines[0].substringBefore(' ')
        val headers = linkedMapOf<String, String>()
        var contentLength = 0
        for (i in 1 until headerLines.size) {
            val parts = headerLines[i].split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                headers[key] = value
                if (key.equals("Content-Length", ignoreCase = true)) {
                    contentLength = value.toIntOrNull() ?: 0
                }
            }
        }
        var body: ByteArray? = null
        if (contentLength > 0) {
            body = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val read = input.read(body, offset, contentLength - offset)
                if (read < 0) break
                offset += read
            }
        }
        return EventMessage(method, headers, body)
    }

    private fun dispatchEventMessage(msg: EventMessage) {
        val body = msg.body ?: return
        if (msg.headers["Content-Type"]?.contains("apple-binary-plist") != true) return
        try {
            val dict = BinaryPlist.decode(body)
            when (dict["type"]) {
                "volume" -> {
                    val db = when (val v = dict["value"]) {
                        is Double -> v
                        is Long -> v.toDouble()
                        else -> return
                    }
                    eventListener?.onVolumeChange(db)
                }
                "command" -> {
                    val name = dict["name"] as? String ?: return
                    eventListener?.onRemoteCommand(name)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Event dispatch failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- lifecycle

    /** Best-effort graceful session end. Call before [close] where possible so the
     *  receiver drops the session immediately instead of waiting out its own timeout. */
    fun teardown() {
        try {
            if (sessionUrl.isNotEmpty()) sendRtspRequest("TEARDOWN", sessionUrl, null, null)
        } catch (_: Exception) {}
    }

    fun close() {
        running = false
        syncThread?.interrupt()
        keepAliveThread?.interrupt()
        timingThread?.interrupt()
        ptpMaster?.close()
        ptpMaster = null
        audioSocket?.close()
        try { eventSocket?.close() } catch (_: Exception) {}
        controlSocket.close()
        timingSocket.close()
        try { socket.close() } catch (_: Exception) {}
    }

    private fun startSyncLoop() {
        syncThread = thread(name = "ap2-sync", isDaemon = true) {
            while (running) {
                sendSyncPacket()
                try { Thread.sleep(1000) } catch (e: InterruptedException) { return@thread }
            }
        }
    }

    /**
     * POST /feedback every 2 s, matching the reference's feedbackLoop. Each reply
     * re-anchors a receiver-owned PTP timeline (mirror.go feedbackLoop).
     */
    private fun startKeepAliveLoop() {
        keepAliveThread = thread(name = "ap2-keepalive", isDaemon = true) {
            while (running) {
                try {
                    val resp = sendRequest("POST", "/feedback", null, null)
                    if (usePtp && ptpMaster == null) mediaClock.reanchor(resp.headers, System.nanoTime())
                } catch (_: Exception) {}
                try { Thread.sleep(2000) } catch (e: InterruptedException) { return@thread }
            }
        }
    }

    // ------------------------------------------------------------------- wire

    private fun sendPairingRequest(path: String, body: ByteArray, verify: Boolean = false): HttpResponse {
        val headers = mutableListOf<Pair<String, String>>()
        if (pairType == HKP_LEGACY) {
            headers += "X-Apple-HKP" to HKP_LEGACY.toString()
        } else {
            headers += "X-Apple-Client-Name" to clientName
            headers += "X-Apple-HKP" to pairType.toString()
            credentials?.let { headers += "X-Apple-Client-ID" to it.pairingId }
            if (verify) headers += "X-Apple-PD" to "1"
        }
        return sendRequest("POST", path, "application/octet-stream", body, headers)
    }

    private fun sendRtspRequest(
        method: String,
        url: String,
        contentType: String?,
        body: ByteArray?,
        extraHeaders: List<Pair<String, String>> = emptyList()
    ): HttpResponse {
        val headers = mutableListOf(
            "Client-Instance" to deviceId.replace(":", ""),
            "DACP-ID" to dacpId,
            "Active-Remote" to activeRemote
        )
        sessionId?.let { headers += "Session" to it }
        headers += extraHeaders
        return sendRequest(method, url, contentType, body, headers)
    }

    private fun sendRequest(
        method: String,
        target: String,
        contentType: String?,
        body: ByteArray?,
        extraHeaders: List<Pair<String, String>> = emptyList()
    ): HttpResponse {
        synchronized(this) {
            val out = output ?: return HttpResponse(0, emptyMap(), null)
            val payload = body ?: ByteArray(0)
            val sb = StringBuilder()
            sb.append("$method $target RTSP/1.0\r\n")
            sb.append("CSeq: ${++cseq}\r\n")
            sb.append("User-Agent: $USER_AGENT\r\n")
            for ((k, v) in extraHeaders) sb.append("$k: $v\r\n")
            if (contentType != null && payload.isNotEmpty()) sb.append("Content-Type: $contentType\r\n")
            sb.append("Content-Length: ${payload.size}\r\n")
            sb.append("\r\n")

            val frame = sb.toString().toByteArray(Charsets.UTF_8) + payload
            writeFramed(out, frame)
            out.flush()
            return readResponse()
        }
    }

    /**
     * HAP framing: 1024-byte plaintext chunks, each `[len LE16][ciphertext][tag]`
     * with the length prefix as AAD and an incrementing LE counter nonce
     * (client.go:1100-1137).
     */
    private fun writeFramed(out: OutputStream, data: ByteArray) {
        if (!channelEncrypted) { out.write(data); return }
        val key = hapWriteKey ?: return
        var offset = 0
        while (offset < data.size) {
            val n = minOf(HAP_FRAME_SIZE, data.size - offset)
            val lengthPrefix = byteArrayOf((n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte())
            val nonce = ByteArray(12)
            for (i in 0..7) nonce[4 + i] = ((hapWriteNonce shr (i * 8)) and 0xFF).toByte()
            val (ct, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(
                key, nonce, data.copyOfRange(offset, offset + n), lengthPrefix
            )
            out.write(lengthPrefix)
            out.write(ct)
            out.write(tag)
            hapWriteNonce++
            offset += n
        }
    }

    /** Transparently decrypts HAP frames once the channel is encrypted. */
    private inner class HapInputStream(private val raw: InputStream) : InputStream() {
        private var plain: ByteArray = ByteArray(0)
        private var pos = 0

        override fun read(): Int {
            if (!channelEncrypted) return raw.read()
            if (pos >= plain.size && !fill()) return -1
            return plain[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!channelEncrypted) return raw.read(b, off, len)
            if (len == 0) return 0
            if (pos >= plain.size && !fill()) return -1
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        private fun readFully(buf: ByteArray): Boolean {
            var offset = 0
            while (offset < buf.size) {
                val n = raw.read(buf, offset, buf.size - offset)
                if (n < 0) return false
                offset += n
            }
            return true
        }

        private fun fill(): Boolean {
            val key = hapReadKey ?: return false
            val lengthPrefix = ByteArray(2)
            if (!readFully(lengthPrefix)) return false
            val length = (lengthPrefix[0].toInt() and 0xFF) or ((lengthPrefix[1].toInt() and 0xFF) shl 8)
            val sealed = ByteArray(length + 16)
            if (!readFully(sealed)) return false
            val nonce = ByteArray(12)
            for (i in 0..7) nonce[4 + i] = ((hapReadNonce shr (i * 8)) and 0xFF).toByte()
            plain = AirPlay2Crypto.chacha20Poly1305Decrypt(
                key, nonce,
                sealed.copyOfRange(0, length),
                lengthPrefix,
                sealed.copyOfRange(length, sealed.size)
            )
            pos = 0
            hapReadNonce++
            return plain.isNotEmpty()
        }
    }

    private fun readResponse(): HttpResponse {
        val input = input ?: return HttpResponse(0, emptyMap(), null)
        val headers = linkedMapOf<String, String>()
        var statusCode = 0
        val headerLines = mutableListOf<String>()
        val buf = StringBuilder()

        while (true) {
            val b = input.read()
            if (b < 0) break
            buf.append(b.toChar())
            if (b == '\n'.code) {
                val line = buf.toString().trimEnd('\r', '\n')
                buf.clear()
                if (line.isEmpty()) break
                headerLines.add(line)
            }
        }

        if (headerLines.isEmpty()) return HttpResponse(0, emptyMap(), null)

        Regex("\\w+/\\d\\.\\d (\\d+)").find(headerLines.first())?.let {
            statusCode = it.groupValues[1].toIntOrNull() ?: 0
        }

        var contentLength = 0
        for (i in 1 until headerLines.size) {
            val parts = headerLines[i].split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                headers[key] = value
                if (key.equals("Content-Length", ignoreCase = true)) {
                    contentLength = value.toIntOrNull() ?: 0
                }
            }
        }

        var body: ByteArray? = null
        if (contentLength > 0) {
            body = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val read = input.read(body, offset, contentLength - offset)
                if (read < 0) break
                offset += read
            }
            if (offset != contentLength) body = null
        }

        return HttpResponse(statusCode, headers, body)
    }

    private fun currentNtpTime(): Long {
        val ms = System.currentTimeMillis()
        val seconds = ms / 1000 + 2208988800L
        val fraction = ((ms % 1000) * 0x100000000L) / 1000
        return (seconds shl 32) or (fraction and 0xFFFFFFFFL)
    }

    private fun writeUInt32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v shr 24) and 0xFF).toByte()
        buf[off + 1] = ((v shr 16) and 0xFF).toByte()
        buf[off + 2] = ((v shr 8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
    }

    private fun writeUInt64(buf: ByteArray, off: Int, v: Long) {
        for (i in 0..7) buf[off + i] = ((v shr ((7 - i) * 8)) and 0xFF).toByte()
    }

    operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val result = ByteArray(this.size + other.size)
        System.arraycopy(this, 0, result, 0, this.size)
        System.arraycopy(other, 0, result, this.size, other.size)
        return result
    }
}
