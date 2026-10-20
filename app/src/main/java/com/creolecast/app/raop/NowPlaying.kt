package com.creolecast.app.raop

private const val UINT32_MASK = 0xFFFFFFFFL
private const val MILLIS_PER_SECOND = 1000L
private val PNG_MAGIC =
    byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

/**
 * What one AirPlay receiver was last sent as now-playing info. Metadata is re-published
 * every few seconds; receivers should only see a new item (and re-fetch nothing) when the
 * track or the artwork actually changed.
 */
class NowPlayingTracker {
    private var track: String? = null
    private var artworkHash: Int? = null
    private var itemId = 0L

    /** The DMAP body for this track if it differs from the last one sent, else null. */
    @Synchronized
    fun trackInfoIfChanged(
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long?
    ): ByteArray? {
        val key = listOf(title, artist, album, durationMs).joinToString("\u0000")
        if (key == track) return null
        track = key
        artworkHash = null // receivers tie artwork to the current item
        return Dmap.trackInfo(title, artist, album, durationMs, ++itemId)
    }

    /** [artwork] if it differs from what was last sent for this track, else null. */
    @Synchronized
    fun artworkIfChanged(artwork: ByteArray?): ByteArray? {
        val hash = artwork?.takeIf { it.isNotEmpty() }?.contentHashCode()
        return if (hash == null || hash == artworkHash) {
            null
        } else {
            artworkHash = hash
            artwork
        }
    }
}

/** RTSP Content-Type of the artwork [image] by its magic bytes: PNG, else JPEG. */
fun artworkContentType(image: ByteArray): String =
    if (image.size >= PNG_MAGIC.size && image.copyOf(PNG_MAGIC.size).contentEquals(PNG_MAGIC)) {
        "image/png"
    } else {
        "image/jpeg"
    }

/**
 * `progress: start/now/end` in RTP time: [nowRtp] is the RTP time of what plays now,
 * [positionMs] into a track of [durationMs].
 */
fun progressParameter(nowRtp: Int, positionMs: Long, durationMs: Long, sampleRate: Int): ByteArray {
    val now = nowRtp.toLong() and UINT32_MASK
    val start = (now - positionMs * sampleRate / MILLIS_PER_SECOND) and UINT32_MASK
    val end = (start + durationMs * sampleRate / MILLIS_PER_SECOND) and UINT32_MASK
    return "progress: $start/$now/$end\r\n".toByteArray(Charsets.US_ASCII)
}
