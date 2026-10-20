package com.creolecast.app.cast

import android.util.Log
import com.creolecast.app.AudioCastService
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Seconds between the NTP epoch (1900) and the Unix epoch (1970). */
private const val NTP_UNIX_EPOCH_OFFSET_SECONDS = 0x83AA7E80L

/** One NTP fraction unit is 2^-32 s. */
private const val NTP_FRACTION_PER_SECOND = 0x100000000L
private const val MILLIS_PER_SECOND = 1000L

/** RAOP streams at 44.1 kHz; RTP timestamps count samples. */
private const val RAOP_SAMPLE_RATE = 44100

/** How long one blocking receive waits before the loop re-checks for cancellation. */
private const val TIMING_RECEIVE_TIMEOUT_MS = 5000
private const val TIMING_BUFFER_BYTES = 256
private const val TIMING_PACKET_BYTES = 32
private const val SYNC_PACKET_BYTES = 20

/** RTP payload type of a timing request (0x52 = 82); the marker bit is masked off. */
private const val PAYLOAD_TYPE_MASK = 0x7F
private const val PT_TIMING_REQUEST = 0x52

/** Offset of the request's send time (T1), echoed back as the reply's reference time. */
private const val REQUEST_SEND_TIME_OFFSET = 24
private const val NTP_TIMESTAMP_HALF_BYTES = 4

/** Re-anchor the receiver's RTP-to-NTP mapping every second, as iTunes does. */
private const val SYNC_INTERVAL_MS = 1000L

/** RTP version 2 header byte; the first sync packet also sets the extension bit. */
private const val RTP_V2 = 0x80
private const val RTP_V2_EXTENSION = 0x90

/** Marker bit with payload type 83 (timing reply) and 84 (sync). */
private const val PT_TIMING_REPLY_MARKED = 0xD3
private const val PT_SYNC_MARKED = 0xD4

/** RAOP timing and sync packets always carry sequence number 7. */
private const val CLOCK_PACKET_SEQUENCE: Short = 7

/**
 * The two clock loops of a RAOP session: answering the receiver's NTP-style timing
 * requests on [timingSocket], and sending periodic sync packets from [controlSocket] to
 * the receiver's control port so its RTP-to-NTP mapping does not drift.
 */
