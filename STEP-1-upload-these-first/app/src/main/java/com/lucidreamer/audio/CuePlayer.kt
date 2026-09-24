// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.lucidreamer.data.db.entity.CueOutcome
import com.lucidreamer.domain.cue.CuePlayback
import com.lucidreamer.domain.cue.RouteFallback
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Plays one cue.
 *
 * ## Why every cue plays on the alarm stream
 *
 * Modern Android hardens background audio: an app without a visible activity or
 * while-in-use foreground service is blocked from touching audio at all, and
 * the failure is *silent* - playback simply does not happen, focus requests
 * just fail. The documented waiver is holding the exact-alarm permission and
 * acting on streams marked [AudioAttributes.USAGE_ALARM]. This app holds that
 * permission and uses that usage, which is the only combination that reliably
 * makes a sound from an alarm-triggered background service.
 *
 * That has a consequence worth being explicit about: subtlety cannot come from
 * choosing a quieter stream. It comes from [CuePlayback.volume], a per-player
 * gain applied to the samples themselves, calibrated per output route. The app
 * never calls `setStreamVolume` - it is ignored in the background on recent
 * Android, and it would trample the volume the user set for their real morning
 * alarm.
 */
class CuePlayer(private val context: Context) {

    /** Bluetooth link wake-up plus audio-path warm-up. See [PcmBuffer.withLeadIn]. */
    private val leadInMillis = 700

    data class Result(
        val outcome: CueOutcome,
        val route: RouteType,
        val routeName: String,
        val focusGranted: Boolean,
        val error: String? = null,
    )

    private val audioManager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * Plays [pcm] according to [playback], suspending until it has finished.
     *
     * Cancelling the calling coroutine stops playback promptly, which is how
     * "skip this cue" and "stop the session" work from the notification.
     */
    suspend fun play(pcm: PcmBuffer, playback: CuePlayback): Result {
        val status = AudioRoutes.current(context)

        if (playback.requireHeadphones && !status.route.isHeadphoneLike) {
            return when (playback.routeFallback) {
                RouteFallback.SKIP -> Result(CueOutcome.NO_AUDIO_ROUTE, status.route, status.deviceName, false)
                else -> playResolved(pcm, playback, status, gainScale = speakerFallbackScale(playback))
            }
        }

        if (status.dndMayBlockAlarms) {
            // Do Not Disturb set to total silence will swallow even an alarm.
            // Recorded distinctly so the morning report can say so rather than
            // leaving the user to guess why the night was quiet.
            return Result(CueOutcome.SILENCED_BY_DND, status.route, status.deviceName, false)
        }

        return playResolved(pcm, playback, status, gainScale = 1f)
    }

    private fun speakerFallbackScale(playback: CuePlayback): Float =
        if (playback.routeFallback == RouteFallback.SPEAKER_REDUCED) 0.35f else 1f

    private suspend fun playResolved(
        pcm: PcmBuffer,
        playback: CuePlayback,
        status: AudioRouteStatus,
        gainScale: Float,
    ): Result {
        var focusRequest: AudioFocusRequest? = null
        var focusGranted = false

        if (playback.requestAudioFocus) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(false)
                .build()
            focusGranted = runCatching {
                audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }.getOrDefault(false)
            // Deliberately not a reason to stay silent. A one-second cue that
            // gets ducked is far better than no cue, and MAY_DUCK (rather than
            // GAIN) means the user's white-noise app dips for a moment instead
            // of being muted for the rest of the night.
        }

        val prepared = pcm
            .withFades(playback.fadeIn.toMillis().toInt(), playback.fadeOut.toMillis().toInt())
            .let { if (playback.maxDuration != null) it.limitTo(playback.maxDuration.toMillis()) else it }
            .withGain((playback.volume * gainScale).coerceIn(0f, 1f))
            .withLeadIn(leadInMillis)

        return try {
            var lastError: String? = null
            var played = false

            repeat(playback.repeatCount.coerceAtLeast(1)) { i ->
                if (!coroutineContext.isActive) return@repeat
                if (i > 0) delay(playback.repeatGap.toMillis())
                if (playback.vibrate) vibrate()

                when (val r = playOnce(prepared)) {
                    null -> played = true
                    else -> lastError = r
                }
            }

            when {
                played -> Result(CueOutcome.PLAYED, status.route, status.deviceName, focusGranted)
                else -> Result(CueOutcome.PLAYBACK_ERROR, status.route, status.deviceName, focusGranted, lastError)
            }
        } finally {
            focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        }
    }

    /**
     * One pass through the buffer.
     *
     * A fresh [AudioTrack] in static mode, written once and released
     * afterwards. Nothing is retained between cues: no codec, no prepared
     * player, no native object that has to survive six hours of idle, an
     * audio-server restart and a route change.
     *
     * @return null on success, or a description of the failure.
     */
    private suspend fun playOnce(pcm: PcmBuffer): String? {
        val channelMask =
            if (pcm.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(pcm.sampleRate)
            .setChannelMask(channelMask)
            .build()

        val byteCount = pcm.samples.size * 2
        var track: AudioTrack? = null

        return try {
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(byteCount)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            if (track.state != AudioTrack.STATE_NO_STATIC_DATA && track.state != AudioTrack.STATE_INITIALIZED) {
                return "AudioTrack failed to initialise (state=${track.state})"
            }

            val written = track.write(pcm.samples, 0, pcm.samples.size)
            if (written < 0) return "AudioTrack.write failed ($written)"

            track.play()

            // Poll the playback head rather than sleeping blind for the buffer
            // duration: it is how we know a sound actually came out, which is
            // the difference between a recorded PLAYED and a hopeful one.
            val totalFrames = pcm.frameCount
            val timeoutAt = System.currentTimeMillis() + pcm.durationMillis + 2000
            var advanced = false

            while (coroutineContext.isActive) {
                val head = runCatching { track.playbackHeadPosition }.getOrDefault(0)
                if (head > 0) advanced = true
                if (head >= totalFrames) break
                if (System.currentTimeMillis() > timeoutAt) break
                delay(50)
            }

            if (!advanced) "Playback head never advanced - audio was not delivered" else null
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
        }
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (!vibrator.hasVibrator()) return
        // Gentle double pulse rather than a long buzz - the point is to reach a
        // dreaming mind, not to jolt someone out of bed.
        runCatching {
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 120, 180, 120), intArrayOf(0, 90, 0, 90), -1),
            )
        }
    }
}
