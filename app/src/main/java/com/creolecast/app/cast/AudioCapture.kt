package com.creolecast.app.cast

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import com.creolecast.app.AudioCastService
import com.creolecast.app.PacketDirection
import com.creolecast.app.PacketLogger
import com.creolecast.app.PacketType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val MAX_INIT_ATTEMPTS = 3
private const val INIT_RETRY_DELAY_MS = 400L

/** Recorder buffer: at least four capture frames. */
private const val BUFFERED_FRAMES = 4

/**
 * Captures what other apps play (AudioPlaybackCapture via the cast's MediaProjection) as
 * 16-bit stereo PCM and publishes it in [AudioCastService.FRAME_SIZE] chunks on
 * [CastContext.audio].
 */
internal class AudioCapture(private val ctx: CastContext) {
    private var audioRecord: AudioRecord? = null

    /** Opens the recorder at [sampleRate], retrying a few times; false if it never initialises. */
    suspend fun open(projection: MediaProjection, sampleRate: Int): Boolean {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        var attempt = 0
        var recorder: AudioRecord? = null
        while (attempt < MAX_INIT_ATTEMPTS && recorder == null &&
            currentCoroutineContext().isActive
        ) {
            if (attempt > 0) delay(INIT_RETRY_DELAY_MS)
            attempt++
            recorder = tryCreate(config, sampleRate, attempt)
        }
        if (recorder == null) {
            Log.e(LOG_TAG, "Failed to initialize AudioRecord after $attempt attempts")
            PacketLogger.log(
                PacketDirection.IN,
                PacketType.AUDIO,
                "AudioRecord failed to initialize after $attempt attempts — " +
                    "check Microphone permission for CreoleCast"
            )
        }
        audioRecord = recorder
        return recorder != null
    }

    /** Records until cancelled or the recorder fails, emitting each full frame. */
    suspend fun pump() {
        try {
            audioRecord?.startRecording()
            PacketLogger.log(PacketDirection.IN, PacketType.AUDIO, "AudioRecord capture started")
            readFrames()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            Log.e(LOG_TAG, "Audio recording loop failed", e)
            PacketLogger.log(
                PacketDirection.IN,
                PacketType.AUDIO,
                "Audio recording loop crashed: ${e.reason}"
            )
        }
    }

    fun release() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: IllegalStateException) {
            Log.d(LOG_TAG, "AudioRecord was not recording: ${e.reason}")
        }
        audioRecord = null
    }

    // RECORD_AUDIO is requested by MainActivity before it lets a cast start; without it
    // build() throws, which is handled below like any other failed attempt. The catches
    // are what AudioRecord.Builder.build() and the audio-policy registration behind
    // playback capture throw for unsupported formats, revoked projections and denials.
    @SuppressLint("MissingPermission")
    private fun tryCreate(
        config: AudioPlaybackCaptureConfiguration,
        sampleRate: Int,
        attempt: Int
    ): AudioRecord? = try {
        val minBufSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
            )
            .setAudioPlaybackCaptureConfig(config)
            .setBufferSizeInBytes(
                (AudioCastService.FRAME_SIZE * BUFFERED_FRAMES).coerceAtLeast(minBufSize)
            )
            .build()
        if (recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder
        } else {
            Log.e(LOG_TAG, "AudioRecord not initialized, attempt $attempt")
            PacketLogger.log(
                PacketDirection.IN,
                PacketType.AUDIO,
                "AudioRecord not initialized (attempt $attempt)"
            )
            recorder.release()
            null
        }
    } catch (e: UnsupportedOperationException) {
        logInitFailure(attempt, e)
    } catch (e: IllegalArgumentException) {
        logInitFailure(attempt, e)
    } catch (e: SecurityException) {
        logInitFailure(attempt, e)
    } catch (e: IllegalStateException) {
        logInitFailure(attempt, e)
    }

    private fun logInitFailure(attempt: Int, e: RuntimeException): AudioRecord? {
        Log.e(LOG_TAG, "AudioRecord initialization attempt $attempt failed: ${e.reason}")
        PacketLogger.log(
            PacketDirection.IN,
            PacketType.AUDIO,
            "AudioRecord init failed (attempt $attempt): ${e.reason}"
        )
        return null
    }

    private suspend fun readFrames() {
        val frameSize = AudioCastService.FRAME_SIZE
        val buffer = ByteArray(frameSize)
        var lastFrameWasSilent: Boolean? = null
        var readResult = 0
        while (currentCoroutineContext().isActive && readResult >= 0) {
            readResult = audioRecord?.read(buffer, 0, frameSize) ?: 0
            if (readResult == frameSize) {
                val frame = buffer.copyOf()
                val isSilent = frame.all { it == 0.toByte() }
                if (isSilent != lastFrameWasSilent) {
                    lastFrameWasSilent = isSilent
                    logSignalChange(isSilent)
                }
                ctx.audio.emit(frame)
            } else if (readResult < 0) {
                Log.e(LOG_TAG, "AudioRecord read error: $readResult")
                PacketLogger.log(
                    PacketDirection.IN,
                    PacketType.AUDIO,
                    "AudioRecord read error: $readResult"
                )
            }
        }
    }

    private fun logSignalChange(isSilent: Boolean) {
        PacketLogger.log(
            PacketDirection.IN,
            PacketType.AUDIO,
            if (isSilent) {
                "Capture is silent — no audio signal detected " +
                    "(check DRM/FLAG_SECURE source, or that something is actually playing)"
            } else {
                "Capture is producing a real audio signal"
            }
        )
    }
}
