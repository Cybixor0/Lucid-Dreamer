// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lucidreamer.LucidDreamerApp
import com.lucidreamer.R
import com.lucidreamer.core.EventLog
import com.lucidreamer.core.Notifications
import com.lucidreamer.data.db.ColumnJson
import com.lucidreamer.data.db.entity.SessionState
import com.lucidreamer.domain.cue.CuePlayback
import com.lucidreamer.domain.cue.CueSound
import com.lucidreamer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.time.Duration

/**
 * Wake Back To Bed.
 *
 * Two alarms: one to wake the user, one as a backstop in case they fall asleep
 * again without confirming. The wake-up is intentionally the loudest thing the
 * app ever does - unlike a cue, it is supposed to wake you - and it deliberately
 * uses the same alarm-stream playback path as everything else, because that is
 * the only path guaranteed to make a sound from the background.
 */
class WbtbReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_WBTB_WAKE = "com.lucidreamer.action.WBTB_WAKE"
        const val ACTION_WBTB_END = "com.lucidreamer.action.WBTB_END"
        const val EXTRA_SESSION_ID = "session_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (sessionId < 0) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val container = (context.applicationContext as LucidDreamerApp).container
                when (action) {
                    ACTION_WBTB_WAKE -> wake(context, container, sessionId)
                    ACTION_WBTB_END -> endAwakeWindow(context, container, sessionId)
                }
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    private suspend fun wake(context: Context, container: com.lucidreamer.AppContainer, sessionId: Long) {
        val config = container.settings.wbtbConfig.first()
        container.eventLog.i(EventLog.TAG_WBTB, "WBTB wake-up", sessionId)

        container.db.sessionDao().setState(sessionId, SessionState.WBTB_AWAKE)

        // Routed through the normal playback service so it inherits the
        // alarm-stream path, the foreground-service requirement and the outcome
        // logging that make audio actually happen in the background.
        playWakeSound(context, config.wakeSound, config.wakeVolume, config.wakeRepeatCount)

        if (config.speakInstructions && config.spokenInstructions.isNotBlank()) {
            playWakeSound(context, CueSound.Speech(config.spokenInstructions), config.wakeVolume, 1)
        }

        postWakeNotification(context, config.journalPrompt)
        SessionNotifications.refresh(context)

        // Backstop: if the user never taps "back to bed" - most likely because
        // they fell asleep, which is a success, not a failure - cues resume on
        // their own rather than the night quietly doing nothing.
        val session = container.db.sessionDao().byId(sessionId) ?: return
        val resumeAt = System.currentTimeMillis() + backstopDelay(config).toMillis()
        if (resumeAt < session.wakeAtMillis) {
            container.alarmScheduler.armWbtbEnd(resumeAt, sessionId)
        }
    }

    private fun backstopDelay(config: com.lucidreamer.domain.wbtb.WbtbConfig): Duration =
        when (config.returnMode) {
            com.lucidreamer.domain.wbtb.WbtbReturnMode.AUTOMATIC -> config.awakeDuration
            com.lucidreamer.domain.wbtb.WbtbReturnMode.CONFIRMED -> config.fallbackAfter
        }

    private suspend fun endAwakeWindow(context: Context, container: com.lucidreamer.AppContainer, sessionId: Long) {
        val session = container.db.sessionDao().byId(sessionId) ?: return
        if (session.wbtbReturnAtMillis != null) return // the user already confirmed

        container.eventLog.i(
            EventLog.TAG_WBTB,
            "Awake window elapsed with no confirmation; resuming cues anyway",
            sessionId,
        )
        NotificationManagerCompat.from(context).cancel(Notifications.ID_WBTB)
        container.sessionManager.wbtbBackToBed()
        SessionNotifications.refresh(context)
    }

    private fun playWakeSound(context: Context, sound: CueSound, volume: Float, repeats: Int) {
        val playback = CuePlayback(
            volume = volume.coerceIn(0f, 1f),
            fadeIn = Duration.ofMillis(400),
            fadeOut = Duration.ofMillis(400),
            repeatCount = repeats.coerceAtLeast(1),
            repeatGap = Duration.ofSeconds(8),
            vibrate = true,
            requireHeadphones = false,
            requestAudioFocus = true,
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
        }
    }

    private fun postWakeNotification(context: Context, journalPrompt: Boolean) {
        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_WBTB)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.wbtb_title))
            .setContentText(context.getString(R.string.wbtb_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .addAction(
                0,
                context.getString(R.string.action_back_to_bed),
                PendingIntent.getBroadcast(
                    context,
                    SessionControlReceiver.ACTION_BACK_TO_BED.hashCode(),
                    Intent(context, SessionControlReceiver::class.java).apply {
                        action = SessionControlReceiver.ACTION_BACK_TO_BED
                        data = android.net.Uri.parse("lucid://control/back_to_bed")
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

        if (journalPrompt) {
            builder.addAction(
                0,
                context.getString(R.string.action_log_dream),
                PendingIntent.getActivity(
                    context,
                    MainActivity.ROUTE_QUICK_DREAM.hashCode(),
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_QUICK_DREAM)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }

        Notifications.notifyIfPermitted(context, Notifications.ID_WBTB, builder.build())
    }
}
