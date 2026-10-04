package com.creolecast.app.airplay2

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * HAP pair-verify ([AirPlay2Client] `doPairVerify`) against a scripted receiver on a
 * loopback socket. The receiver speaks the real M1..M4 exchange with
 * [AirPlay2Crypto], so these tests pin which M2 messages the sender accepts as the
 * genuine receiver and which keys it encrypts the control channel with afterwards.
 */
class AirPlay2ClientPairVerifyTest {

    private val receiverSeed = AirPlay2Crypto.generateEd25519Seed()
    private val receiverLtpk = AirPlay2Crypto.ed25519PublicFromSeed(receiverSeed)
    private val receiverId = "AA:BB:CC:DD:EE:FF".toByteArray(Charsets.UTF_8)
    private val credentials = AirPlay2Credentials.generate()

    private var receiver: FakeReceiver? = null
    private var client: AirPlay2Client? = null

    @After
    fun tearDown() {
        client?.close()
        receiver?.finish()
    }

    // ------------------------------------------------------------- acceptance

    @Test
    fun `M2 signed with the receiver's long-term key is accepted and M3 proves our persisted identity`() {
        val r = start(m2 = { it.m2(it.signedInner(receiverSeed, receiverId)) })

        assertTrue(pairVerify(r, receiverLtpk))

        val inner = r.m3Inner!!
        val identifier = inner.getValue(TlvUtil.TLV_IDENTIFIER).single()
        val signature = inner.getValue(TlvUtil.TLV_SIGNATURE).single()
        val session = r.session!!
        assertArrayEquals(credentials.pairingId.toByteArray(Charsets.UTF_8), identifier)
        assertTrue(
            "M3 must sign clientPub || pairingId || serverPub with the persisted seed",
            AirPlay2Crypto.ed25519Verify(
                credentials.ed25519Public,
                session.clientPub + identifier + session.serverPub,
                signature
            )
        )
        assertTrue(field("channelEncrypted") as Boolean)
    }

    @Test
    fun `after pair-verify the client writes with Control-Write and reads with Control-Read`() {
        val r = start(
            m2 = { it.m2(it.signedInner(receiverSeed, receiverId)) },
            serveEncryptedRequest = true
        )
        assertTrue(pairVerify(r, receiverLtpk))

        val response = sendEncryptedRequest("GET", "/info")

        assertEquals("GET /info RTSP/1.0", r.encryptedRequestLine)
        assertEquals("client must decrypt frames sealed with Control-Read-Encryption-Key", 200, response.code)
    }

    // --------------------------------------------- receiver authentication

    @Test
    fun `M2 signed by a key other than the receiver's long-term key is rejected before M3`() {
        val impostorSeed = AirPlay2Crypto.generateEd25519Seed()
        val r = start(m2 = { it.m2(it.signedInner(impostorSeed, receiverId)) })

        assertFalse(pairVerify(r, receiverLtpk))
        assertEquals("our identity must not be presented to an impostor", 1, r.pairVerifyRequests)
        assertFalse(field("channelEncrypted") as Boolean)
    }

    @Test
    fun `M2 without a signature is rejected when the long-term key is known`() {
        val r = start(m2 = { it.m2(TlvUtil.build(TlvUtil.TLV_IDENTIFIER to receiverId)) })

        assertFalse(pairVerify(r, receiverLtpk))
        assertEquals(1, r.pairVerifyRequests)
    }

    @Test
    fun `M2 without an identifier is rejected when the long-term key is known`() {
        val r = start(m2 = { s ->
            val signature = AirPlay2Crypto.ed25519SignWithSeed(receiverSeed, s.serverPub + receiverId + s.clientPub)
            s.m2(TlvUtil.build(TlvUtil.TLV_SIGNATURE to signature))
        })

        assertFalse(pairVerify(r, receiverLtpk))
        assertEquals(1, r.pairVerifyRequests)
    }

    /**
     * Current policy, pinned: authentication only runs with a 32-byte long-term key.
     * Without one, any peer that completes the X25519 exchange is accepted.
     */
    @Test
    fun `without a long-term key the receiver is not authenticated`() {
        val r = start(m2 = { it.m2(TlvUtil.build(TlvUtil.TLV_STATE to byteArrayOf(2))) })

        assertTrue(pairVerify(r, pk = null))
        assertEquals(2, r.pairVerifyRequests)
        assertTrue(field("channelEncrypted") as Boolean)
    }

