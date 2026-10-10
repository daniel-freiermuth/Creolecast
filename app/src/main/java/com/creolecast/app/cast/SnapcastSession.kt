package com.creolecast.app.cast

import android.os.Build
import android.util.Log
import com.creolecast.app.CastDestination
import com.creolecast.app.CastState
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** First TCP port requested for our source; an explicit port makes it part of the URI. */
private const val FIRST_SOURCE_PORT = 4953

/** Give up creating the source after this many name or port collisions. */
private const val MAX_ADD_STREAM_ATTEMPTS = 5

/** Time for snapserver to bind the new TCP source port before we connect to it. */
private const val SOURCE_BIND_DELAY_MS = 500L
private const val CONNECT_TIMEOUT_MS = 5000

/**
 * One Snapcast cast to [dest]: creates a TCP source stream on snapserver over JSON-RPC,
 * switches every group to it and writes the captured PCM to it. When the cast ends the
 * groups get their previous streams back and the source is removed.
 */
internal class SnapcastSession(private val ctx: CastContext, private val dest: CastDestination) {
    private var streamId: String? = null

    /** groupId to the stream it played before we switched it to ours. */
    private var previousGroupStreams: Map<String, String>? = null

    suspend fun run() = withContext(ctx.ioDispatcher) {
        SnapcastRpc().use { rpc ->
            try {
                ctx.state.value = CastState.CONNECTING
                rpc.connect(dest.host, dest.port)
                val sourcePort = addStream(rpc)
                Log.d(LOG_TAG, "Snapcast stream '${streamId.orEmpty()}' on port $sourcePort")
                delay(SOURCE_BIND_DELAY_MS)
                streamTo(rpc, sourcePort)
            } catch (e: IOException) {
                fail(e)
            } catch (e: JSONException) {
                fail(e)
            } finally {
                restore(rpc)
            }
        }
    }

    /** Creates our TCP source, retrying with a new name and/or port on collisions. */
    private fun addStream(rpc: SnapcastRpc): Int {
        val baseName = Build.MODEL ?: "Android"
        var nameAttempt = 0
        var portAttempt = 0
        var createdPort: Int? = null
        while (createdPort == null) {
            val name = if (nameAttempt == 0) baseName else "$baseName (${nameAttempt + 1})"
            val port = FIRST_SOURCE_PORT + portAttempt
            val resp = rpc.call(
                "Stream.AddStream",
                JSONObject().apply {
                    put(
                        "streamUri",
                        "tcp://0.0.0.0:$port?name=$name&sampleformat=48000:16:2&mode=server"
                    )
                }
            )
            if (resp.has("error")) {
                val errData = resp.optJSONObject("error")?.optString("data", "").orEmpty()
                when {
                    errData.contains("already exists", ignoreCase = true) -> {
                        nameAttempt++
                        Log.d(LOG_TAG, "Snapcast: name '$name' taken, trying next name")
                    }

                    errData.contains("Address already in use", ignoreCase = true) ||
                        errData.contains("bind", ignoreCase = true) -> {
                        portAttempt++
                        Log.d(LOG_TAG, "Snapcast: port $port in use, trying next port")
                    }

                    else -> {
                        nameAttempt++
                        portAttempt++
                        Log.d(
                            LOG_TAG,
                            "Snapcast: '$name' on port $port failed ($errData), trying next"
                        )
                    }
                }
                val attemptsExhausted = nameAttempt >= MAX_ADD_STREAM_ATTEMPTS ||
                    portAttempt >= MAX_ADD_STREAM_ATTEMPTS
                if (attemptsExhausted) {
                    throw SnapcastException("Snapcast: failed to create stream: $errData")
                }
            } else {
                streamId = resp.getJSONObject("result").optString("id", name)
                createdPort = port
            }
        }
        return createdPort
    }

    private suspend fun streamTo(rpc: SnapcastRpc, sourcePort: Int) {
        Socket().use { audioSocket ->
            audioSocket.connect(InetSocketAddress(dest.host, sourcePort), CONNECT_TIMEOUT_MS)
            audioSocket.tcpNoDelay = true
            val audioOut = audioSocket.getOutputStream()

            ctx.markCasting()
            Log.d(
                LOG_TAG,
                "Snapcast streaming to ${dest.host}:$sourcePort (stream: ${streamId.orEmpty()})"
            )

            assignGroups(rpc)
            ctx.audio.collect { buffer -> audioOut.write(buffer) }
        }
    }

    /** Remembers each group's current stream, then switches every group to ours. */
    private fun assignGroups(rpc: SnapcastRpc) {
        try {
            val groups = rpc.call("Server.GetStatus").getJSONObject("result")
                .getJSONObject("server")
                .getJSONArray("groups")
            val saved = mutableMapOf<String, String>()
            for (i in 0 until groups.length()) {
                val group = groups.getJSONObject(i)
                val groupId = group.getString("id")
                saved[groupId] = group.getString("stream_id")
                rpc.call(
                    "Group.SetStream",
                    JSONObject().apply {
                        put("id", groupId)
                        put("stream_id", streamId)
                    }
                )
            }
            previousGroupStreams = saved
            Log.d(
                LOG_TAG,
                "Snapcast: assigned ${groups.length()} group(s), saved previous assignments"
            )
        } catch (e: IOException) {
            Log.w(LOG_TAG, "Snapcast: failed to assign groups: ${e.reason}")
        } catch (e: JSONException) {
            Log.w(LOG_TAG, "Snapcast: failed to assign groups: ${e.reason}")
        }
    }

    private fun fail(e: Exception) {
        Log.e(LOG_TAG, "Snapcast session failed for ${dest.name}: ${e.reason}")
        ctx.state.value = CastState.ERROR
    }

    /** Best effort: give the groups their previous streams back and remove our source. */
    private fun restore(rpc: SnapcastRpc) {
        if (!rpc.isOpen) return
        try {
            val previous = previousGroupStreams
            previous?.forEach { (groupId, previousStreamId) ->
                rpc.call(
                    "Group.SetStream",
                    JSONObject().apply {
                        put("id", groupId)
                        put("stream_id", previousStreamId)
                    }
                )
            }
            if (previous != null) Log.d(LOG_TAG, "Snapcast: restored previous group assignments")

            val ourStreamId = streamId
            if (ourStreamId != null) {
                rpc.call("Stream.RemoveStream", JSONObject().apply { put("id", ourStreamId) })
                Log.d(LOG_TAG, "Snapcast stream '$ourStreamId' removed")
            }
        } catch (e: IOException) {
            Log.d(LOG_TAG, "Snapcast: restoring groups failed: ${e.reason}")
        } catch (e: JSONException) {
            Log.d(LOG_TAG, "Snapcast: restoring groups failed: ${e.reason}")
        }
    }
}
