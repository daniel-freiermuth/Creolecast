package com.creolecast.app

/**
 * Receiver protocol a [Server] / [CastDestination] speaks.
 *
 * [label] is the wire form: it travels in Intent extras
 * ([AudioCastService.EXTRA_SERVER_PLATFORM]), is persisted under
 * [AudioCastService.KEY_LAST_SERVER_PLATFORM], and is shown in the UI. Existing
 * saved preferences hold these exact strings, so labels must not be renamed.
 */
enum class CastPlatform(val label: String) {
    AIRPLAY("AirPlay"),
    AIRPLAY2("AirPlay2"),
    DLNA("DLNA"),
    GOOGLE_CAST("Google Cast"),
    SNAPCAST("Snapcast"),
    ARIACAST("AriaCast"),

    /** Added by IP in the UI; spoken to with the AriaCast WebSocket protocol. */
    MANUAL("Manual");

    /** Hardware volume keys are routed to the receiver through a MediaSession. */
    val supportsVolumeSession: Boolean
        get() = when (this) {
            AIRPLAY, AIRPLAY2, ARIACAST, DLNA -> true
            GOOGLE_CAST, SNAPCAST, MANUAL -> false
        }

    /** The receiver pulls the stream (and artwork) from a local HTTP server. */
    val pullsStreamOverHttp: Boolean
        get() = when (this) {
            DLNA, GOOGLE_CAST, AIRPLAY -> true
            AIRPLAY2, SNAPCAST, ARIACAST, MANUAL -> false
        }

    companion object {
        /** Parses a [label]; returns null for null or unrecognised input. */
        fun fromLabel(label: String?): CastPlatform? = entries.firstOrNull { it.label == label }
    }
}
