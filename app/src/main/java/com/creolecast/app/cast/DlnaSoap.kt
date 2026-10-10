package com.creolecast.app.cast

import com.creolecast.app.TrackMetadata

/**
 * SOAP request bodies for UPnP AV renderers. The bytes are what renderers have been
 * verified against, so every builder reproduces them exactly.
 */
internal object DlnaSoap {
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    private const val ENVELOPE_OPEN =
        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">"""

    /**
     * The Stop sent before SetAVTransportURI. Unlike [action] it is indented XML spread
     * over several lines; renderers have been verified with exactly this whitespace.
     */
    val PRE_PLAY_STOP_BODY: String = listOf(
        ENVELOPE_OPEN,
        indented(24, "<s:Body>"),
        indented(28, """<u:Stop xmlns:u="$AV_TRANSPORT">"""),
        indented(32, "<InstanceID>0</InstanceID>"),
        indented(28, "</u:Stop>"),
        indented(24, "</s:Body>"),
        indented(20, "</s:Envelope>")
    ).joinToString("\n")

    /** A UPnP [action] on [service], wrapped in the SOAP envelope sent to DLNA renderers. */
    fun action(service: String, action: String, arguments: String): String = ENVELOPE_OPEN +
        "<s:Body>" +
        """<u:$action xmlns:u="$service">$arguments</u:$action>""" +
        "</s:Body></s:Envelope>"

    fun setAvTransportUri(streamUrl: String, metadataXml: String): String = action(
        AV_TRANSPORT,
        "SetAVTransportURI",
        "<InstanceID>0</InstanceID>" +
            "<CurrentURI>${escapeXml(streamUrl)}</CurrentURI>" +
            "<CurrentURIMetaData>${escapeXml(metadataXml)}</CurrentURIMetaData>"
    )

    /** DIDL-Lite item describing the live stream at [streamUrl] with the current track. */
    fun didlMetadata(streamUrl: String, metadata: TrackMetadata?): String {
        val title = metadata?.title ?: "CreoleCast Live Stream"
        val artist = metadata?.artist ?: "CreoleCast"
        val album = metadata?.album.orEmpty()
        val artwork = metadata?.artworkUrl.orEmpty()
        val artworkTag = if (artwork.isNotEmpty()) {
            "<upnp:albumArtURI>${escapeXml(artwork)}</upnp:albumArtURI>"
        } else {
            ""
        }
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="0" parentID="-1" restricted="1">""" +
            "<dc:title>${escapeXml(title)}</dc:title>" +
            "<upnp:artist>${escapeXml(artist)}</upnp:artist>" +
            "<upnp:album>${escapeXml(album)}</upnp:album>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            artworkTag +
            """<res protocolInfo="http-get:*:audio/x-wav:*">${escapeXml(streamUrl)}</res>""" +
            "</item></DIDL-Lite>"
    }

    private fun escapeXml(str: String): String = str.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun indented(spaces: Int, xml: String): String = " ".repeat(spaces) + xml
}
