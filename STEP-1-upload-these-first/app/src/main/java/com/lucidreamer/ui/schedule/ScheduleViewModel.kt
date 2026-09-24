// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucidreamer.AppContainer
import com.lucidreamer.data.db.entity.NightOverrideEntity
import com.lucidreamer.data.db.entity.ScheduleProfileEntity
import com.lucidreamer.domain.schedule.DaySelector
import com.lucidreamer.domain.schedule.NightOverride
import com.lucidreamer.domain.schedule.ScheduleProfile
import com.lucidreamer.domain.schedule.SleepAnchors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

data class ScheduleState(
    val defaultAnchors: SleepAnchors = SleepAnchors(),
    val requireManualStart: Boolean = false,
    val profiles: List<ScheduleProfile> = emptyList(),
    val tonightOverride: SleepAnchors? = null,
)

class ScheduleViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ScheduleState())
    val state: StateFlow<ScheduleState> = _state.asStateFlow()

    private val zone: ZoneId get() = ZoneId.systemDefault()

    init { refresh() }

    private fun tonight(): LocalDate = LocalDate.now(zone)

    fun refresh() {
        viewModelScope.launch {
            _state.value = ScheduleState(
                defaultAnchors = container.settings.defaultAnchors.first(),
                requireManualStart = container.settings.requireManualStart.first(),
                profiles = container.db.scheduleProfileDao().all().map { it.toDomain() },
                tonightOverride = container.db.nightOverrideDao()
                    .forNight(tonight().toEpochDay())?.toDomain()?.anchors,
            )
        }
    }

    fun setDefaultAnchors(anchors: SleepAnchors) {
        viewModelScope.launch {
            container.settings.setDefaultAnchors(anchors)
            refresh()
        }
    }

    fun setRequireManualStart(value: Boolean) {
        viewModelScope.launch {
            container.settings.setRequireManualStart(value)
            refresh()
        }
    }

    fun addProfile() {
        viewModelScope.launch {
            container.db.scheduleProfileDao().upsert(
                ScheduleProfileEntity.from(
                    ScheduleProfile(
                        name = "Weekend nights",
                        selector = DaySelector.WeekendNights,
                        anchors = container.settings.defaultAnchors.first(),
                        // Above the default so it takes precedence on the
                        // nights it matches.
                        priority = 10,
                    ),
                ),
            )
            refresh()
        }
    }

    fun renameProfile(id: Long, name: String) = editProfile(id) { it.copy(name = name) }

    fun setProfileAnchors(id: Long, anchors: SleepAnchors) = editProfile(id) { it.copy(anchors = anchors) }

    fun toggleDay(id: Long, day: DayOfWeek) = editProfile(id) { profile ->
        val days = profile.selector.days.toMutableSet()
        if (!days.remove(day)) days.add(day)
        profile.copy(selector = DaySelector(days))
    }

    private fun editProfile(id: Long, transform: (ScheduleProfile) -> ScheduleProfile) {
        viewModelScope.launch {
            val existing = container.db.scheduleProfileDao().byId(id)?.toDomain() ?: return@launch
            container.db.scheduleProfileDao().upsert(ScheduleProfileEntity.from(transform(existing)))
            refresh()
        }
    }

    fun deleteProfile(id: Long) {
        viewModelScope.launch {
            container.db.scheduleProfileDao().byId(id)?.let {
                container.db.scheduleProfileDao().delete(it)
            }
            refresh()
        }
    }

    fun createTonightOverride() {
        viewModelScope.launch {
            val base = container.sessionRepository.anchorsFor(tonight())
            saveOverride(base)
        }
    }

    fun setTonightOverride(anchors: SleepAnchors) {
        viewModelScope.launch { saveOverride(anchors) }
    }

    private suspend fun saveOverride(anchors: SleepAnchors) {
        container.db.nightOverrideDao().upsert(
            NightOverrideEntity.from(NightOverride(night = tonight(), anchors = anchors)),
        )
        refresh()
    }

    fun clearTonightOverride() {
        viewModelScope.launch {
            container.db.nightOverrideDao().deleteForNight(tonight().toEpochDay())
            refresh()
        }
    }
}
