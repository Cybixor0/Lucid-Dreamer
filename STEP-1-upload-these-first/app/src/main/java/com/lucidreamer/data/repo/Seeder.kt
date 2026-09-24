// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.data.repo

import com.lucidreamer.data.SettingsStore
import com.lucidreamer.data.db.LucidDatabase
import com.lucidreamer.data.db.entity.CueProfileEntity
import com.lucidreamer.data.db.entity.ReminderMode
import com.lucidreamer.data.db.entity.ReminderProfileEntity
import com.lucidreamer.data.db.entity.ReminderStyle
import com.lucidreamer.data.db.entity.ScheduleProfileEntity
import com.lucidreamer.domain.cue.CuePresets
import com.lucidreamer.domain.cue.Technique
import com.lucidreamer.domain.schedule.DaySelector
import com.lucidreamer.domain.schedule.ScheduleProfile
import com.lucidreamer.domain.schedule.SleepAnchors
import com.lucidreamer.service.ReminderReceiver
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Populates the database on first run.
 *
 * Presets exist so a user who skips the whole of onboarding still ends up with
 * something coherent and safe rather than an empty app. They are ordinary rows,
 * editable and deletable like anything else - the `isPreset` flag only protects
 * them from accidental deletion, and a copy can always be made and changed.
 */
class Seeder(
    private val db: LucidDatabase,
    private val settings: SettingsStore,
) {
    suspend fun seedIfEmpty() {
        if (db.cueProfileDao().count() == 0) seedCueProfiles()
        if (db.reminderDao().all().isEmpty()) seedReminderProfile()
    }

    private suspend fun seedCueProfiles() {
        val ids = CuePresets.all().map { profile ->
            db.cueProfileDao().upsert(CueProfileEntity.from(profile, isPreset = true))
        }
        // The first preset is the gentlest one, and becomes the default.
        ids.firstOrNull()?.let { settings.setActiveCueProfileId(it) }
    }

    private suspend fun seedReminderProfile() {
        db.reminderDao().upsert(
            ReminderProfileEntity(
                name = "Daytime checks",
                // Created but switched off: reality checks are opt-in, and an
                // app that starts buzzing at someone unprompted gets uninstalled.
                enabled = false,
                daysMask = ScheduleProfileEntity.daysToMask(DaySelector.EveryDay.days),
                windowStartMinute = 9 * 60,
                windowEndMinute = 22 * 60,
                mode = ReminderMode.RANDOM,
                count = 6,
                minGapMinutes = 60,
                messagesJson = Json.encodeToString(
                    ListSerializer(String.serializer()),
                    ReminderReceiver.DEFAULT_PROMPTS,
                ),
                randomiseMessages = true,
                style = ReminderStyle.VIBRATE,
            ),
        )
    }

    /**
     * Applies the answers given during onboarding.
     *
     * Called at the end of setup, and again if the user changes their technique
     * later. Adds profiles rather than replacing them, so nothing a user has
     * already customised is destroyed.
     */
    suspend fun applyOnboarding(
        anchors: SleepAnchors,
        weekendAnchors: SleepAnchors?,
        technique: Technique,
        audioCues: Boolean,
        realityChecks: Boolean,
        manualStart: Boolean,
        wbtb: Boolean,
        sensorEstimation: Boolean,
    ) {
        settings.setDefaultAnchors(anchors)
        settings.setRequireManualStart(manualStart)
        settings.setAudioCuesEnabled(audioCues)
        settings.setRealityChecksEnabled(realityChecks)
        settings.setWbtbEnabled(wbtb)
        settings.setSensorEstimationRequested(sensorEstimation)
        settings.setDifferentAtWeekends(weekendAnchors != null)

        if (weekendAnchors != null) {
            // Attached to Friday and Saturday *nights*, because the lie-in
            // happens on Saturday and Sunday morning.
            db.scheduleProfileDao().upsert(
                ScheduleProfileEntity.from(
                    ScheduleProfile(
                        name = "Weekend nights",
                        selector = DaySelector.WeekendNights,
                        anchors = weekendAnchors,
                        priority = 10,
                    ),
                ),
            )
        }

        // Make the technique's most suitable preset the active one, if it is
        // already present; otherwise add it.
        val preferred = CuePresets.forTechnique(technique).firstOrNull() ?: return
        val existing = db.cueProfileDao().all().firstOrNull { it.name == preferred.name }
        val id = existing?.id ?: db.cueProfileDao().upsert(CueProfileEntity.from(preferred, isPreset = true))
        settings.setActiveCueProfileId(id)

        if (realityChecks) {
            db.reminderDao().all().firstOrNull()?.let {
                db.reminderDao().upsert(it.copy(enabled = true))
            }
        }
    }

    /** True when the user has never finished setup. */
    suspend fun needsOnboarding(): Boolean = !settings.onboardingComplete.first()
}
