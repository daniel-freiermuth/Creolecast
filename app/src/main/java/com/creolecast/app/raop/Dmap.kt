package com.creolecast.app.raop

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** DMAP item kind of an audio track. */
private const val ITEM_KIND_AUDIO = 2
private const val TAG_LENGTH = 4

/**
 * DMAP-tagged now-playing info, the `application/x-dmap-tagged` SET_PARAMETER body of
 * RAOP and AirPlay 2: an `mlit` (listing item) container around the track's tags.
 * Receivers walk the tags inside the container, so it is not optional: shairport-sync
 * ignores bare tags.
 */
object Dmap {
    /**
     * The `mlit` block for one track. [itemId] (`mper`) tells receivers a new track from a
     * re-send of the current one; they tie artwork to it.
     */
    fun trackInfo(
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long?,
        itemId: Long
    ): ByteArray {
        val items = ByteArrayOutputStream()
        items.tag("mikd", byteArrayOf(ITEM_KIND_AUDIO.toByte()))
        items.tag("minm", title.orEmpty().toByteArray(Charsets.UTF_8))
        artist?.takeIf {
            it.isNotEmpty()
        }?.let { items.tag("asar", it.toByteArray(Charsets.UTF_8)) }
        album?.takeIf { it.isNotEmpty() }?.let { items.tag("asal", it.toByteArray(Charsets.UTF_8)) }
        items.tag("mper", ByteBuffer.allocate(Long.SIZE_BYTES).putLong(itemId).array())
        if (durationMs != null && durationMs > 0) {
            items.tag(
                "astm",
                ByteBuffer.allocate(Int.SIZE_BYTES).putInt(durationMs.toInt()).array()
            )
        }
        return ByteArrayOutputStream().apply { tag("mlit", items.toByteArray()) }.toByteArray()
    }

    private fun ByteArrayOutputStream.tag(tag: String, value: ByteArray) {
        require(tag.length == TAG_LENGTH) { "DMAP tags are 4 characters" }
        write(tag.toByteArray(Charsets.US_ASCII))
        write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
        write(value)
    }
}
