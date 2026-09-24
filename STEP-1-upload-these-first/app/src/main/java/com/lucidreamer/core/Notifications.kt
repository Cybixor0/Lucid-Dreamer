// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Notification channels.
 *
 * Channel choice matters more than usual here because these notifications exist
 * while the user is asleep. The session channel is deliberately silent and
 * low-importance: it must be visible (it is what keeps the foreground service
 * alive and the app in the active standby bucket) without ever making a sound
 * of its own. The only thing allowed to make noise at night is a cue, played
 * deliberately through the audio engine.
 */
object Notifications {

    const val CHANNEL_SESSION = "session"
    const val CHANNEL_WBTB = "wbtb"
    const val CHANNEL_REMINDER = "reality_check"
    const val CHANNEL_PROBLEM = "problem"

    const val ID_SESSION = 1001
    const val ID_CUE_PLAYBACK = 1002
    const val ID_WBTB = 1003
    const val ID_PROBLEM = 1004
    const val ID_REMINDER_BASE = 2000

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SESSION,
                "Sleep session",
                // LOW: shown in the shade, never makes a sound or vibrates.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows that tonight's session is running, and the next scheduled cue. Silent."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WBTB,
                "Wake Back To Bed",
                // HIGH: this one is supposed to wake you. That is its entire job.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "The WBTB wake-up, and the journal prompt while you are awake."
                enableVibration(true)
                // Audio comes from the cue engine on the alarm stream, not from
                // the notification, so the channel itself stays silent.
                setSound(null, null)
            },
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDER,
                "Reality check reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Daytime prompts to perform a reality check."
                setShowBadge(false)
            },
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROBLEM,
                "Problems and diagnostics",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description =
                    "Tells you when something stopped the app working overnight, such as a " +
                        "battery restriction, so a failed night is never silent."
                setShowBadge(true)
            },
        )
    }

    fun enabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Posts a notification only if it is actually permitted, and never throws.
     *
     * Two separate things have to be true: the runtime POST_NOTIFICATIONS
     * permission must be granted on Android 13+, and notifications must not be
     * switched off for the app. Both are checked explicitly rather than assumed.
     *
     * The try/catch is not defensive padding - these calls happen in the middle
     * of the night from receivers and services, and a SecurityException
     * escaping here would take down the process that is running the session.
     * A missing notification is a small problem; a dead session is the whole
     * night.
     *
     * @return true if the notification was posted.
     */
    fun notifyIfPermitted(context: Context, id: Int, notification: android.app.Notification): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        if (!enabled(context)) return false

        return runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }.isSuccess
    }

    fun channelBlocked(context: Context, channelId: String): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = nm.getNotificationChannel(channelId) ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }
}
