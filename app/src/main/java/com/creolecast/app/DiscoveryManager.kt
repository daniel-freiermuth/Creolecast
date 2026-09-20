package com.creolecast.app

import android.content.Context
import android.net.InetAddresses
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.creolecast.app.raop.RaopDiscovery
import java.net.Inet4Address
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DiscoveryManager(
    private val context: Context,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(ioDispatcher + job)

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.IDLE)
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(
        Context.WIFI_SERVICE
    ) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val discoveredServers = mutableMapOf<String, Server>()
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private var activeResolves = 0
    private var discoveryJob: Job? = null
    private val activeListeners = mutableListOf<NsdManager.DiscoveryListener>()
    private var raopDiscovery: RaopDiscovery? = null
    private val audioCastDiscovery = AudioCastBroadcastDiscovery(ioDispatcher) { server ->
        putFound(server.name, server)
    }
    private val ssdpDiscovery = SsdpDiscovery(ioDispatcher, ::addDlnaRenderer)

    private fun createNsdListener() = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            if (_state.value != DiscoveryState.FOUND) {
                _state.value = DiscoveryState.SCANNING
            }
            Log.d(TAG, "Discovery started: $serviceType")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
            resolveQueue.add(serviceInfo)
            processResolveQueue()
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            val nameToRemove =
                MdnsServers.lostServiceKey(serviceInfo.serviceType, serviceInfo.serviceName)
            synchronized(discoveredServers) {
                discoveredServers.remove(nameToRemove)
                _servers.value = discoveredServers.values.toList()
                if (discoveredServers.isEmpty()) {
                    _state.value = DiscoveryState.NONE
                }
            }
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

    private fun processResolveQueue() {
        if (activeResolves >= MAX_CONCURRENT_RESOLVES || resolveQueue.isEmpty()) return

        val serviceInfo = resolveQueue.poll() ?: return
        activeResolves++

        try {
            // Deprecated in API 34; the migration to registerServiceInfoCallback is PR #36.
            @Suppress("DEPRECATION")
            nsdManager.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "Resolve failed: $errorCode for ${si.serviceName}")
                        activeResolves--
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                            scope.launch {
                                delay(RESOLVE_RETRY_DELAY_MS)
                                resolveQueue.add(si)
                                processResolveQueue()
                            }
                        } else {
                            processResolveQueue()
                        }
                    }

                    override fun onServiceResolved(si: NsdServiceInfo) {
                        activeResolves--
                        handleResolvedService(si)
                        processResolveQueue()
                    }
                }
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Could not resolve ${serviceInfo.serviceName}", e)
            activeResolves--
            processResolveQueue()
        }
    }

    private fun handleResolvedService(serviceInfo: NsdServiceInfo) {
        val serviceName = serviceInfo.serviceName ?: return

        // Deprecated in API 34 in favour of hostAddresses; migrated together with PR #36.
        @Suppress("DEPRECATION")
        val hostAddress = serviceInfo.host?.hostAddress?.takeUnless { isLoopbackHost(it) }
            ?: return

        val server = MdnsServers.fromResolvedService(
            serviceInfo.serviceType,
            serviceName,
            hostAddress,
            serviceInfo.port,
            serviceInfo.attributes
        )
        val name = server.name

        synchronized(discoveredServers) {
            // A different device advertising the same friendly name must not silently
            // take over an already-discovered entry's host - that's exactly how mDNS
            // name spoofing would redirect a user who trusts a familiar name into
            // casting to an attacker's box instead. Key it separately and disambiguate
            // the display name so both stay visible rather than one clobbering the other.
            val existingSameName = discoveredServers[name]
            val spoofedName = existingSameName != null && existingSameName.host != hostAddress
            val baseKey = if (spoofedName) "$name@$hostAddress" else name
            val key = MdnsServers.storageKey(baseKey, server.platform)
            val candidate = if (spoofedName) server.copy(name = "$name ($hostAddress)") else server

            val isRaop = serviceInfo.serviceType.contains("_raop")
            putFound(key, MdnsServers.merged(discoveredServers[key], candidate, isRaop))
        }
    }

    /** Stores a DLNA renderer, keeping a same-named renderer on another host separate. */
    private fun addDlnaRenderer(renderer: Server) {
        val friendlyName = renderer.name
        val hostAddress = renderer.host
        synchronized(discoveredServers) {
            val existing = discoveredServers[friendlyName]
            if (existing != null && existing.host != hostAddress) {
                putFound(
                    "$friendlyName@$hostAddress",
                    renderer.copy(name = "$friendlyName ($hostAddress)")
                )
            } else {
                putFound(friendlyName, renderer)
            }
        }
    }

    /** Stores [server] under [key], publishes the list and leaves the NONE/IDLE state. */
    private fun putFound(key: String, server: Server) {
        synchronized(discoveredServers) {
            discoveredServers[key] = server
            _servers.value = discoveredServers.values.toList()
            if (_state.value != DiscoveryState.SCANNING) {
                _state.value = DiscoveryState.FOUND
            }
        }
    }

    fun startDiscovery() {
        stopDiscovery()
        synchronized(discoveredServers) {
            discoveredServers.clear()
            _servers.value = emptyList()
        }

        acquireMulticastLock()

        _state.value = DiscoveryState.SCANNING

        val prefs = context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val services = mutableListOf("_audiocast._tcp")
        if (prefs.getBoolean("airplay2_enabled", false)) {
            services.add("_airplay._tcp")
        }
        if (prefs.getBoolean("airplay_enabled", false)) {
            startRaopDiscovery()
        }
        if (prefs.getBoolean("google_cast_enabled", false)) {
            services.add("_googlecast._tcp")
        }
        if (prefs.getBoolean("snapcast_enabled", false)) {
            services.add("_snapcast-ctrl._tcp")
            services.add("_snapcast-jsonrpc._tcp")
        }

        discoveryJob = scope.launch {
            // Initial burst
            launch {
                services.forEach { type ->
                    try {
                        val listener = createNsdListener()
                        synchronized(activeListeners) { activeListeners.add(listener) }
                        nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Discovery failed for $type", e)
                    }
                }
            }

            launch { audioCastDiscovery.run() }
            if (prefs.getBoolean("dlna_enabled", false)) {
                launch { ssdpDiscovery.run() }
            }

            // Keep "SCANNING" state for at least 15 seconds
            delay(MIN_SCANNING_MS)
            if (_state.value == DiscoveryState.SCANNING) {
                _state.value =
                    if (discoveredServers.isEmpty()) DiscoveryState.NONE else DiscoveryState.FOUND
            }
        }
    }

    private fun acquireMulticastLock() {
        try {
            val lock = multicastLock
                ?: wifiManager.createMulticastLock("CreoleCastDiscoveryLock")
                    .also { multicastLock = it }
            lock.acquire()
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not acquire multicast lock", e)
        } catch (e: UnsupportedOperationException) {
            // Thrown once the process holds the platform's maximum number of Wi-Fi locks.
            Log.w(TAG, "Could not acquire multicast lock", e)
        }
    }

    private fun startRaopDiscovery() {
        raopDiscovery = RaopDiscovery(context)
        raopDiscovery?.start { device ->
            val server = Server(
                name = device.name,
                host = device.host,
                port = device.port,
                version = device.model ?: "1.0",
                codecs = listOf("PCM"),
                sampleRate = device.sampleRate,
                channels = 2,
                platform = "AirPlay",
                extra = "et=${device.encryptionType};sr=${device.sampleRate};cn=${device.codec}"
            )
            scope.launch {
                discoveredServers[device.host] = server
                _servers.value = discoveredServers.values.toList()
                _state.value = DiscoveryState.FOUND
            }
        }
    }

    fun stopDiscovery() {
        raopDiscovery?.stop()
        raopDiscovery = null

        discoveryJob?.cancel()
        discoveryJob = null

        synchronized(activeListeners) {
            activeListeners.forEach { listener ->
                try {
                    nsdManager.stopServiceDiscovery(listener)
                } catch (e: IllegalArgumentException) {
                    // The listener's discovery never started or has already failed.
                    Log.d(TAG, "NSD listener was not discovering", e)
                }
            }
            activeListeners.clear()
        }

        // Only ever acquired once per startDiscovery(), which stops first, so a held
        // lock is released exactly once and cannot be under-locked.
        if (multicastLock?.isHeld == true) multicastLock?.release()
        resolveQueue.clear()
        activeResolves = 0
    }

    /**
     * Removes a server the user deleted from the list.
     *
     * Matching is by [identityOf] rather than by map key. Every discovery path derives
     * its own key — the plain name, "name@host" when two hosts claim one name,
     * "name::platform" for AirPlay/AirPlay 2, or the bare host for RAOP — so a caller
     * holding only a [Server] cannot reconstruct the key it was stored under. Removing
     * by display name, as this once did, silently did nothing for every AirPlay,
     * AirPlay 2 and name-disambiguated entry.
     */
    fun removeServer(server: Server) {
        val identity = identityOf(server)
        synchronized(discoveredServers) {
            if (!discoveredServers.entries.removeAll { identityOf(it.value) == identity }) {
                Log.w(TAG, "removeServer: no entry matching $identity")
                return
            }
            _servers.value = discoveredServers.values.toList()
        }
    }

    /**
     * Adds a server that was entered manually
     * rather than discovered through mDNS/SSDP/UDP. Returns false if the host is not a
     * valid IP address, true once the server has been merged into [servers].
     */
    fun addManualServer(host: String, port: Int, name: String): Boolean {
        if (!isIpv4Literal(host)) {
            Log.w(TAG, "addManualServer: invalid host '$host'")
            return false
        }
        val server = Server(
            name = name,
            host = host,
            port = port,
            version = "manual",
            codecs = emptyList(),
            sampleRate = 48000,
            channels = 2,
            platform = "Manual"
        )
        synchronized(discoveredServers) {
            discoveredServers[name] = server
            _servers.value = discoveredServers.values.toList()
        }
        Log.d(TAG, "Manually added server: $name @ $host:$port")
        return true
    }

    companion object {
        private const val TAG = "DiscoveryManager"
        private const val MAX_CONCURRENT_RESOLVES = 3
        private const val RESOLVE_RETRY_DELAY_MS = 1000L
        private const val MIN_SCANNING_MS = 15_000L

        // URLs are built as "http://$host:$port", so only IPv4 literals are accepted (an IPv6
        // literal would need brackets), matching the previous Patterns.IP_ADDRESS check.
        private fun isIpv4Literal(host: String): Boolean = InetAddresses.isNumericAddress(host) &&
            InetAddresses.parseNumericAddress(host) is Inet4Address

        private fun isLoopbackHost(hostAddress: String): Boolean =
            hostAddress == "127.0.0.1" || hostAddress == "::1" || hostAddress.contains("localhost")

        /**
         * Stable identity of a discovered server: the tuple that actually distinguishes
         * one receiver from another, independent of which discovery path found it and of
         * metadata merged in later ([Server.extra], [Server.version]). The name is part
         * of it because host+port+platform is not unique — every DLNA entry carries
         * port 0, so several renderers on one IP share that triple.
         */
        internal fun identityOf(server: Server): String =
            "${server.host}:${server.port}:${server.platform ?: "unknown"}:${server.name}"
    }
}

enum class DiscoveryState { IDLE, SCANNING, FOUND, NONE }
