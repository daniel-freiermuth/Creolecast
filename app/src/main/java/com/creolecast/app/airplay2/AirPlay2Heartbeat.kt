package com.creolecast.app.airplay2

import kotlin.concurrent.thread

/**
 * The two periodic session threads: a sync packet every second, and POST
 * /feedback every 2 s, matching the reference's feedbackLoop. Each feedback
 * reply re-anchors a receiver-owned PTP timeline (mirror.go feedbackLoop).
 * [FEEDBACK_FAILURE_LIMIT] failed keep-alives in a row report the receiver [onLost].
 */
internal class AirPlay2Heartbeat(
    private val control: AirPlay2ControlChannel,
    private val clock: AirPlay2Clock,
    private val audio: AirPlay2AudioStream,
    private val isRunning: () -> Boolean,
    private val onLost: (reason: String) -> Unit
) {
    private companion object {
        const val SYNC_INTERVAL_MS = 1000L
        const val FEEDBACK_INTERVAL_MS = 2000L
        const val FEEDBACK_FAILURE_LIMIT = 3
        val SUCCESS = 200..299
    }

    private var syncThread: Thread? = null
    private var keepAliveThread: Thread? = null

    fun start() {
        syncThread = thread(name = "ap2-sync", isDaemon = true) {
            while (isRunning()) {
                audio.sendSync(clock)
                try {
                    Thread.sleep(SYNC_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
        keepAliveThread = thread(name = "ap2-keepalive", isDaemon = true) {
            var failures = 0
            while (isRunning() && failures < FEEDBACK_FAILURE_LIMIT) {
                failures = if (sendFeedback()) 0 else failures + 1
                try {
                    Thread.sleep(FEEDBACK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
            if (isRunning()) onLost("$failures keep-alives failed")
        }
    }

    /** POST /feedback; false if the receiver didn't accept it. */
    @Suppress("TooGenericExceptionCaught") // Any failure of the request counts as a miss.
    private fun sendFeedback(): Boolean = try {
        val resp = control.request("POST", "/feedback", null, null)
        if (clock.usePtp) clock.reanchorIfFollowing(resp.headers)
        resp.code in SUCCESS
    } catch (_: Exception) {
        false
    }

    fun interrupt() {
        syncThread?.interrupt()
        keepAliveThread?.interrupt()
    }
}
