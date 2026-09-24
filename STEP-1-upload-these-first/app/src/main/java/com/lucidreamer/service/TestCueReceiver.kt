// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.lucidreamer.LucidDreamerApp
import com.lucidreamer.core.EventLog
import com.lucidreamer.data.db.ColumnJson
import com.lucidreamer.domain.cue.BuiltInTone
import com.lucidreamer.domain.cue.CuePlayback
import com.lucidreamer.domain.cue.CueSound
import kotlinx.serialization.encodeToString
import java.time.Duration

/**
 * Delivers the "test cue in 2 minutes" check.
 *
 * Identical in structure to the real cue path - alarm, then a foreground
 * service for playback - so passing this test genuinely says something about
 * whether the real thing will work on this device.
 */
class TestCueReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TEST = "com.lucidreamer.action.TEST_ALARM"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TEST) return

        val container = (context.applicationContext as LucidDreamerApp).container
        container.eventLog.i(EventLog.TAG_ALARM, "Test cue alarm delivered")

        val sound: CueSound = CueSound.BuiltIn(BuiltInTone.CHIME)
        val playback = CuePlayback(
            volume = 0.5f,
            fadeIn = Duration.ofMillis(300),
            fadeOut = Duration.ofMillis(500),
            repeatCount = 1,
            vibrate = true,
        )

        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CuePlaybackService::class.java).apply {
                    action = CuePlaybackService.ACTION_TEST_CUE
                    putExtra(CuePlaybackService.EXTRA_SOUND_JSON, ColumnJson.encodeToString(sound))
                    putExtra(CuePlaybackService.EXTRA_PLAYBACK_JSON, ColumnJson.encodeToString(playback))
                },
            )
        }.onFailure {
            container.eventLog.e(EventLog.TAG_ALARM, "Test cue could not start playback", it)
        }
    }
}
