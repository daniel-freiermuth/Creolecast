package com.creolecast.app.cast

import android.util.Log
import com.creolecast.app.CastDestination
import com.creolecast.app.CastState
import com.creolecast.app.ExtraFields
import com.creolecast.app.TrackMetadata
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * DLNA/UPnP casts: points the renderer's AVTransport at our WAV stream
 * ([WavStreamServer]) and keeps its track metadata current.
 */
internal class DlnaController(private val ctx: CastContext) {
    /** Stops whatever the renderer plays, then sets our stream as its URI and plays it. */
    suspend fun start(dest: CastDestination) {
        val controlUrl = avTransportUrl(dest.extra) ?: return
        val streamUrl = localStreamUrl() ?: return
        httpExchange {
            // A renderer that is not playing may reject Stop; that must not stop the cast.
            httpExchange { post(controlUrl, "Stop", DlnaSoap.PRE_PLAY_STOP_BODY) }
                .onFailure { Log.d(LOG_TAG, "DLNA pre-play Stop failed: ${it.reason}") }

            val metadataXml = DlnaSoap.didlMetadata(streamUrl, ctx.metadata.value)
            post(
                controlUrl,
                "SetAVTransportURI",
                DlnaSoap.setAvTransportUri(streamUrl, metadataXml)
            )
            post(
                controlUrl,
                "Play",
                DlnaSoap.action(
                    DlnaSoap.AV_TRANSPORT,
                    "Play",
                    "<InstanceID>0</InstanceID><Speed>1</Speed>"
                )
            )
        }.onSuccess {
            ctx.markCasting()
        }.onFailure {
            Log.e(LOG_TAG, "DLNA session failed for ${dest.name}: ${it.reason}")
            ctx.state.value = CastState.ERROR
        }
    }

    suspend fun stop(dest: CastDestination) {
        val controlUrl = avTransportUrl(dest.extra) ?: return
        post(
            controlUrl,
            "Stop",
            DlnaSoap.action(DlnaSoap.AV_TRANSPORT, "Stop", "<InstanceID>0</InstanceID>")
        )
    }

    /** Re-sends the stream URI with DIDL-Lite metadata describing [metadata]. */
    suspend fun updateMetadata(dest: CastDestination, metadata: TrackMetadata) {
        val controlUrl = avTransportUrl(dest.extra) ?: return
        val streamUrl = localStreamUrl() ?: return
        val metadataXml = DlnaSoap.didlMetadata(streamUrl, metadata)
        httpExchange {
            post(
                controlUrl,
                "SetAVTransportURI",
                DlnaSoap.setAvTransportUri(streamUrl, metadataXml)
            )
        }.onFailure {
            Log.e(LOG_TAG, "Failed to update DLNA metadata: ${it.reason}")
        }
    }

    private suspend fun post(controlUrl: String, action: String, body: String) {
        ctx.client.post(controlUrl) {
            header("SoapAction", "\"${DlnaSoap.AV_TRANSPORT}#$action\"")
            contentType(ContentType.parse("text/xml; charset=utf-8"))
            setBody(body)
        }
    }

    /** The AVTransport control URL: [extra] is either that URL or ExtraFields with `av_control`. */
    private fun avTransportUrl(extra: String?): String? = when {
        extra == null -> null
        extra.startsWith("http") -> extra
        else -> ExtraFields.parse(extra)["av_control"]
    }

    private fun localStreamUrl(): String? =
        localIpv4Address()?.let { "http://$it:$STREAM_PORT/stream.wav" }
}
