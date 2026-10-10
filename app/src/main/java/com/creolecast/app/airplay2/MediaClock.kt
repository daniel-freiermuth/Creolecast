package com.creolecast.app.airplay2

/**
 * Maps local monotonic time onto the PTP timeline the receiver plays against.
 * Timestamps are AirPlay's 32.32 fixed-point seconds.
 *
 * Transcribed from doubletake `internal/airplay/mirror.go` `mediaClock`. The
 * receiver's `X-Apple-RequestReceivedTimestamp` is in the same boot-relative
 * domain as its PTP Follow_Up timestamps, so following an Apple receiver's
 * clock needs no local PTP stack.
 */
class MediaClock(private val nanoTime: () -> Long = System::nanoTime) {

    private var anchorLocalNs = 0L
    private var anchorTimestamp = 0L
    private var anchored = false

    /** PTP clock identity of the timeline; 0 until configured. */
    var timelineId = 0L
        @Synchronized get
        private set

    /**
     * Anchor on a SETUP response from a receiver that publishes its own clock.
     * Returns false when the private clock headers are missing; the timeline
     * identity is still kept so [configureFromLocalClock] can anchor to it.
     */
    @Synchronized
    fun configureFromSetup(clockId: Long, headers: Map<String, String>, receivedAtNs: Long): Boolean {
        require(clockId != 0L) { "clockId must be non-zero" }
        timelineId = clockId
        val timestamp = receiverClockTimestamp(headers) ?: return false
        anchorLocalNs = receivedAtNs
        anchorTimestamp = timestamp
        anchored = true
        return true
    }

    /**
     * Anchor [clockId]'s timeline to the local monotonic clock. Used when the
     * receiver omits the clock headers, and when we are the PTP master ourselves
     * (whose Follow_Up timestamps are taken from the same [nanoTime]).
     */
    @Synchronized
    fun configureFromLocalClock(clockId: Long) {
        require(clockId != 0L) { "clockId must be non-zero" }
        val now = nanoTime()
        timelineId = clockId
        anchorLocalNs = now
        anchorTimestamp = compactTimestamp(now)
        anchored = true
    }

    /**
     * Refresh the anchor from a later response (stream SETUP, `/feedback`).
     * The receiver's timestamp is observed only after network delay, so a fresh
     * sample can look older than the current mapping — never move backwards.
     */
    @Synchronized
    fun reanchor(headers: Map<String, String>, receivedAtNs: Long): Boolean {
        var timestamp = receiverClockTimestamp(headers) ?: return false
        if (anchored && receivedAtNs >= anchorLocalNs) {
            val projected = anchorTimestamp + compactTimestamp(receivedAtNs - anchorLocalNs)
            if (java.lang.Long.compareUnsigned(timestamp, projected) < 0) timestamp = projected
        }
        anchorTimestamp = timestamp
        anchorLocalNs = receivedAtNs
        anchored = true
        return true
    }

    /** Current position on the timeline, or null before the clock is anchored. */
    @Synchronized
    fun now(): Long? {
        if (!anchored || timelineId == 0L) return null
        return anchorTimestamp + compactTimestamp(nanoTime() - anchorLocalNs)
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L

        /** Nanoseconds -> 32.32 fixed-point seconds (mirror.go compactTimestamp). */
        fun compactTimestamp(nanos: Long): Long {
            val d = if (nanos < 0) 0L else nanos
            val seconds = d / NANOS_PER_SECOND
            val fraction = ((d % NANOS_PER_SECOND) shl 32) / NANOS_PER_SECOND
            return (seconds shl 32) or fraction
        }

        /**
         * Receiver time at which it answered: request-received timestamp plus
         * processing time, both in milliseconds (mirror.go receiverClockTimestamp).
         */
        fun receiverClockTimestamp(headers: Map<String, String>): Long? {
            fun header(name: String) =
                headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()
            val received = header("X-Apple-RequestReceivedTimestamp")?.toLongOrNull() ?: return null
            val processing = header("X-Apple-ProcessingTime")?.toLongOrNull() ?: return null
            if (received < 0 || processing < 0) return null
            val maxMillis = Long.MAX_VALUE / 1_000_000L
            if (received > maxMillis || processing > maxMillis - received) return null
            return compactTimestamp((received + processing) * 1_000_000L)
        }
    }
}
