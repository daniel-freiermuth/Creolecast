package com.creolecast.app.cast

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json

/** OkHttp socket connect/read/write timeout. */
private const val SOCKET_TIMEOUT_SECONDS = 15L
private const val MAX_CONCURRENT_REQUESTS = 64
private const val MAX_CONCURRENT_REQUESTS_PER_HOST = 16
private const val WEBSOCKET_PING_INTERVAL_MS = 5000L
private const val REQUEST_TIMEOUT_MS = 10000L

/** The HTTP/WebSocket client used for every receiver protocol that runs over HTTP. */
internal fun createCastHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            connectTimeout(SOCKET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            readTimeout(SOCKET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            writeTimeout(SOCKET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // The OkHttp engine sends WebSocket pings only from its own setting; it ignores
            // the WebSockets plugin's pingIntervalMillis.
            pingInterval(WEBSOCKET_PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
            val dispatcher = okhttp3.Dispatcher()
            dispatcher.maxRequests = MAX_CONCURRENT_REQUESTS
            dispatcher.maxRequestsPerHost = MAX_CONCURRENT_REQUESTS_PER_HOST
            dispatcher(dispatcher)
        }
    }
    install(WebSockets) {
        pingIntervalMillis = WEBSOCKET_PING_INTERVAL_MS
    }
    install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
        connectTimeoutMillis = REQUEST_TIMEOUT_MS
    }
    install(Logging) {
        logger = object : Logger {
            override fun log(message: String) {
                Log.i(LOG_TAG, "Ktor: $message")
            }
        }
        level = LogLevel.INFO
    }
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
        )
    }
}
