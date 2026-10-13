package com.creolecast.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [readLocalArtwork], the scheme gate in front of the in-process read of a
 * media app's artwork URI.
 */
class MediaNotificationListenerArtworkTest {

    private val artwork = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    /** Returns [bytes] and counts how often it was asked to read. */
    private class RecordingReader(private val bytes: ByteArray?) {
        var calls = 0
        fun read(): ByteArray? {
            calls++
            return bytes
        }
    }

    private fun readWith(scheme: String?, reader: RecordingReader) =
        readLocalArtwork(scheme) { reader.read() }

    // --- Allowed schemes ---

    @Test
    fun `content scheme is read`() {
        val reader = RecordingReader(artwork)
        assertArrayEquals(artwork, readWith("content", reader))
        assertEquals(1, reader.calls)
    }

    @Test
    fun `android resource scheme is read`() {
        val reader = RecordingReader(artwork)
        assertArrayEquals(artwork, readWith("android.resource", reader))
        assertEquals(1, reader.calls)
    }

    @Test
    fun `provider returning no stream yields no artwork`() {
        val reader = RecordingReader(bytes = null)
        assertNull(readWith("content", reader))
        assertEquals(1, reader.calls)
    }

    // --- Rejected schemes: the reader must never run ---

    @Test
    fun `file scheme pointing at the secure prefs is never read`() {
        // file:///data/data/com.creolecast.app/shared_prefs/CreoleCastSecurePrefs.xml
        val reader = RecordingReader(artwork)
        assertNull(readWith("file", reader))
        assertEquals(0, reader.calls)
    }

    @Test
    fun `scheme-less path is never read`() {
        // "/data/data/com.creolecast.app/shared_prefs/..." parses with a null scheme.
        val reader = RecordingReader(artwork)
        assertNull(readWith(null, reader))
        assertEquals(0, reader.calls)
    }

    @Test
    fun `http and https URIs are passed through without being fetched`() {
        for (scheme in listOf("http", "https")) {
            val reader = RecordingReader(artwork)
            assertNull(scheme, readWith(scheme, reader))
            assertEquals(scheme, 0, reader.calls)
        }
    }

    @Test
    fun `unknown and case-variant schemes are never read`() {
        // ContentResolver matches schemes case-sensitively, so the gate does too.
        for (scheme in listOf("ftp", "jar", "FILE", "Content", "", "content ")) {
            val reader = RecordingReader(artwork)
            assertNull(scheme, readWith(scheme, reader))
            assertEquals(scheme, 0, reader.calls)
        }
    }
}
