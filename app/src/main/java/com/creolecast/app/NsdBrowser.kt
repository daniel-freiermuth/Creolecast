package com.creolecast.app

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.Executors

/**
 * Browses DNS-SD service types with [NsdManager] and tracks every service found through
 * one [NsdManager.ServiceInfoCallback] each. Unlike the one-shot resolveService it
 * replaces, a service info callback keeps delivering the service's current addresses and
 * TXT records until it is unregistered, and the system runs any number of them
 * concurrently, so no resolve queue or throttle is needed.
 *
 * Services are identified by a stable id (type and instance name). [onUpdated] runs on a
 * single background thread, once per resolve and again on every address or TXT change;
 * [onLost] may run on that thread or on NsdManager's. [onDiscoveryStarted] runs once per
 * browsed type.
 */
internal class NsdBrowser(
    private val nsdManager: NsdManager,
    private val onDiscoveryStarted: () -> Unit,
    private val onUpdated: (id: String, serviceInfo: NsdServiceInfo) -> Unit,
    private val onLost: (id: String) -> Unit
) {
    /** Serial executor for every [NsdManager.ServiceInfoCallback] invocation. */
    private val callbackExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "nsd-service-info") }

    private val discoveryListeners = mutableListOf<NsdManager.DiscoveryListener>()

    /** The live [NsdManager.ServiceInfoCallback] for each found service, by [serviceId]. */
    private val serviceCallbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()

    /** Starts browsing each of [serviceTypes], e.g. "_raop._tcp". */
    fun start(serviceTypes: List<String>) {
        serviceTypes.forEach { type ->
            try {
                val listener = createDiscoveryListener()
                synchronized(discoveryListeners) { discoveryListeners.add(listener) }
                nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Discovery failed for $type", e)
            }
        }
    }

    /** Stops browsing and tracking; events still in flight are dropped. */
    fun stop() {
        synchronized(discoveryListeners) {
            discoveryListeners.forEach { listener ->
                try {
                    nsdManager.stopServiceDiscovery(listener)
                } catch (e: IllegalArgumentException) {
                    // The listener's discovery never started or has already failed.
                    Log.d(TAG, "NSD listener was not discovering", e)
                }
            }
            discoveryListeners.clear()
        }

        val callbacks = synchronized(serviceCallbacks) {
            serviceCallbacks.values.toList().also { serviceCallbacks.clear() }
        }
        callbacks.forEach { unregisterQuietly(it) }
    }

    private fun createDiscoveryListener() = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.d(TAG, "Discovery started: $serviceType")
            onDiscoveryStarted()
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
            val id = serviceId(serviceInfo)
            val callback = synchronized(serviceCallbacks) {
                // onServiceFound repeats for the same service (e.g. once per network);
                // the callback already registered keeps it up to date.
                if (id in serviceCallbacks) return
                createServiceInfoCallback(id).also { serviceCallbacks[id] = it }
            }
            try {
                nsdManager.registerServiceInfoCallback(serviceInfo, callbackExecutor, callback)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "registerServiceInfoCallback failed for $id", e)
                synchronized(serviceCallbacks) { serviceCallbacks.remove(id, callback) }
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            val id = serviceId(serviceInfo)
            val callback = synchronized(serviceCallbacks) { serviceCallbacks.remove(id) }
            callback?.let { unregisterQuietly(it) }
            onLost(id)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "Discovery stopped: $serviceType")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "NSD discovery start failed: $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "NSD discovery stop failed: $errorCode")
        }
    }

    private fun createServiceInfoCallback(id: String) = object : NsdManager.ServiceInfoCallback {
        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
            Log.e(TAG, "Service info callback registration failed: $errorCode for $id")
            synchronized(serviceCallbacks) { serviceCallbacks.remove(id, this) }
        }

        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
            if (isCurrent()) onUpdated(id, serviceInfo)
        }

        override fun onServiceLost() {
            if (isCurrent()) onLost(id)
        }

        override fun onServiceInfoCallbackUnregistered() {
            // Nothing to release: stop()/onServiceLost already dropped this callback.
        }

        // Unregistration is asynchronous: drop events still in flight from a callback
        // that stop()/onServiceLost already replaced or removed.
        private fun isCurrent() = synchronized(serviceCallbacks) { serviceCallbacks[id] === this }
    }

    private fun unregisterQuietly(callback: NsdManager.ServiceInfoCallback) {
        try {
            nsdManager.unregisterServiceInfoCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Registration already failed or was torn down by the system.
            Log.d(TAG, "Service info callback was not registered", e)
        }
    }

    private companion object {
        const val TAG = "NsdBrowser"

        fun serviceId(serviceInfo: NsdServiceInfo): String =
            "${serviceInfo.serviceType.trimEnd('.')}/${serviceInfo.serviceName}"
    }
}
