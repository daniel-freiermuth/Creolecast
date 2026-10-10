package com.creolecast.app.airplay2

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.concurrent.thread

/** NTP-format (32.32 fixed point, epoch 1900) wall-clock timestamps. */
internal object NtpTime {
    private const val UNIX_TO_NTP_EPOCH_S = 2208988800L
    private const val MILLIS_PER_SECOND = 1000L
    private const val FRACTION_MASK = 0xFFFFFFFFL

    fun now(): Long {
        val ms = System.currentTimeMillis()
        val seconds = ms / MILLIS_PER_SECOND + UNIX_TO_NTP_EPOCH_S
        val fraction = ((ms % MILLIS_PER_SECOND) * 0x100000000L) / MILLIS_PER_SECOND
        return (seconds shl Int.SIZE_BITS) or (fraction and FRACTION_MASK)
    }
}

/**
 * Answer the receiver's NTP timing requests (0xd2) with 0xd3 responses.
 * Without this the receiver never establishes a media clock and silently
 * renders nothing (mirror.go NTP timing responder).
 */
internal class NtpTimingResponder(
    private val socket: DatagramSocket,
    private val isRunning: () -> Boolean
) {
    private companion object {
        const val TAG = "AirPlay2Client"
        const val RECEIVE_BUFFER_SIZE = 64
        const val PACKET_SIZE = 32
        const val RTP_VERSION_2 = 0x80
        const val TIMING_REQUEST = 0xD2
        const val TIMING_RESPONSE = 0xD3
        const val BYTE_MASK = 0xFF

        /** Bytes 2-3 carry the request's sequence number, echoed in the reply. */
        const val SEQUENCE_OFFSET = 2

        /** Offsets of the three 64-bit NTP timestamps in a timing packet. */
        const val REFERENCE_TIME_OFFSET = 8
        const val RECEIVE_TIME_OFFSET = 16
        const val TRANSMIT_TIME_OFFSET = 24
        const val NTP_TIMESTAMP_SIZE = 8
    }

    private var worker: Thread? = null

    fun start() {
        worker = thread(name = "ap2-timing", isDaemon = true) {
            val buf = ByteArray(RECEIVE_BUFFER_SIZE)
            while (isRunning()) {
                try {
                    val request = DatagramPacket(buf, buf.size)
                    socket.receive(request)
                    if (isTimingRequest(request)) respond(request)
                } catch (e: IOException) {
                    if (isRunning()) Log.w(TAG, "timing responder failed", e)
                }
            }
        }
    }

    fun interrupt() {
        worker?.interrupt()
    }

    private fun isTimingRequest(request: DatagramPacket): Boolean =
        request.length >= PACKET_SIZE && (request.data[1].toInt() and BYTE_MASK) == TIMING_REQUEST

    private fun respond(request: DatagramPacket) {
        val buf = request.data
        val receiveTime = NtpTime.now()
        val response = ByteArray(PACKET_SIZE)
        response[0] = RTP_VERSION_2.toByte()
        response[1] = TIMING_RESPONSE.toByte()
        response[SEQUENCE_OFFSET] = buf[SEQUENCE_OFFSET]
        response[SEQUENCE_OFFSET + 1] = buf[SEQUENCE_OFFSET + 1]
        // their transmit -> our reference
        System.arraycopy(
            buf,
            TRANSMIT_TIME_OFFSET,
            response,
            REFERENCE_TIME_OFFSET,
            NTP_TIMESTAMP_SIZE
        )
        WireBytes.putUInt64(response, RECEIVE_TIME_OFFSET, receiveTime)
        WireBytes.putUInt64(response, TRANSMIT_TIME_OFFSET, NtpTime.now())
        socket.send(DatagramPacket(response, response.size, request.address, request.port))
    }
}
