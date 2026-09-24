// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lucidreamer.LucidDreamerApp
import com.lucidreamer.R
import com.lucidreamer.launchCatching
import com.lucidreamer.core.Notifications
import com.lucidreamer.data.db.entity.SessionEntity
import com.lucidreamer.data.db.entity.SessionState
import com.lucidreamer.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The ongoing session notification.
 *
 * It is the main control surface at 4am: reaching it does not require unlocking
 * the phone, finding the app or looking at a bright screen. It is also, on some
 * devices, part of what keeps the app in the active standby bucket, so it
 * stays visible for the whole session.
 *
 * The channel is silent and low-importance by design. The only thing permitted
 * to make a sound during the night is a cue, played deliberately.
 */
object SessionNotifications {

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    fun build(context: Context, session: SessionEntity?, nextCueAtMillis: Long?, cuesRemaining: Int): Notification {
        val zone = ZoneId.systemDefault()
        val state = session?.state ?: SessionState.ARMED

        val title = when (state) {
            SessionState.PAUSED -> context.getString(R.string.session_paused_title)
            SessionState.ARMED -> context.getString(R.string.session_armed_title)
            SessionState.WBTB_AWAKE -> context.getString(R.string.wbtb_title)
            else -> context.getString(R.string.session_title)
        }

        val text = when {
            state == SessionState.PAUSED -> "Cues are paused. Resume to continue tonight."
            state == SessionState.WBTB_AWAKE -> context.getString(R.string.wbtb_text)
            nextCueAtMillis != null -> {
                val at = timeFormat.format(Instant.ofEpochMilli(nextCueAtMillis).atZone(zone))
                val more = if (cuesRemaining > 1) " ($cuesRemaining left tonight)" else ""
                "Next cue at $at$more"
            }

            else -> context.getString(R.string.session_no_cues)
        }

        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // Visible on the lock screen: the whole point is reaching it
            // without unlocking, and it contains nothing private.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context))

        if (state == SessionState.WBTB_AWAKE) {
            builder.addAction(0, context.getString(R.string.action_back_to_bed), action(context, SessionControlReceiver.ACTION_BACK_TO_BED))
            builder.addAction(0, context.getString(R.string.action_log_dream), openApp(context, MainActivity.ROUTE_QUICK_DREAM))
        } else {
            if (state == SessionState.PAUSED) {
                builder.addAction(0, context.getString(R.string.action_resume), action(context, SessionControlReceiver.ACTION_RESUME))
            } else {
                builder.addAction(0, context.getString(R.string.action_pause), action(context, SessionControlReceiver.ACTION_PAUSE))
                builder.addAction(0, context.getString(R.string.action_skip), action(context, SessionControlReceiver.ACTION_SKIP_NEXT))
            }
            builder.addAction(0, context.getString(R.string.action_stop), action(context, SessionControlReceiver.ACTION_STOP))
        }

        return builder.build()
    }

    /** Re-renders the notification after something changed, e.g. a cue fired. */
    fun refresh(context: Context) {
        val app = context.applicationContext as? LucidDreamerApp ?: return
        app.container.applicationScope.launchCatching {
            val session = app.container.db.sessionDao().activeSession()
            if (session == null) {
                NotificationManagerCompat.from(context).cancel(Notifications.ID_SESSION)
                return@launchCatching
            }
            val remaining = app.container.db.cueEventDao().allArmed(session.id)
            val next = remaining.minByOrNull { it.scheduledAtMillis }?.scheduledAtMillis

            Notifications.notifyIfPermitted(
                context,
                Notifications.ID_SESSION,
                build(context, session, next, remaining.size),
            )
        }
    }

    private fun openApp(context: Context, route: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) }
        }
        return PendingIntent.getActivity(
            context,
            route?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun action(context: Context, actionName: String): PendingIntent {
        val intent = Intent(context, SessionControlReceiver::class.java).apply {
            action = actionName
            data = android.net.Uri.parse("lucid://control/$actionName")
        }
        return PendingIntent.getBroadcast(
            context,
            actionName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
