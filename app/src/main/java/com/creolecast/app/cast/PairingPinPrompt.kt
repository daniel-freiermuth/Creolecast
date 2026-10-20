package com.creolecast.app.cast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a receiver asks the user for. */
internal enum class SecretKind {
    /** A one-time code shown on the receiver's screen (AirPlay 2 pairing). */
    PIN,

    /** The receiver's access password (password-protected AirPlay receivers). */
    PASSWORD
}

/** A pending request for [kind] from the receiver at [host]. */
internal data class SecretRequest(val host: String, val kind: SecretKind)

/**
 * Hands a PIN or password request from a session coroutine to the UI and the user's answer
 * back. [requests] holds the pending request, or null when nothing is pending.
 */
internal class PairingPinPrompt {
    private val pending = MutableStateFlow<SecretRequest?>(null)
    val requests: StateFlow<SecretRequest?> = pending.asStateFlow()

    @Volatile private var activeDeferred: CompletableDeferred<String?>? = null

    /** Publishes a request and suspends until it is answered; null = cancelled. */
    suspend fun request(host: String, kind: SecretKind = SecretKind.PIN): String? {
        val deferred = CompletableDeferred<String?>()
        activeDeferred = deferred
        pending.value = SecretRequest(host, kind)
        val answer = deferred.await()
        pending.value = null
        activeDeferred = null
        return answer
    }

    fun submit(secret: String) {
        activeDeferred?.complete(secret)
        activeDeferred = null
    }

    fun cancel() {
        activeDeferred?.complete(null)
        activeDeferred = null
        pending.value = null
    }
}
