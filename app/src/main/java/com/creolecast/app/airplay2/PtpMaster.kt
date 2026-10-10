package com.creolecast.app.airplay2

import android.util.Log
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/**
 * Minimal unicast IEEE 1588 / 802.1AS grandmaster for receivers that follow the
 * sender's clock instead of publishing their own (shairport-sync + nqptp: its
 * PTP SETUP reply carries no `timingPeerInfo.ClockID`).
 *
 * Sends Announce every 250 ms and two-step Sync + Follow_Up every 125 ms to the
 * receiver. Timestamps come from [nanoTime], the same source [MediaClock] uses
 * when anchored with [MediaClock.configureFromLocalClock], so the timeline in our
 * 0xd7 sync packets is exactly the one the receiver locks to.
 *
 * nqptp wipes its clock table when shairport-sync names us as timing peer
 * ("T <ip>", sent during SETUP) and ignores Sync/Follow_Up until it has seen an
 * Announce, so the first Announce is easily lost in that race; the short
 * interval bounds the cost. shairport-sync then requires 400 ms of mastership
 * before it trusts the clock and drops audio due earlier — see [awaitReceiverLock].
 *
 * nqptp only accepts PTP traffic whose source port equals its destination port
 * (319 -> 319, 320 -> 320), so both well-known ports must be bound locally.
 * Unprivileged Android apps may bind them from Android 13 with an updated
 * connectivity module (issuetracker 218578943); [start] throws otherwise.
 */
