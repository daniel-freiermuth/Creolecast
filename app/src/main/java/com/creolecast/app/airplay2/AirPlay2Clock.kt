package com.creolecast.app.airplay2

import android.util.Log
import java.io.IOException
import java.net.InetAddress

/**
 * Session timing: NTP, or a PTP timeline that is either the receiver's (Apple)
 * or ours, mastered by [PtpMaster] (shairport-sync + nqptp).
 */
internal class AirPlay2Clock(private val host: String, private val deviceId: String) {

    private companion object {
        const val TAG = "AirPlay2Client"
        const val HEX_RADIX = 16
    }

    /** Negotiated timing protocol: PTP when true, NTP otherwise. */
    var usePtp = false

    /** PTP timeline our 0xd7 sync packets are stamped on. */
    val mediaClock = MediaClock()

    /** Running only when the receiver follows our clock instead of publishing one. */
    private var ptpMaster: PtpMaster? = null

    /**
     * Receivers that publish a ClockID (Apple) own the timeline: follow it via
     * their clock headers (mirror.go configurePTPClock). Receivers that don't
     * (shairport-sync + nqptp) follow the sender, so become the PTP master.
     */
    fun configurePtp(clockId: Long, headers: Map<String, String>, receivedAt: Long): Boolean {
        if (clockId == 0L) return startPtpMaster()
        if (!mediaClock.configureFromSetup(clockId, headers, receivedAt)) {
            val timeline = clockId.toULong().toString(HEX_RADIX)
            Log.d(TAG, "SETUP lacks receiver clock headers; local clock on 0x$timeline")
            mediaClock.configureFromLocalClock(clockId)
        }
        return true
    }

    /** Refresh a receiver-owned timeline from a reply's clock headers. */
    fun reanchorIfFollowing(headers: Map<String, String>) {
        if (ptpMaster == null) mediaClock.reanchor(headers, System.nanoTime())
    }

    /** Now on the negotiated timeline (32.32 fixed point), or null before PTP is anchored. */
    fun networkTime(): Long? = if (usePtp) mediaClock.now() else NtpTime.now()

    /** Blocks until a receiver following our PTP clock can have locked onto it. */
    fun awaitReceiverLock() {
        ptpMaster?.awaitReceiverLock()
    }

    fun close() {
        ptpMaster?.close()
        ptpMaster = null
    }

    @Suppress("MissingUseCall") // Runs for the session until close(); `use` would stop it here.
    private fun startPtpMaster(): Boolean {
        val master = PtpMaster(InetAddress.getByName(host), PtpMaster.clockIdFromDeviceId(deviceId))
        val started = try {
            master.start()
            true
        } catch (e: IOException) {
            Log.e(TAG, "Receiver needs a sender PTP clock but UDP 319/320 cannot be bound", e)
            false
        }
        if (started) {
            ptpMaster = master
            mediaClock.configureFromLocalClock(master.clockId)
        }
        return started
    }
}
