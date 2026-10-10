package com.creolecast.app.cast

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A minimal TCP server on a fixed [port] that receivers connect to: accepts on
 * [CastContext.ioDispatcher] and serves each connection with [respond] in its own
 * coroutine, closing the connection afterwards.
 */
internal class LocalSocketServer(
    private val name: String,
    private val port: Int,
    private val ctx: CastContext,
    private val respond: suspend (Socket) -> Unit
) {
    private var job: Job? = null
    private var serverSocket: ServerSocket? = null

    /** (Re)starts the server; a running instance is stopped first. */
    fun start() {
        stop()
        job = ctx.scope.launch(ctx.ioDispatcher) {
            try {
                ServerSocket().use { server -> acceptConnections(server) }
            } catch (e: IOException) {
                Log.e(LOG_TAG, "Could not run $name server on port $port: ${e.reason}")
            } finally {
                Log.i(LOG_TAG, "$name server stopped")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        // accept() blocks rather than suspends; closing the socket is what releases it.
        closeQuietly(serverSocket, "$name server socket")
        serverSocket = null
    }

    private fun CoroutineScope.acceptConnections(server: ServerSocket) {
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        serverSocket = server
        Log.i(LOG_TAG, "$name server started on port $port")
        while (isActive) {
            // Closed (quietly, so a failing close cannot crash the server) by the per-client
            // coroutine below; `use` would close it as soon as the loop iteration ended.
            @Suppress("MissingUseCall")
            val client = acceptOrNull(server)
            if (client != null) {
                launch {
                    try {
                        respond(client)
                    } finally {
                        closeQuietly(client, "$name client socket")
                    }
                }
            }
        }
    }

    private fun acceptOrNull(server: ServerSocket): Socket? = try {
        server.accept()
    } catch (e: IOException) {
        Log.d(LOG_TAG, "$name server accept failed: ${e.reason}")
        null
    }
}