    /** Same policy for a key that is present but not 32 bytes (e.g. a malformed TXT `pk`). */
    @Test
    fun `a long-term key that is not 32 bytes disables receiver authentication`() {
        val impostorSeed = AirPlay2Crypto.generateEd25519Seed()
        val r = start(m2 = { it.m2(it.signedInner(impostorSeed, receiverId)) })

        assertTrue(pairVerify(r, pk = receiverLtpk.copyOf(31)))
        assertEquals(2, r.pairVerifyRequests)
    }

    // ------------------------------------------------------- malformed M2

    @Test
    fun `M2 carrying TLV_ERROR fails pair-verify`() {
        val r = start(m2 = { s ->
            TlvUtil.build(
                TlvUtil.TLV_STATE to byteArrayOf(2),
                TlvUtil.TLV_ERROR to byteArrayOf(2),
                TlvUtil.TLV_PUBLIC_KEY to s.serverPub,
                TlvUtil.TLV_ENCRYPTED_DATA to s.seal(s.signedInner(receiverSeed, receiverId))
            )
        })

        assertFalse(pairVerify(r, receiverLtpk))
        assertEquals(1, r.pairVerifyRequests)
    }

    @Test
    fun `M2 without the receiver's ephemeral public key fails pair-verify`() {
        val r = start(m2 = { s ->
            TlvUtil.build(
                TlvUtil.TLV_STATE to byteArrayOf(2),
                TlvUtil.TLV_ENCRYPTED_DATA to s.seal(s.signedInner(receiverSeed, receiverId))
            )
        })

        assertFalse(pairVerify(r, receiverLtpk))
        assertEquals(1, r.pairVerifyRequests)
    }

    @Test
    fun `M2 encrypted data shorter than a Poly1305 tag fails pair-verify`() {
        val r = start(m2 = { s ->
            TlvUtil.build(
                TlvUtil.TLV_STATE to byteArrayOf(2),
                TlvUtil.TLV_PUBLIC_KEY to s.serverPub,
                TlvUtil.TLV_ENCRYPTED_DATA to ByteArray(15)
            )
        })

        assertFalse(pairVerify(r, receiverLtpk))
        assertEquals(1, r.pairVerifyRequests)
    }

    /** Pinned with no long-term key, so the tag check is the only thing standing in the way. */
    @Test
    fun `M2 with a Poly1305 tag mismatch fails even without a long-term key`() {
        val r = start(m2 = { s ->
            val sealed = s.seal(s.signedInner(receiverSeed, receiverId))
            sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()
            TlvUtil.build(
                TlvUtil.TLV_STATE to byteArrayOf(2),
                TlvUtil.TLV_PUBLIC_KEY to s.serverPub,
                TlvUtil.TLV_ENCRYPTED_DATA to sealed
            )
        })

        assertFalse(pairVerify(r, pk = null))
        assertEquals(1, r.pairVerifyRequests)
        assertFalse(field("channelEncrypted") as Boolean)
    }

    // ------------------------------------------------------------------- M4

    @Test
    fun `M4 carrying TLV_ERROR fails pair-verify and leaves the channel unencrypted`() {
        val r = start(
            m2 = { it.m2(it.signedInner(receiverSeed, receiverId)) },
            m4 = TlvUtil.build(TlvUtil.TLV_STATE to byteArrayOf(4), TlvUtil.TLV_ERROR to byteArrayOf(2))
        )

        assertFalse(pairVerify(r, receiverLtpk))
        assertEquals(2, r.pairVerifyRequests)
        assertFalse(field("channelEncrypted") as Boolean)
        assertNull(field("hapWriteKey"))
        assertNull(field("hapReadKey"))
    }

    // -------------------------------------------------------------- harness

    private fun start(
        m2: (Session) -> ByteArray,
        m4: ByteArray = TlvUtil.build(TlvUtil.TLV_STATE to byteArrayOf(4)),
        serveEncryptedRequest: Boolean = false
    ): FakeReceiver = FakeReceiver(m2, m4, serveEncryptedRequest).also { receiver = it }

