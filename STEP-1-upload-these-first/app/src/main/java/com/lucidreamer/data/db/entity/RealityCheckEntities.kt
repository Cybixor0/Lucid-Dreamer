// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** How reminder times are chosen within the profile's window. */
enum class ReminderMode {
    /** Specific times the user typed. */
    FIXED,

    /** N random times per day, at least minGap apart. */
    RANDOM,

    /** Every N minutes across the window. */
    INTERVAL,
}

enum class ReminderStyle { SILENT, VIBRATE, SOUND }

/**
 * A daytime reality-check reminder schedule.
 *
 * Several may be active at once - random checks through the day plus a fixed
 * one at a habitual trigger, say - and the app supplies example prompts without
 * imposing any particular reality-check technique.
 */
@Entity(tableName = "reminder_profiles")
data class ReminderProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val daysMask: Int,

    /** Minutes from midnight, local time. */
    val windowStartMinute: Int,
    val windowEndMinute: Int,

    val mode: ReminderMode,
    /** RANDOM: how many per day. */
    val count: Int = 6,
    /** INTERVAL: minutes between reminders. */
    val intervalMinutes: Int = 90,
    /** RANDOM: minimum minutes between reminders. */
    val minGapMinutes: Int = 45,
    /** FIXED: JSON list of minutes-from-midnight. */
    val fixedTimesJson: String = "[]",

    /** JSON list of prompts, rotated or picked at random. */
    val messagesJson: String,
    val randomiseMessages: Boolean = true,
    val style: ReminderStyle = ReminderStyle.VIBRATE,
)

/**
 * A logged reality check. Optional - the user can leave statistics off
 * entirely - but when enabled it turns "am I actually doing these?" from a
 * feeling into a number.
 */
@Entity(tableName = "reality_check_log", indices = [Index("atMillis")])
data class RealityCheckLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMillis: Long,
    val profileId: Long?,
    /** True if the user confirmed they performed the check. */
    val performed: Boolean,
    /** Recorded honestly: people do occasionally answer "yes". */
    val wasDreaming: Boolean = false,
    val note: String = "",
)
