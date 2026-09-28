package com.creolecast.app.airplay2

/**
 * Maps local monotonic time ([System.nanoTime]) onto the receiver's PTP
 * timeline, transcribed from mirror.go `mediaClock`. The receiver's
 * X-Apple-RequestReceivedTimestamp is in the same boot-relative domain as its
 * PTP Follow_Up timestamps, so no local PTP stack is required.
 */
internal class PtpMediaClock(
    private val anchorLocalNanos: Long,
    private val anchorTimelineNanos: Long
) {
    /** Receiver-timeline nanoseconds at local [System.nanoTime] instant [localNanos]. */
    fun nanosAt(localNanos: Long): Long = anchorTimelineNanos + (localNanos - anchorLocalNanos)

    companion object {
        private const val MAX_MILLIS = Long.MAX_VALUE / 1_000_000L

        /**
         * Anchors the receiver's SETUP clock headers to the local instant the
         * response arrived (mirror.go configureFromSetup/receiverClockTimestamp).
         * Returns null when either header is missing or malformed.
         */
        fun fromSetupHeaders(headers: Map<String, String>, receivedAtNanos: Long): PtpMediaClock? {
            fun header(name: String) =
                headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.toLongOrNull()
            val receivedMillis = header("X-Apple-RequestReceivedTimestamp") ?: return null
            val processingMillis = header("X-Apple-ProcessingTime") ?: return null
            if (receivedMillis < 0 || processingMillis < 0 ||
                receivedMillis > MAX_MILLIS || processingMillis > MAX_MILLIS - receivedMillis
            ) return null
            return PtpMediaClock(receivedAtNanos, (receivedMillis + processingMillis) * 1_000_000L)
        }

        /**
         * Fallback for receivers that omit the clock headers: anchor the
         * timeline to the sender's own boot-relative clock
         * (mirror.go configureFromLocalClock). On Android [System.nanoTime] is
         * CLOCK_MONOTONIC, i.e. time since boot.
         */
        fun fromLocalClock(nowNanos: Long): PtpMediaClock = PtpMediaClock(nowNanos, nowNanos)
    }
}