    /** Drives the private pair-verify step exactly as `connect` → `establishPairing` would. */
    private fun pairVerify(r: FakeReceiver, pk: ByteArray?): Boolean {
        val c = AirPlay2Client(
            host = LOOPBACK.hostAddress!!,
            port = r.port,
            deviceId = "02:00:00:00:00:01",
            dacpId = "0000000000000001",
            activeRemote = "1"
        )
        client = c
        setField("credentials", credentials)
        setField("deviceEd25519PubKey", pk)
        invoke("openControlConnection")
        return invoke("doPairVerify") as Boolean
    }

    private fun sendEncryptedRequest(method: String, target: String): AirPlay2Client.HttpResponse =
        invoke(
            "sendRequest",
            arrayOf(String::class.java, String::class.java, String::class.java, ByteArray::class.java, List::class.java),
            method, target, null, null, emptyList<Pair<String, String>>()
        ) as AirPlay2Client.HttpResponse

    private fun field(name: String): Any? =
        AirPlay2Client::class.java.getDeclaredField(name).apply { isAccessible = true }.get(client)

    private fun setField(name: String, value: Any?) {
        AirPlay2Client::class.java.getDeclaredField(name).apply { isAccessible = true }.set(client, value)
    }

    private fun invoke(name: String, types: Array<Class<*>> = emptyArray(), vararg args: Any?): Any? {
        val method = AirPlay2Client::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
        return try {
            method.invoke(client, *args)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    /** Receiver side of one pair-verify: its ephemeral X25519 key and the derived verify key. */
    class Session(val clientPub: ByteArray) {
        private val keyPair = AirPlay2Crypto.generateCurve25519KeyPair()
        val serverPub: ByteArray = AirPlay2Crypto.getPublicKeyBytes(keyPair)
        val shared: ByteArray =
            AirPlay2Crypto.curve25519Agree(keyPair.private as X25519PrivateKeyParameters, clientPub)
        val verifyKey: ByteArray = hkdf("Pair-Verify-Encrypt-Salt", shared, "Pair-Verify-Encrypt-Info")

        fun signedInner(seed: ByteArray, id: ByteArray): ByteArray = TlvUtil.build(
            TlvUtil.TLV_IDENTIFIER to id,
            TlvUtil.TLV_SIGNATURE to AirPlay2Crypto.ed25519SignWithSeed(seed, serverPub + id + clientPub)
        )

        fun seal(inner: ByteArray): ByteArray {
            val (ct, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(verifyKey, labelNonce("PV-Msg02"), inner, ByteArray(0))
            return ct + tag
        }

        fun m2(inner: ByteArray): ByteArray = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(2),
            TlvUtil.TLV_PUBLIC_KEY to serverPub,
            TlvUtil.TLV_ENCRYPTED_DATA to seal(inner)
        )
    }

    private class Request(val line: String, val body: ByteArray)

    /** One-connection scripted receiver: M1→M2, M3→M4, then optionally one HAP-framed request. */
    class FakeReceiver(
        private val m2: (Session) -> ByteArray,
        private val m4: ByteArray,
        private val serveEncryptedRequest: Boolean
    ) {
        private val server = ServerSocket(0, 1, LOOPBACK)
        val port: Int get() = server.localPort

        @Volatile var pairVerifyRequests = 0
        @Volatile var session: Session? = null
        @Volatile var m3Inner: Map<Int, List<ByteArray>>? = null
        @Volatile var encryptedRequestLine: String? = null
        @Volatile private var failure: Throwable? = null

        private val worker = thread(name = "fake-airplay-receiver", isDaemon = true) {
            try {
                serve()
            } catch (t: Throwable) {
                failure = t
            }
        }

        /** Waits for the script to end (the client closes its socket) and surfaces receiver-side errors. */
        fun finish() {
            worker.join(5_000)
            server.close()
            failure?.let { throw AssertionError("fake receiver failed", it) }
        }

        private fun serve() {
            server.soTimeout = 10_000
            server.accept().use { socket ->
                socket.soTimeout = 10_000
                val input = DataInputStream(socket.getInputStream())
                val out = socket.getOutputStream()

                val m1 = readRequest(input) ?: return
                check(m1.line.startsWith("POST /pair-verify ")) { "unexpected M1 request ${m1.line}" }
                pairVerifyRequests++
                val s = Session(TlvUtil.parse(m1.body).getValue(TlvUtil.TLV_PUBLIC_KEY).single())
                session = s
                respond(out, m2(s))

                // The client stops here when it rejects M2; its socket close ends the read.
                val m3 = readRequest(input) ?: return
                check(m3.line.startsWith("POST /pair-verify ")) { "unexpected M3 request ${m3.line}" }
                pairVerifyRequests++
                val sealed = TlvUtil.parse(m3.body).getValue(TlvUtil.TLV_ENCRYPTED_DATA).single()
                m3Inner = TlvUtil.parse(
                    AirPlay2Crypto.chacha20Poly1305Decrypt(
                        s.verifyKey, labelNonce("PV-Msg03"),
                        sealed.copyOfRange(0, sealed.size - 16), ByteArray(0),
                        sealed.copyOfRange(sealed.size - 16, sealed.size)
                    )
                )
                respond(out, m4)

                if (serveEncryptedRequest) serveEncrypted(s, input, out)
            }
        }

        /** The receiver reads what the client wrote with Control-Write, and answers with Control-Read. */
        private fun serveEncrypted(s: Session, input: DataInputStream, out: OutputStream) {
            val readKey = hkdf("Control-Salt", s.shared, "Control-Write-Encryption-Key")
            val writeKey = hkdf("Control-Salt", s.shared, "Control-Read-Encryption-Key")

            val prefix = ByteArray(2).also { input.readFully(it) }
            val length = (prefix[0].toInt() and 0xFF) or ((prefix[1].toInt() and 0xFF) shl 8)
            val frame = ByteArray(length + 16).also { input.readFully(it) }
            val plain = AirPlay2Crypto.chacha20Poly1305Decrypt(
                readKey, counterNonce(0), frame.copyOfRange(0, length), prefix,
                frame.copyOfRange(length, frame.size)
            )
            encryptedRequestLine = String(plain, Charsets.UTF_8).substringBefore("\r\n")

            val response = "RTSP/1.0 200 OK\r\nCSeq: 3\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.UTF_8)
            val responsePrefix = byteArrayOf(response.size.toByte(), (response.size shr 8).toByte())
            val (ct, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(writeKey, counterNonce(0), response, responsePrefix)
            out.write(responsePrefix + ct + tag)
            out.flush()
        }

        private fun readRequest(input: InputStream): Request? {
            val lines = mutableListOf<String>()
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) {
                    val text = line.toString(Charsets.UTF_8.name()).trimEnd('\r')
                    line.reset()
                    if (text.isEmpty()) break
                    lines += text
                } else {
                    line.write(b)
                }
            }
            val contentLength = lines.drop(1)
                .map { it.split(":", limit = 2) }
                .firstOrNull { it[0].trim().equals("Content-Length", ignoreCase = true) }
                ?.get(1)?.trim()?.toInt() ?: 0
            val body = ByteArray(contentLength)
            DataInputStream(input).readFully(body)
            return Request(lines.first(), body)
        }

        private fun respond(out: OutputStream, body: ByteArray) {
            val head = "RTSP/1.0 200 OK\r\nCSeq: 1\r\n" +
                "Content-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8) + body)
            out.flush()
        }
    }

    companion object {
        private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

        private fun hkdf(salt: String, ikm: ByteArray, info: String): ByteArray =
            AirPlay2Crypto.hkdfSha512(salt.toByteArray(Charsets.UTF_8), ikm, info.toByteArray(Charsets.UTF_8), 32)

        private fun labelNonce(label: String): ByteArray =
            ByteArray(12).also { System.arraycopy(label.toByteArray(Charsets.UTF_8), 0, it, 4, 8) }

        private fun counterNonce(counter: Long): ByteArray =
            ByteArray(12).also { for (i in 0..7) it[4 + i] = ((counter shr (i * 8)) and 0xFF).toByte() }
    }
}
