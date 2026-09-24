// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lucidreamer.LucidDreamerApp
import com.lucidreamer.R
import com.lucidreamer.core.EventLog
import com.lucidreamer.core.Notifications
import com.lucidreamer.data.db.entity.RealityCheckLogEntity
import com.lucidreamer.data.db.entity.ReminderProfileEntity
import com.lucidreamer.data.db.entity.ReminderStyle
import com.lucidreamer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Shows a reality-check reminder, and records the answer if the user gives one.
 *
 * The app supplies example prompts and imposes no particular technique - what
 * counts as a reality check is a matter of personal preference and some methods
 * contradict each other. Users can replace every prompt with their own text.
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REMIND = "com.lucidreamer.action.REMIND"
        const val ACTION_LOG_CHECK = "com.lucidreamer.action.LOG_CHECK"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_WAS_DREAMING = "was_dreaming"

        val DEFAULT_PROMPTS = listOf(
            "Am I dreaming?",
            "Check your hands. Count your fingers.",
            "Read this twice. Did it change?",
            "How did I get here?",
            "Is anything about this odd?",
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as LucidDreamerApp).container
        val pending = goAsync()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_REMIND -> remind(context, container, intent.getLongExtra(EXTRA_PROFILE_ID, -1L))
                    ACTION_LOG_CHECK -> logCheck(
                        container,
                        intent.getLongExtra(EXTRA_PROFILE_ID, -1L),
                        intent.getBooleanExtra(EXTRA_WAS_DREAMING, false),
                    ).also { NotificationManagerCompat.from(context).cancelAll() }
                }
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    private suspend fun remind(context: Context, container: com.lucidreamer.AppContainer, profileId: Long) {
        val profile = container.db.reminderDao().byId(profileId) ?: return

        val prompt = pickPrompt(profile)
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(prompt)
            .setContentText("Tap to log it, or just do the check.")
            .setAutoCancel(true)
            .setPriority(
                when (profile.style) {
                    ReminderStyle.SILENT -> NotificationCompat.PRIORITY_LOW
                    else -> NotificationCompat.PRIORITY_DEFAULT
                },
            )
            .setSilent(profile.style == ReminderStyle.SILENT)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    profileId.toInt(),
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_REALITY_CHECKS)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(0, "Did it", logAction(context, profileId, wasDreaming = false))
            .build()

        val shown = Notifications.notifyIfPermitted(
            context,
            Notifications.ID_REMINDER_BASE + profileId.toInt(),
            notification,
        )

        container.eventLog.d(
            EventLog.TAG_REMINDER,
            if (shown) "Reminder shown: \"$prompt\"" else "Reminder suppressed - notifications not permitted",
        )

        // The horizon is two days, so each reminder is also an opportunity to
        // extend it. Cheap, and means the schedule never quietly runs out.
        container.reminderScheduler.rescheduleAll("rolling horizon")
    }

    private fun logAction(context: Context, profileId: Long, wasDreaming: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (profileId * 10 + if (wasDreaming) 1 else 0).toInt() + 500_000,
            Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_LOG_CHECK
                putExtra(EXTRA_PROFILE_ID, profileId)
                putExtra(EXTRA_WAS_DREAMING, wasDreaming)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private suspend fun logCheck(container: com.lucidreamer.AppContainer, profileId: Long, wasDreaming: Boolean) {
        if (!container.settings.statisticsEnabled.first()) return
        container.db.reminderDao().logCheck(
            RealityCheckLogEntity(
                atMillis = System.currentTimeMillis(),
                profileId = profileId.takeIf { it >= 0 },
                performed = true,
                wasDreaming = wasDreaming,
            ),
        )
    }

    private fun pickPrompt(profile: ReminderProfileEntity): String {
        val prompts = runCatching {
            Json.decodeFromString(ListSerializer(String.serializer()), profile.messagesJson)
        }.getOrDefault(emptyList()).filter { it.isNotBlank() }.ifEmpty { DEFAULT_PROMPTS }

        return if (profile.randomiseMessages) {
            prompts[Random.nextInt(prompts.size)]
        } else {
            // Rotates through the list across the day rather than repeating one.
            prompts[((System.currentTimeMillis() / 60_000) % prompts.size).toInt()]
        }
    }
}
