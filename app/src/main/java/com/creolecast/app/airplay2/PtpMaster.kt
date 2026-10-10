package com.creolecast.app.airplay2

import android.util.Log
import java.io.Closeable
import java.io.IOException
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

        private const val SYNC_INTERVAL_MS = 125L
        private const val SYNCS_PER_ANNOUNCE = 2
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MAC_ADDRESS_BYTES = 6

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
            require(mac.size == MAC_ADDRESS_BYTES) { "device id must be a MAC address: $deviceId" }
            val eui = intArrayOf(mac[0], mac[1], mac[2], 0xFF, 0xFE, mac[3], mac[4], mac[5])
            return eui.fold(0L) { acc, b -> (acc shl 8) or b.toLong() }
        }
    }

    private val messages = PtpMessages(clockId)
    private var eventSocket: DatagramSocket? = null
    private var generalSocket: DatagramSocket? = null
    private var worker: Thread? = null

    @Volatile private var running = false
    private var syncSequence = 0
    private var announceSequence = 0
    private var startedAtNs = 0L

    /** Bind 319/320 and start sending. Throws if either port cannot be bound. */
    @Suppress("MissingUseCall") // Both sockets live until close(); `use` would close them here.
    fun start() {
        val event = bind(EVENT_PORT)
        var bound = false
        val general = try {
            bind(GENERAL_PORT).also { bound = true }
        } finally {
            if (!bound) event.close()
        }
        eventSocket = event
        generalSocket = general
        running = true
        startedAtNs = nanoTime()
        worker = thread(name = "ptp-master", isDaemon = true) { loop(event, general) }
        Log.d(TAG, "PTP master 0x${clockId.toULong().toString(16)} -> ${peer.hostAddress}")
    }

    private fun bind(port: Int): DatagramSocket {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        return socket
    }

    /**
     * Block until a receiver following this clock can have locked onto it. Audio
     * timed against the clock before then is dropped as out of date.
     */
    fun awaitReceiverLock() {
        val remainingMs = RECEIVER_LOCK_MS - (nanoTime() - startedAtNs) / NANOS_PER_MILLI
        if (remainingMs > 0) Thread.sleep(remainingMs)
    }

    private fun loop(event: DatagramSocket, general: DatagramSocket) {
        var tick = 0
        while (running) {
            try {
                if (tick % SYNCS_PER_ANNOUNCE == 0) {
                    send(general, GENERAL_PORT, messages.announce(announceSequence++))
                }
                val sequence = syncSequence++
                val origin = nanoTime()
                send(event, EVENT_PORT, messages.sync(sequence))
                send(general, GENERAL_PORT, messages.followUp(sequence, origin))
            } catch (e: IOException) {
                if (running) Log.w(TAG, "send failed", e)
            }
            tick++
            try {
                Thread.sleep(SYNC_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun send(socket: DatagramSocket, port: Int, data: ByteArray) {
        socket.send(DatagramPacket(data, data.size, peer, port))
    }

    override fun close() {
        running = false
        worker?.interrupt()
        eventSocket?.close()
        generalSocket?.close()
    }
}