internal class RaopClock(
    private val timingSocket: DatagramSocket,
    private val controlSocket: DatagramSocket,
    private val serverAddress: InetAddress,
    private val serverControlPort: Int
) {
    /** Wall-clock time at which the RTP timestamp equals [AudioCastService.LATENCY]. */
    private val startTimeMs = System.currentTimeMillis()

    /** In classic AirPlay the receiver sends timing requests to the sender, which replies. */
    suspend fun answerTimingRequests() {
        try {
            timingSocket.soTimeout = TIMING_RECEIVE_TIMEOUT_MS
            val buf = ByteArray(TIMING_BUFFER_BYTES)
            Log.d(
                LOG_TAG,
                "RAOP timing thread started, listening on port ${timingSocket.localPort}"
            )
            while (currentCoroutineContext().isActive) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    timingSocket.receive(packet)
                    answer(packet)
                } catch (e: SocketTimeoutException) {
                    Log.d(
                        LOG_TAG,
                        "RAOP timing: no packet received in 5s " +
                            "(port ${timingSocket.localPort}): ${e.reason}"
                    )
                }
            }
        } catch (e: IOException) {
            if (currentCoroutineContext().isActive) {
                Log.d(LOG_TAG, "RAOP timing thread ended: ${e.reason}")
            }
        }
    }

    /**
     * AudioPlaybackCapture delivers audio in bursts, so the audio loop's RTP timestamp can
     * run ahead of wall-clock time; without periodic re-anchoring the drift accumulates
     * until the receiver stops playing.
     */
    suspend fun sendSyncPackets() {
        // The first one goes out at once: receivers anchor the stream on it.
        var isFirstSync = true
        while (currentCoroutineContext().isActive && sendToControlPort(syncPacket(isFirstSync))) {
            isFirstSync = false
            delay(SYNC_INTERVAL_MS)
        }
    }

    private fun answer(packet: DatagramPacket) {
        val data = packet.data
        val type = String.format(Locale.US, "%02X", data[1])
        Log.d(
            LOG_TAG,
            "RAOP timing: received ${packet.length} bytes from " +
                "${packet.address}:${packet.port}, type=0x$type"
        )
        val payloadType = data[1].toInt() and PAYLOAD_TYPE_MASK
        if (packet.length >= TIMING_PACKET_BYTES && payloadType == PT_TIMING_REQUEST) {
            val reply = timingReply(data)
            timingSocket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            Log.d(LOG_TAG, "RAOP timing: sent reply to ${packet.address}:${packet.port}")
        }
    }

    /** Timing reply: reference time T1 from the request, then receive T2 = send T3 = now. */
    private fun timingReply(request: ByteArray): ByteArray {
        val referenceSeconds =
            ByteBuffer.wrap(request, REQUEST_SEND_TIME_OFFSET, NTP_TIMESTAMP_HALF_BYTES).int
        val referenceFraction = ByteBuffer.wrap(
            request,
            REQUEST_SEND_TIME_OFFSET + NTP_TIMESTAMP_HALF_BYTES,
            NTP_TIMESTAMP_HALF_BYTES
        ).int
        val now = NtpTime.at(System.currentTimeMillis())
        return ByteBuffer.allocate(TIMING_PACKET_BYTES).apply {
            put(RTP_V2.toByte())
            put(PT_TIMING_REPLY_MARKED.toByte())
            putShort(CLOCK_PACKET_SEQUENCE)
            putInt(0) // padding
            putInt(referenceSeconds) // reference time (T1 from request)
            putInt(referenceFraction)
            putInt(now.seconds) // receive time (T2)
            putInt(now.fraction)
            putInt(now.seconds) // send time (T3)
            putInt(now.fraction)
        }.array()
    }

    /** Sync packet: the RTP timestamp playing now, and the NTP time it maps to. */
    private fun syncPacket(isFirstSync: Boolean): ByteArray {
        val nowMs = System.currentTimeMillis()
        val now = NtpTime.at(nowMs)
        val elapsedMs = nowMs - startTimeMs
        val rtpNow =
            AudioCastService.LATENCY + (elapsedMs * RAOP_SAMPLE_RATE / MILLIS_PER_SECOND).toInt()
        return ByteBuffer.allocate(SYNC_PACKET_BYTES).apply {
            put((if (isFirstSync) RTP_V2_EXTENSION else RTP_V2).toByte())
            put(PT_SYNC_MARKED.toByte())
            putShort(CLOCK_PACKET_SEQUENCE)
            putInt(rtpNow - AudioCastService.LATENCY)
            putInt(now.seconds)
            putInt(now.fraction)
            putInt(rtpNow)
        }.array()
    }

    /** False once the control port is unreachable, which ends the sync loop. */
    private fun sendToControlPort(packet: ByteArray): Boolean = try {
        controlSocket.send(DatagramPacket(packet, packet.size, serverAddress, serverControlPort))
        true
    } catch (e: IOException) {
        Log.d(LOG_TAG, "RAOP sync stopped: ${e.reason}")
        false
    } catch (e: IllegalArgumentException) {
        Log.d(LOG_TAG, "RAOP sync stopped, bad control port $serverControlPort: ${e.reason}")
        false
    }
}

/** An NTP timestamp as the two 32-bit halves RAOP puts on the wire. */
private class NtpTime(val seconds: Int, val fraction: Int) {
    companion object {
        fun at(unixMillis: Long): NtpTime {
            val seconds = unixMillis / MILLIS_PER_SECOND + NTP_UNIX_EPOCH_OFFSET_SECONDS
            val fraction =
                unixMillis % MILLIS_PER_SECOND * NTP_FRACTION_PER_SECOND / MILLIS_PER_SECOND
            return NtpTime(seconds.toInt(), fraction.toInt())
        }
    }
}
