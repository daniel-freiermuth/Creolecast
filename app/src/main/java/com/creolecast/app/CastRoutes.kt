package com.creolecast.app

import android.media.MediaRoute2Info
import android.media.RoutingSessionInfo
import android.util.Log

/**
 * Builds the system media routes and routing sessions [CastRouteProvider] publishes for
 * the receivers [DiscoveryManager] has found.
 */
internal object CastRoutes {
    private const val TAG = "CastRouteProvider"

    /** Volume steps exposed to the system output picker; step [VOLUME_MAX] is 0 dB. */
    const val VOLUME_MAX = 30

    /**
     * One route per server. notifyRoutes() throws if two routes share an id, which would
     * take the whole process down from a discovery callback. Distinct discovered entries
     * can still collapse onto one id (same device answering on two discovery protocols),
     * so the platform's uniqueness requirement is enforced here rather than trusted.
     */
    fun routesFor(servers: List<Server>, volume: Int): List<MediaRoute2Info> {
        val routes = servers.map { server -> routeInfo(server, volume) }.distinctBy { it.id }
        Log.d(TAG, "Publishing ${routes.size} routes: ${routes.map { it.name }}")
        return routes
    }

    fun sessionInfo(
        sessionId: String,
        clientPackageName: String,
        name: String,
        selectedRouteId: String,
        volume: Int
    ): RoutingSessionInfo = RoutingSessionInfo.Builder(sessionId, clientPackageName)
        .setName(name)
        .addSelectedRoute(selectedRouteId)
        .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
        .setVolumeMax(VOLUME_MAX)
        .setVolume(volume)
        .build()

    /** Must stay byte-identical to the [Server] overload below — the id produced here
     *  is handed to addSelectedRoute() and has to match a published route. */
    fun routeId(dest: CastDestination): String =
        "${dest.host}:${dest.port}:${dest.platform.label}:${dest.name}"

    // The name is part of the id because host+port+platform alone is not unique:
    // every DLNA entry carries port 0, so two renderers on one IP would collide.
    private fun routeId(server: Server): String =
        "${server.host}:${server.port}:${server.platform.label}:${server.name}"

    private fun routeInfo(server: Server, volume: Int): MediaRoute2Info =
        MediaRoute2Info.Builder(routeId(server), server.name)
            .addFeature(MediaRoute2Info.FEATURE_LIVE_AUDIO)
            .addFeature(MediaRoute2Info.FEATURE_REMOTE_PLAYBACK)
            .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
            .setVolumeMax(VOLUME_MAX)
            .setVolume(volume)
            .setDescription(server.platform.label)
            .setType(MediaRoute2Info.TYPE_REMOTE_SPEAKER)
            .build()
}
