// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.checks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucidreamer.AppContainer
import com.lucidreamer.data.db.dao.TagFrequency
import com.lucidreamer.data.db.entity.RealityCheckLogEntity
import com.lucidreamer.data.db.entity.ReminderMode
import com.lucidreamer.data.db.entity.ReminderProfileEntity
import com.lucidreamer.data.db.entity.ReminderStyle
import com.lucidreamer.data.db.entity.ScheduleProfileEntity
import com.lucidreamer.domain.schedule.DaySelector
import com.lucidreamer.service.ReminderReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.DayOfWeek

data class RealityCheckState(
    val profiles: List<ReminderProfileEntity> = emptyList(),
    val topDreamSigns: List<TagFrequency> = emptyList(),
    val recentChecks: List<RealityCheckLogEntity> = emptyList(),
)

class RealityCheckViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(RealityCheckState())
    val state: StateFlow<RealityCheckState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = RealityCheckState(
                profiles = container.db.reminderDao().all(),
                topDreamSigns = container.journalRepository.topDreamSigns(),
                recentChecks = container.db.reminderDao().observeRecentChecks().first(),
            )
        }
    }

    /**
     * Any edit re-plans the reminders, because they are armed a day or two
     * ahead rather than evaluated on demand.
     */
    private fun save(profile: ReminderProfileEntity) {
        viewModelScope.launch {
            container.db.reminderDao().upsert(profile)
            container.reminderScheduler.rescheduleAll("reminder edited")
            refresh()
        }
    }

    fun update(id: Long, transform: (ReminderProfileEntity) -> ReminderProfileEntity) {
        val profile = _state.value.profiles.firstOrNull { it.id == id } ?: return
        save(transform(profile))
    }

    fun addProfile() {
        viewModelScope.launch {
            container.db.reminderDao().upsert(
                ReminderProfileEntity(
                    name = "New reminders",
                    enabled = true,
                    daysMask = ScheduleProfileEntity.daysToMask(DaySelector.EveryDay.days),
                    windowStartMinute = 9 * 60,
                    windowEndMinute = 22 * 60,
                    mode = ReminderMode.RANDOM,
                    count = 5,
                    minGapMinutes = 60,
                    messagesJson = Json.encodeToString(
                        ListSerializer(String.serializer()),
                        ReminderReceiver.DEFAULT_PROMPTS,
                    ),
                    style = ReminderStyle.VIBRATE,
                ),
            )
            container.reminderScheduler.rescheduleAll("reminder added")
            refresh()
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            _state.value.profiles.firstOrNull { it.id == id }?.let {
                container.db.reminderDao().delete(it)
            }
            container.reminderScheduler.rescheduleAll("reminder deleted")
            refresh()
        }
    }

    fun hasDay(profile: ReminderProfileEntity, day: DayOfWeek): Boolean =
        day in ScheduleProfileEntity.maskToDays(profile.daysMask)

    fun toggleDay(id: Long, day: DayOfWeek) = update(id) { profile ->
        val days = ScheduleProfileEntity.maskToDays(profile.daysMask).toMutableSet()
        if (!days.remove(day)) days.add(day)
        profile.copy(daysMask = ScheduleProfileEntity.daysToMask(days))
    }

    fun promptsText(profile: ReminderProfileEntity): String =
        decodePrompts(profile).joinToString("\n")

    fun setPrompts(id: Long, text: String) = update(id) { profile ->
        profile.copy(
            messagesJson = Json.encodeToString(
                ListSerializer(String.serializer()),
                text.split('\n').map { it.trim() }.filter { it.isNotBlank() },
            ),
        )
    }

    /** Turns a recurring dream sign into a prompt on the first reminder schedule. */
    fun addPromptFromDreamSign(sign: String) {
        val profile = _state.value.profiles.firstOrNull() ?: return
        val prompts = decodePrompts(profile) + "Have I seen $sign? Am I dreaming?"
        save(
            profile.copy(
                messagesJson = Json.encodeToString(ListSerializer(String.serializer()), prompts.distinct()),
            ),
        )
    }

    private fun decodePrompts(profile: ReminderProfileEntity): List<String> =
        runCatching {
            Json.decodeFromString(ListSerializer(String.serializer()), profile.messagesJson)
        }.getOrDefault(emptyList())
}