class PtpMaster(
    private val peer: InetAddress,
    val clockId: Long,
    private val nanoTime: () -> Long = System::nanoTime
) : Closeable {

    companion object {
        private const val TAG = "PtpMaster"
        const val EVENT_PORT = 319
        const val GENERAL_PORT = 320

        private const val MSG_SYNC = 0x0
        private const val MSG_FOLLOW_UP = 0x8
        private const val MSG_ANNOUNCE = 0xB
        private const val TRANSPORT_SPECIFIC = 0x10   // 802.1AS
        private const val HEADER_SIZE = 34
        private const val SYNC_INTERVAL_MS = 125L
        private const val SYNCS_PER_ANNOUNCE = 2

        /**
         * Time from [start] until a receiver can play against our clock: a lost
         * first Announce (250 ms), the first Follow_Up after it (125 ms) and
         * shairport-sync's 400 ms mastership minimum (rtp.c
         * get_ptp_anchor_local_time_info), plus margin.
         */
        private const val RECEIVER_LOCK_MS = 900L

        /** EUI-64 clock identity from an `AA:BB:CC:DD:EE:FF` device id (IEEE 1588 7.5.2.2.2). */
        fun clockIdFromDeviceId(deviceId: String): Long {
            val mac = deviceId.split(":").map { it.toInt(16) }
            require(mac.size == 6) { "device id must be a MAC address: $deviceId" }
            val eui = intArrayOf(mac[0], mac[1], mac[2], 0xFF, 0xFE, mac[3], mac[4], mac[5])
            return eui.fold(0L) { acc, b -> (acc shl 8) or b.toLong() }
        }
    }

    private var eventSocket: DatagramSocket? = null
    private var generalSocket: DatagramSocket? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private var syncSequence = 0
    private var announceSequence = 0
    private var startedAtNs = 0L

    /** Bind 319/320 and start sending. Throws if either port cannot be bound. */
    fun start() {
        val event = bind(EVENT_PORT)
        val general = try { bind(GENERAL_PORT) } catch (e: Exception) { event.close(); throw e }
        eventSocket = event
        generalSocket = general
        running = true
        startedAtNs = nanoTime()
        worker = thread(name = "ptp-master", isDaemon = true) { loop(event, general) }
        Log.d(TAG, "PTP master 0x${clockId.toULong().toString(16)} -> ${peer.hostAddress}")
    }

    private fun bind(port: Int) = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
    }

    /**
     * Block until a receiver following this clock can have locked onto it. Audio
     * timed against the clock before then is dropped as out of date.
     */
    fun awaitReceiverLock() {
        val remainingMs = RECEIVER_LOCK_MS - (nanoTime() - startedAtNs) / 1_000_000L
        if (remainingMs > 0) Thread.sleep(remainingMs)
    }

    private fun loop(event: DatagramSocket, general: DatagramSocket) {
        var tick = 0
        while (running) {
            try {
                if (tick % SYNCS_PER_ANNOUNCE == 0) {
                    send(general, GENERAL_PORT, announce(announceSequence++))
                }
                val sequence = syncSequence++
                val origin = nanoTime()
                send(event, EVENT_PORT, sync(sequence))
                send(general, GENERAL_PORT, followUp(sequence, origin))
            } catch (e: Exception) {
                if (running) Log.w(TAG, "send failed: ${e.message}")
            }
            tick++
            try { Thread.sleep(SYNC_INTERVAL_MS) } catch (_: InterruptedException) { return }
        }
    }

    private fun send(socket: DatagramSocket, port: Int, data: ByteArray) {
        socket.send(DatagramPacket(data, data.size, peer, port))
    }

    private fun header(
        message: ByteArray, type: Int, flags: Int, sequence: Int, control: Int, logPeriod: Int
    ) {
        message[0] = (TRANSPORT_SPECIFIC or type).toByte()
        message[1] = 0x02                                  // PTP version 2
        putU16(message, 2, message.size)
        // domain 0, reserved, correctionField 0, reserved: already zero
        putU16(message, 6, flags)
        putU64(message, 20, clockId)                       // sourcePortIdentity.clockIdentity
        putU16(message, 28, 1)                             // sourcePortIdentity.portNumber
        putU16(message, 30, sequence and 0xFFFF)
        message[32] = control.toByte()
        message[33] = logPeriod.toByte()
    }

    private fun sync(sequence: Int) = ByteArray(HEADER_SIZE + 10).also {
        // twoStepFlag | ptpTimescale; the origin timestamp follows in Follow_Up
        header(it, MSG_SYNC, 0x0208, sequence, control = 0, logPeriod = -3)
    }

    private fun followUp(sequence: Int, originNs: Long) = ByteArray(HEADER_SIZE + 10 + 32).also {
        header(it, MSG_FOLLOW_UP, 0x0008, sequence, control = 2, logPeriod = -3)
        putTimestamp(it, HEADER_SIZE, originNs)
        // 802.1AS Follow_Up information TLV (11.4.4.3): organization extension,
        // 00-80-C2 subtype 1, rate/phase-change fields all zero.
        val tlv = HEADER_SIZE + 10
        putU16(it, tlv, 0x0003)
        putU16(it, tlv + 2, 28)
        it[tlv + 4] = 0x00; it[tlv + 5] = 0x80.toByte(); it[tlv + 6] = 0xC2.toByte()
        it[tlv + 9] = 0x01
    }

    private fun announce(sequence: Int) = ByteArray(HEADER_SIZE + 30).also {
        header(it, MSG_ANNOUNCE, 0x0008, sequence, control = 5, logPeriod = -2)
        val body = HEADER_SIZE
        // originTimestamp (10 bytes) left zero
        putU16(it, body + 10, 37)                          // currentUtcOffset
        it[body + 13] = 248.toByte()                       // grandmasterPriority1
        putU32(it, body + 14, 0xF8FE436AL)                 // clockClass 248, accuracy unknown
        it[body + 18] = 248.toByte()                       // grandmasterPriority2
        putU64(it, body + 19, clockId)                     // grandmasterIdentity
        putU16(it, body + 27, 0)                           // stepsRemoved
        it[body + 29] = 0xA0.toByte()                      // timeSource: internal oscillator
    }

    /** 48-bit seconds + 32-bit nanoseconds. */
    private fun putTimestamp(buf: ByteArray, off: Int, nanos: Long) {
        val seconds = nanos / 1_000_000_000L
        putU16(buf, off, ((seconds ushr 32) and 0xFFFF).toInt())
        putU32(buf, off + 2, seconds and 0xFFFFFFFFL)
        putU32(buf, off + 6, nanos % 1_000_000_000L)
    }

    private fun putU16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v ushr 8).toByte(); buf[off + 1] = v.toByte()
    }

    private fun putU32(buf: ByteArray, off: Int, v: Long) {
        for (i in 0..3) buf[off + i] = (v ushr ((3 - i) * 8)).toByte()
    }

    private fun putU64(buf: ByteArray, off: Int, v: Long) {
        for (i in 0..7) buf[off + i] = (v ushr ((7 - i) * 8)).toByte()
    }

    override fun close() {
        running = false
        worker?.interrupt()
        eventSocket?.close()
        generalSocket?.close()
    }
}
