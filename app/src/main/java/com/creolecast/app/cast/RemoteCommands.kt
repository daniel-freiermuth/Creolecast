package com.creolecast.app.cast

import com.creolecast.app.MediaCommand
import java.util.Locale

/**
 * The phone-player command a receiver's remote-control request names: DACP paths
 * (`playpause`, `nextitem`, ...), AirPlay 2 event commands and our own command names.
 */
internal fun mediaCommandFor(name: String): MediaCommand? = when (name.lowercase(Locale.US)) {
    "play", "playresume" -> MediaCommand.PLAY
    "pause" -> MediaCommand.PAUSE
    "playpause", "toggleplaypause", "toggle" -> MediaCommand.TOGGLE
    "nextitem", "nexttrack", "next" -> MediaCommand.NEXT
    "previtem", "previoustrack", "previous" -> MediaCommand.PREVIOUS
    "stop" -> MediaCommand.STOP
    else -> null
}
