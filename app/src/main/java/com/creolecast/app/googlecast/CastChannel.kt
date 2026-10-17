package com.creolecast.app.googlecast

import android.annotation.SuppressLint
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
internal const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
internal const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
internal const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

/** The platform's own endpoint; apps get their own transport ids. */
internal const val RECEIVER_ID = "receiver-0"

private const val CONNECT_TIMEOUT_MS = 5000
private const val HEARTBEAT_INTERVAL_MS = 5_000L
private const val HEARTBEAT_TIMEOUT_MS = 20_000L
private const val REQUEST_TIMEOUT_MS = 15_000L
private const val LENGTH_PREFIX_BYTES = 4

/**
 * One CastV2 connection: TLS socket, length-prefixed [CastMessage] framing with JSON
 * payloads, request/reply matching by `requestId`, and the `tp.heartbeat` keep-alive
 * (PING every 5 s, PONG to the device's PINGs, give up after 20 s of silence).
 *
 * [onMessage] and [onClosed] run on the reader thread. [onClosed] fires once, when the
 * connection is lost (not after [close]).
 */
internal class CastChannel(
    private val host: String,
    private val port: Int,
    private val useTls: Boolean,
    private val onMessage: (CastMessage, JsonObject) -> Unit,
    private val onClosed: (reason: String) -> Unit
) {
    val senderId = "sender-" + (SecureRandom().nextInt() and Int.MAX_VALUE)

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var socket: Socket
    private lateinit var output: OutputStream
    private val writeLock = Any()
    private val nextRequestId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()

    @Volatile private var closed = false

    @Volatile private var lastReceivedNanos = System.nanoTime()
    private var heartbeat: Thread? = null

    @Suppress("MissingUseCall") // The socket lives until close(); `use` would end it here.
    fun open() {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        raw.tcpNoDelay = true
        socket = if (useTls) wrapTls(raw) else raw
        output = socket.getOutputStream()
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        thread(name = "cast-read-$host", isDaemon = true) { readLoop(input) }
        heartbeat = thread(name = "cast-heartbeat-$host", isDaemon = true) { heartbeatLoop() }
    }

    /**
     * Cast devices present self-signed certificates that rotate every day or two; senders
     * authenticate them through the `tp.deviceauth` namespace against Google's device CA,
     * not through TLS. Only stream URLs and metadata cross this link, so the device isn't
     * authenticated at all here.
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun wrapTls(raw: Socket): Socket {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustAll), SecureRandom())
        val tls = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        tls.startHandshake()
        return tls
    }

    private fun readLoop(input: DataInputStream) {
        try {
            while (!closed) {
                val len = input.readInt()
                if (len <= 0 || len > CastMessage.MAX_MESSAGE_SIZE) {
                    throw IOException("bad frame length $len")
                }
                val frame = ByteArray(len)
                input.readFully(frame)
                lastReceivedNanos = System.nanoTime()
                dispatch(CastMessage.decode(frame))
            }
        } catch (e: IOException) {
            connectionLost(e.message ?: "connection lost")
        }
    }

    private fun dispatch(message: CastMessage) {
        val payload = message.payloadUtf8 ?: return
        val obj = try {
            json.parseToJsonElement(payload) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return
        if (message.namespace == NS_HEARTBEAT && obj.string("type") == "PING") {
            send(message.sourceId, NS_HEARTBEAT, typed("PONG"), from = message.destinationId)
        }
        onMessage(message, obj)
        (obj["requestId"] as? JsonPrimitive)?.intOrNull?.let { pending.remove(it)?.complete(obj) }
    }

    private fun heartbeatLoop() {
        try {
            while (!closed && beat()) Unit
        } catch (_: InterruptedException) {
            // close() stops the heartbeat.
        }
    }

    /** Waits one interval, then pings; false once the connection is found dead. */
    private fun beat(): Boolean {
        Thread.sleep(HEARTBEAT_INTERVAL_MS)
        val silentNanos = System.nanoTime() - lastReceivedNanos
        val failure = if (silentNanos > TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_TIMEOUT_MS)) {
            "heartbeat timeout"
        } else {
            try {
                send(RECEIVER_ID, NS_HEARTBEAT, typed("PING"))
                null
            } catch (e: IOException) {
                e.message ?: "heartbeat send failed"
            }
        }
        failure?.let(::connectionLost)
        return failure == null
    }

    private fun connectionLost(reason: String) {
        if (closed) return
        close()
        onClosed(reason)
    }

    fun send(destination: String, namespace: String, payload: JsonObject, from: String = senderId) {
        val bytes = CastMessage(from, destination, namespace, payload.toString()).encode()
        val prefix = ByteArray(LENGTH_PREFIX_BYTES) { i ->
            (bytes.size ushr (Byte.SIZE_BITS * (LENGTH_PREFIX_BYTES - 1 - i))).toByte()
        }
        synchronized(writeLock) {
            output.write(prefix)
            output.write(bytes)
            output.flush()
        }
    }

    /** Sends [payload] without waiting for the device's reply. */
    fun sendRequest(destination: String, namespace: String, payload: JsonObject) {
        send(destination, namespace, withRequestId(payload, nextRequestId.getAndIncrement()))
    }

    /** Sends [payload] under a fresh `requestId` and waits for the reply carrying it. */
    fun request(destination: String, namespace: String, payload: JsonObject): JsonObject {
        val id = nextRequestId.getAndIncrement()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        send(destination, namespace, withRequestId(payload, id))
        val type = payload.string("type").orEmpty()
        return try {
            future.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw IOException("$type failed: ${e.cause?.message.orEmpty()}", e)
        } catch (e: TimeoutException) {
            throw IOException("$type: no reply from $host", e)
        } finally {
            pending.remove(id)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        heartbeat?.interrupt()
        val error = IOException("connection closed")
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        if (::socket.isInitialized) {
            try {
                socket.close()
            } catch (_: IOException) {
                // Already gone.
            }
        }
    }
}

private fun withRequestId(payload: JsonObject, id: Int) =
    JsonObject(payload + ("requestId" to JsonPrimitive(id)))

/** A payload of just `{"type": type}`. */
internal fun typed(type: String) = buildJsonObject { put("type", type) }

/** The string value under [key], or null if absent or not a string. */
internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
