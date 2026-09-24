// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.lucidreamer.core.EventLog
import com.lucidreamer.data.SettingsStore
import com.lucidreamer.data.db.LucidDatabase
import com.lucidreamer.data.db.entity.ReminderMode
import com.lucidreamer.data.db.entity.ReminderProfileEntity
import com.lucidreamer.data.db.entity.ScheduleProfileEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * Daytime reality-check reminders.
 *
 * Scheduled a day at a time with *inexact* windowed alarms, unlike cues. A
 * reality check does not need second precision - several modes randomise the
 * time on purpose - and inexact alarms let the system batch them with other
 * wake-ups, which matters when the alternative is a dozen exact alarms a day
 * for a feature running indefinitely.
 *
 * The times themselves are derived from a date-stable seed, so the schedule
 * does not reshuffle every time the app is opened, a reboot happens, or a
 * profile is edited.
 */
class ReminderScheduler(
    private val context: Context,
    private val db: LucidDatabase,
    private val settings: SettingsStore,
    private val log: EventLog,
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** How far ahead to plan. Re-planned daily by the first reminder of each day. */
    private val horizonDays = 2

    suspend fun rescheduleAll(reason: String) {
        cancelAll()
        if (!settings.realityChecksEnabled.first()) {
            log.d(EventLog.TAG_REMINDER, "Reality checks are off; nothing scheduled ($reason)")
            return
        }

        val profiles = db.reminderDao().enabled()
        if (profiles.isEmpty()) return

        val now = ZonedDateTime.now(zone)
        val armed = mutableListOf<Int>()

        for (dayOffset in 0 until horizonDays) {
            val date = now.toLocalDate().plusDays(dayOffset.toLong())
            for (profile in profiles) {
                if (!matchesDay(profile, date)) continue
                for ((index, at) in timesFor(profile, date).withIndex()) {
                    if (at.isBefore(now)) continue
                    arm(profile, date, index, at)
                    armed += requestCode(profile.id, date, index)
                }
            }
        }

        settings.setArmedReminderCodes(armed)
        log.i(EventLog.TAG_REMINDER, "Scheduled ${armed.size} reality-check reminder(s) ($reason)")
    }

    /**
     * Computes a profile's reminder times for one date.
     *
     * Pure apart from reading the profile, and seeded from (profile, date) so
     * the same day always produces the same times. Without that, every reboot
     * or edit would silently reshuffle the day's reminders.
     */
    fun timesFor(profile: ReminderProfileEntity, date: LocalDate): List<ZonedDateTime> {
        val start = minuteOfDay(profile.windowStartMinute)
        val end = minuteOfDay(profile.windowEndMinute)
        val windowMinutes = profile.windowEndMinute - profile.windowStartMinute
        if (windowMinutes <= 0) return emptyList()

        fun at(minute: Int): ZonedDateTime =
            ZonedDateTime.of(date, LocalTime.of((minute / 60).coerceIn(0, 23), minute % 60), zone)

        return when (profile.mode) {
            ReminderMode.FIXED -> runCatching {
                Json.decodeFromString(ListSerializer(Int.serializer()), profile.fixedTimesJson)
            }.getOrDefault(emptyList())
                .filter { it in 0 until 24 * 60 }
                .sorted()
                .map { at(it) }

            ReminderMode.INTERVAL -> {
                val step = profile.intervalMinutes.coerceAtLeast(5)
                generateSequence(profile.windowStartMinute) { it + step }
                    .takeWhile { it <= profile.windowEndMinute }
                    .take(48)
                    .map { at(it) }
                    .toList()
            }

            ReminderMode.RANDOM -> {
                val gap = profile.minGapMinutes.coerceAtLeast(1)
                val wanted = profile.count.coerceAtLeast(1)
                val fits = (windowMinutes / gap) + 1
                val n = minOf(wanted, fits)
                if (n <= 0) return emptyList()

                // Same construction as the cue planner: reserve the gaps, draw
                // sorted uniforms from what is left, then push each out by its
                // share. Guarantees the minimum spacing without rejection
                // sampling, which can fail to terminate on a tight window.
                val free = windowMinutes - (n - 1) * gap
                if (free < 0) return emptyList()
                val rnd = Random(profile.id * 31 + date.toEpochDay())
                val draws = IntArray(n) { if (free > 0) rnd.nextInt(free + 1) else 0 }
                draws.sort()
                (0 until n).map { i -> at(profile.windowStartMinute + draws[i] + i * gap) }
            }
        }.also { if (start.isAfter(end)) return emptyList() }
    }

    private fun minuteOfDay(minute: Int): LocalTime =
        LocalTime.of((minute / 60).coerceIn(0, 23), minute % 60)

    private fun matchesDay(profile: ReminderProfileEntity, date: LocalDate): Boolean =
        date.dayOfWeek in ScheduleProfileEntity.maskToDays(profile.daysMask)

    private fun arm(profile: ReminderProfileEntity, date: LocalDate, index: Int, at: ZonedDateTime) {
        val code = requestCode(profile.id, date, index)
        // Deliberately no data URI. PendingIntent identity is (requestCode,
        // Intent.filterEquals), and filterEquals includes the data - so if each
        // reminder had its own URI, cancelling later would need that exact URI
        // reconstructed, not just the code. Keeping the intent uniform and
        // distinguishing purely by requestCode makes cancellation reliable.
        // Extras are not part of filterEquals and are refreshed by
        // FLAG_UPDATE_CURRENT.
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_REMIND
            putExtra(ReminderReceiver.EXTRA_PROFILE_ID, profile.id)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                at.toInstant().toEpochMilli(),
                Duration.ofMinutes(5).toMillis(),
                pending,
            )
        }.onFailure { log.w(EventLog.TAG_REMINDER, "Could not arm reminder: ${it.message}") }
    }

    private fun requestCode(profileId: Long, date: LocalDate, index: Int): Int {
        var h = profileId * 31 + date.toEpochDay()
        h = h * 31 + index
        // Kept in a band of its own, clear of cue codes (which start at
        // 0x01000000) and of the small fixed codes in AlarmScheduler.
        return AlarmScheduler.RequestCodes.REMINDER_BASE + (h.toInt() and 0x000FFFFF)
    }

    /** Cancels exactly the reminders that were armed, using the remembered codes. */
    suspend fun cancelAll() {
        val codes = settings.armedReminderCodes.first()
        for (code in codes) {
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_REMIND
            }
            PendingIntent.getBroadcast(
                context,
                code,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }
        settings.setArmedReminderCodes(emptyList())
    }
}
