package com.creolecast.app.cast

import android.util.Log
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** [Throwable.message] for log lines, falling back to the exception's class name. */
internal val Throwable.reason: String
    get() = message ?: javaClass.simpleName

/** Closes [closeable]; a failing close() only means the resource is already gone. */
internal fun closeQuietly(closeable: Closeable?, what: String) {
    try {
        closeable?.close()
    } catch (e: IOException) {
        Log.d(LOG_TAG, "Closing $what failed: ${e.reason}")
    }
}

/**
 * Runs one HTTP exchange with a receiver and returns its failure instead of throwing it.
 *
 * Ktor (OkHttp engine) reports a failed exchange as an [IOException] (connection errors,
 * timeouts), an [IllegalArgumentException] (malformed receiver-supplied URL) or an
 * [IllegalStateException] (URL parser, closed client). Cancellation is propagated.
 */
internal suspend fun <T> httpExchange(exchange: suspend () -> T): Result<T> = try {
    Result.success(exchange())
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    Result.failure(e)
} catch (e: IllegalArgumentException) {
    Result.failure(e)
} catch (e: IllegalStateException) {
    Result.failure(e)
}

/**
 * Runs one best-effort command against a receiver (volume change, metadata push,
 * teardown request) and logs instead of propagating its failure.
 *
 * These commands reach third-party devices through Ktor WebSockets/HTTP, raw RTSP and
 * [com.creolecast.app.airplay2.AirPlay2Client], whose failures surface as arbitrary
 * exception types (I/O, protocol, crypto, closed channels). None of them may abort the
 * cast or crash the service, so this is a deliberate catch-all boundary. Cancellation is
 * still propagated.
 */
@Suppress("TooGenericExceptionCaught") // Deliberate boundary for receiver commands, see KDoc.
internal suspend fun bestEffort(what: String, command: suspend () -> Unit) {
    try {
        command()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(LOG_TAG, "$what failed: ${e.reason}")
    }
}
