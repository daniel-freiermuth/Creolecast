package com.creolecast.app.cast

import android.util.Log
import com.creolecast.app.CastState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Failed reconnect attempts in a row before a lost cast is given up. */
private const val MAX_RECONNECT_FAILURES = 3
private const val RECONNECT_BACKOFF_MS = 1000L

/** How one connection of a RAOP or AirPlay 2 session ended (cancellation aside). */
internal enum class SessionEnd {
    /** It streamed until the receiver went away (closed, rebooted, out of reach). */
    LOST,

    /** It never got to stream: connecting, pairing or setup failed. */
    FAILED,

    /** It ended for good and must not be retried (e.g. the user declined to pair). */
    DONE
}

/**
 * Runs [connect] until the cast ends. A connection that is [SessionEnd.LOST] while
 * streaming is re-established, with backoff; after [MAX_RECONNECT_FAILURES] failed
 * attempts in a row, or if the very first connection fails, the cast ends in
 * [CastState.ERROR]. Cancellation (the user stopping the cast) propagates.
 */
internal suspend fun reconnecting(
    ctx: CastContext,
    name: String,
    connect: suspend () -> SessionEnd
) {
    var reconnecting = false
    var failures = 0
    while (true) {
        val end = connect()
        // A connection that ends because the cast is being stopped must not touch the state.
        currentCoroutineContext().ensureActive()
        when (end) {
            SessionEnd.DONE -> return

            SessionEnd.LOST -> {
                Log.w(LOG_TAG, "$name: connection lost, reconnecting")
                reconnecting = true
                failures = 0
                ctx.state.value = CastState.CONNECTING
                ctx.refreshStatus()
            }

            SessionEnd.FAILED -> if (!reconnecting || ++failures >= MAX_RECONNECT_FAILURES) {
                ctx.state.value = CastState.ERROR
                ctx.refreshStatus()
                return
            }
        }
        delay(RECONNECT_BACKOFF_MS shl failures)
    }
}
