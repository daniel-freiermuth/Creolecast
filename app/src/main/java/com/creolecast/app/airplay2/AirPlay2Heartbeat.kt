package com.creolecast.app.airplay2

import kotlin.concurrent.thread

/**
 * The two periodic session threads: a sync packet every second, and POST
 * /feedback every 2 s, matching the reference's feedbackLoop. Each feedback
 * reply re-anchors a receiver-owned PTP timeline (mirror.go feedbackLoop).
 */
internal class AirPlay2Heartbeat(
    private val control: AirPlay2ControlChannel,
    private val clock: AirPlay2Clock,
    private val audio: AirPlay2AudioStream,
    private val isRunning: () -> Boolean
) {
    private companion object {
        const val SYNC_INTERVAL_MS = 1000L
        const val FEEDBACK_INTERVAL_MS = 2000L
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
            while (isRunning()) {
                try {
                    val resp = control.request("POST", "/feedback", null, null)
                    if (clock.usePtp) clock.reanchorIfFollowing(resp.headers)
                } catch (_: Exception) {
                    // A missed keep-alive is retried on the next tick.
                }
                try {
                    Thread.sleep(FEEDBACK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    fun interrupt() {
        syncThread?.interrupt()
        keepAliveThread?.interrupt()
    }
}
