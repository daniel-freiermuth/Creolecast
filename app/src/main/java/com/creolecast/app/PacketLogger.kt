package com.creolecast.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class PacketDirection { IN, OUT }
enum class PacketType { AUDIO, CONTROL, METADATA, STATS, HANDSHAKE }

data class PacketLog(
    var timestamp: String,
    val direction: PacketDirection,
    val type: PacketType,
    val message: String,
    val payloadSize: Int = 0,
    var count: Int = 1
)

object PacketLogger {
    /** Oldest entries beyond this many are dropped. */
    private const val MAX_LOG_ENTRIES = 500

    private val _logs = mutableListOf<PacketLog>()
    val logs: List<PacketLog> get() = synchronized(_logs) { _logs.toList() }

    private val _logFlow = MutableSharedFlow<PacketLog>(extraBufferCapacity = 100)
    val logFlow = _logFlow.asSharedFlow()

    // SimpleDateFormat is not thread-safe; log() is called concurrently from multiple
    // Dispatchers.IO threads (one audio/control/stats session per cast destination),
    // so each thread needs its own formatter instance rather than sharing one.
    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }

    fun log(direction: PacketDirection, type: PacketType, message: String, payloadSize: Int = 0) {
        // initialValue() is overridden, so get() never returns null.
        val formatter = checkNotNull(dateFormat.get()) { "ThreadLocal formatter missing" }
        val timestamp = formatter.format(Date())
        val entry = PacketLog(
            timestamp = timestamp,
            direction = direction,
            type = type,
            message = message,
            payloadSize = payloadSize
        )

        synchronized(_logs) {
            val lastLog = _logs.firstOrNull()
            if (lastLog != null && lastLog.isRepeatOf(direction, type, message)) {
                lastLog.count++
                lastLog.timestamp = timestamp
                // We still emit it to let the UI know something happened,
                // but we need a way to distinguish between "new" and "update".
                // For simplicity, we'll let the adapter handle the merging logic
                // since it maintains its own list anyway.
            } else {
                _logs.add(0, entry)
                if (_logs.size > MAX_LOG_ENTRIES) _logs.removeAt(_logs.size - 1)
            }
        }
        _logFlow.tryEmit(entry)
    }

    private fun PacketLog.isRepeatOf(
        direction: PacketDirection,
        type: PacketType,
        message: String
    ): Boolean = this.direction == direction && this.type == type && this.message == message

    fun clear() {
        synchronized(_logs) {
            _logs.clear()
        }
    }
}
