package com.creolecast.app.cast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hands an AirPlay 2 PIN request from a session coroutine to the UI and the user's answer
 * back. [requests] holds the host awaiting a PIN, or null when nothing is pending.
 */
internal class PairingPinPrompt {
    private val pendingHost = MutableStateFlow<String?>(null)
    val requests: StateFlow<String?> = pendingHost.asStateFlow()

    @Volatile private var activeDeferred: CompletableDeferred<String?>? = null

    /** Publishes a PIN request for [host] and suspends until it is answered; null = cancelled. */
    suspend fun request(host: String): String? {
        val deferred = CompletableDeferred<String?>()
        activeDeferred = deferred
        pendingHost.value = host
        val pin = deferred.await()
        pendingHost.value = null
        activeDeferred = null
        return pin
    }

    fun submit(pin: String) {
        activeDeferred?.complete(pin)
        activeDeferred = null
    }

    fun cancel() {
        activeDeferred?.complete(null)
        activeDeferred = null
        pendingHost.value = null
    }
}
