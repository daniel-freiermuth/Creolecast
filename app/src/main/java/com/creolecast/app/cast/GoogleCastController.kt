package com.creolecast.app.cast

import android.util.Log
import com.creolecast.app.CastDestination
import com.creolecast.app.CastState
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URLEncoder

/** DIAL REST service port of Google Cast devices. */
private const val DIAL_PORT = 8008
private const val LAUNCH_TIMEOUT_MS = 5000L

/** DIAL app ids/names tried in order to play our stream URL. */
private val DIAL_APP_IDS = listOf("DefaultMediaPlayer", "YouTube", "ChromeCast", "CC1AD845")

/** Own-tone style mirroring receiver, the last resort when no standard app launches. */
private const val MIRRORING_APP_ID = "0F5096C8"

/**
 * Google Cast over DIAL: launches a receiver app with our WAV stream ([WavStreamServer])
 * as its media URL. DIAL has no volume or metadata channel; that would need the full
 * CastV2 protocol on port 8009.
 */
internal class GoogleCastController(private val ctx: CastContext) {
    suspend fun start(dest: CastDestination) {
        val myIp = localIpv4Address() ?: return
        val encodedUrl = URLEncoder.encode("http://$myIp:$STREAM_PORT/stream.wav", Charsets.UTF_8)
        ctx.state.value = CastState.CONNECTING
        logInstalledApps(dest)
        val launched = DIAL_APP_IDS.any { appId -> launchApp(dest, appId, encodedUrl) } ||
            launchMirroringApp(dest, encodedUrl)
        if (launched) {
            ctx.markCasting()
        } else {
            Log.e(LOG_TAG, "Google Cast launch failed for all attempted apps on ${dest.name}")
            ctx.state.value = CastState.ERROR
        }
    }

    suspend fun stop(dest: CastDestination) {
        ctx.client.delete("http://${dest.host}:$DIAL_PORT/apps/DefaultMediaPlayer")
    }

    /** Logs the device's DIAL apps, which helps debugging launch 404s. */
    private suspend fun logInstalledApps(dest: CastDestination) {
        httpExchange { ctx.client.get("http://${dest.host}:$DIAL_PORT/apps").bodyAsText() }
            .onSuccess { Log.d(LOG_TAG, "Apps list on ${dest.name}: $it") }
            .onFailure { Log.d(LOG_TAG, "Could not fetch apps list: ${it.reason}") }
    }

    /** Receivers differ in the form field they read the media URL from: `url` or `v`. */
    private suspend fun launchApp(dest: CastDestination, appId: String, encodedUrl: String) =
        httpExchange {
            listOf("url=$encodedUrl", "v=$encodedUrl").any { payload ->
                val response = ctx.client.post("http://${dest.host}:$DIAL_PORT/apps/$appId") {
                    setBody(payload)
                    contentType(ContentType.Application.FormUrlEncoded)
                    timeout { requestTimeoutMillis = LAUNCH_TIMEOUT_MS }
                }
                response.status.isSuccess().also { launched ->
                    if (launched) {
                        Log.d(
                            LOG_TAG,
                            "Successfully launched Google Cast app: $appId with payload: $payload"
                        )
                    }
                }
            }
        }.getOrElse {
            Log.d(LOG_TAG, "Failed to launch $appId: ${it.reason}")
            false
        }

    private suspend fun launchMirroringApp(dest: CastDestination, encodedUrl: String) =
        httpExchange {
            ctx.client.post("http://${dest.host}:$DIAL_PORT/apps/$MIRRORING_APP_ID") {
                setBody("url=$encodedUrl")
                contentType(ContentType.Application.FormUrlEncoded)
            }.status.isSuccess()
        }.getOrElse {
            Log.d(LOG_TAG, "Mirroring app launch failed: ${it.reason}")
            false
        }
}
