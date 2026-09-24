// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.lucidreamer.data.db.ColumnJson
import com.lucidreamer.domain.cue.CueConstraints
import com.lucidreamer.domain.cue.CueProfile
import com.lucidreamer.domain.cue.CueRule
import com.lucidreamer.domain.schedule.DaySelector
import com.lucidreamer.domain.schedule.NightOverride
import com.lucidreamer.domain.schedule.ScheduleProfile
import com.lucidreamer.domain.schedule.SleepAnchors
import kotlinx.serialization.encodeToString
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Configuration is stored with its structured parts as JSON columns rather than
 * spread across a dozen relational tables.
 *
 * The rules are deeply nested sealed hierarchies that are always read and
 * written as a whole and never queried field-by-field, so normalising them
 * would buy nothing and cost a migration every time a timing variant gains an
 * option. Journal data, which genuinely is queried and filtered, is normalised
 * properly - see JournalEntities.kt.
 */

@Entity(tableName = "schedule_profiles")
data class ScheduleProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    /** Bitmask of [DayOfWeek.getValue] (Mon=1..Sun=7) as bits 1..7. */
    val daysMask: Int,
    val priority: Int = 0,
    val anchorsJson: String,
) {
    fun toDomain(): ScheduleProfile = ScheduleProfile(
        id = id,
        name = name,
        enabled = enabled,
        selector = DaySelector(maskToDays(daysMask)),
        anchors = ColumnJson.decodeFromString<SleepAnchors>(anchorsJson),
        priority = priority,
    )

    companion object {
        fun from(p: ScheduleProfile) = ScheduleProfileEntity(
            id = p.id,
            name = p.name,
            enabled = p.enabled,
            daysMask = daysToMask(p.selector.days),
            priority = p.priority,
            anchorsJson = ColumnJson.encodeToString(p.anchors),
        )

        fun daysToMask(days: Set<DayOfWeek>): Int = days.fold(0) { acc, d -> acc or (1 shl d.value) }

        fun maskToDays(mask: Int): Set<DayOfWeek> =
            DayOfWeek.entries.filterTo(mutableSetOf()) { (mask shr it.value) and 1 == 1 }
    }
}

@Entity(tableName = "night_overrides")
data class NightOverrideEntity(
    /** Epoch day of the night the user gets into bed. */
    @PrimaryKey val nightEpochDay: Long,
    val anchorsJson: String? = null,
    val cueProfileId: Long? = null,
    val enabled: Boolean = true,
    val note: String = "",
) {
    fun toDomain(): NightOverride = NightOverride(
        night = LocalDate.ofEpochDay(nightEpochDay),
        anchors = anchorsJson?.let { ColumnJson.decodeFromString<SleepAnchors>(it) },
        cueProfileId = cueProfileId,
        enabled = enabled,
        note = note,
    )

    companion object {
        fun from(o: NightOverride) = NightOverrideEntity(
            nightEpochDay = o.night.toEpochDay(),
            anchorsJson = o.anchors?.let { ColumnJson.encodeToString(it) },
            cueProfileId = o.cueProfileId,
            enabled = o.enabled,
            note = o.note,
        )
    }
}

@Entity(tableName = "cue_profiles")
data class CueProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val rulesJson: String,
    val constraintsJson: String,
    /** Built-in presets are protected from deletion but may be copied and edited. */
    val isPreset: Boolean = false,
) {
    fun toDomain(): CueProfile = CueProfile(
        id = id,
        name = name,
        description = description,
        rules = ColumnJson.decodeFromString<List<CueRule>>(rulesJson),
        constraints = ColumnJson.decodeFromString<CueConstraints>(constraintsJson),
    )

    companion object {
        fun from(p: CueProfile, isPreset: Boolean = false) = CueProfileEntity(
            id = p.id,
            name = p.name,
            description = p.description,
            rulesJson = ColumnJson.encodeToString(p.rules),
            constraintsJson = ColumnJson.encodeToString(p.constraints),
            isPreset = isPreset,
        )
    }
}
