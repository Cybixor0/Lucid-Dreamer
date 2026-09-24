// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucidreamer.AppContainer
import com.lucidreamer.domain.cue.SchedulingMode
import com.lucidreamer.domain.wbtb.WbtbConfig
import com.lucidreamer.sensing.SensingMode
import com.lucidreamer.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SettingsState(
    val audioCuesEnabled: Boolean = true,
    val preciseAlarms: Boolean = true,
    val wbtbEnabled: Boolean = false,
    val wbtbConfig: WbtbConfig = WbtbConfig(),
    val realityChecksEnabled: Boolean = false,
    val sensorEstimationRequested: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColour: Boolean = false,
    val nightModeDuringSession: Boolean = true,
    val statisticsEnabled: Boolean = true,
    val developerMode: Boolean = false,
    val sensingMode: SensingMode = SensingMode.OFF,
    val schedulingMode: SchedulingMode = SchedulingMode.FIXED,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val s = container.settings
            _state.value = SettingsState(
                audioCuesEnabled = s.audioCuesEnabled.first(),
                preciseAlarms = s.preciseAlarms.first(),
                wbtbEnabled = s.wbtbEnabled.first(),
                wbtbConfig = s.wbtbConfig.first(),
                realityChecksEnabled = s.realityChecksEnabled.first(),
                sensorEstimationRequested = s.sensorEstimationRequested.first(),
                themeMode = s.themeMode.first(),
                dynamicColour = s.dynamicColour.first(),
                nightModeDuringSession = s.nightModeDuringSession.first(),
                statisticsEnabled = s.statisticsEnabled.first(),
                developerMode = s.developerMode.first(),
                sensingMode = s.sensingMode.first(),
                schedulingMode = s.schedulingMode.first(),
            )
        }
    }

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            refresh()
        }
    }

    fun setAudioCuesEnabled(v: Boolean) = edit { container.settings.setAudioCuesEnabled(v) }
    fun setPreciseAlarms(v: Boolean) = edit { container.settings.setPreciseAlarms(v) }
    fun setThemeMode(v: ThemeMode) = edit { container.settings.setThemeMode(v) }
    fun setDynamicColour(v: Boolean) = edit { container.settings.setDynamicColour(v) }
    fun setNightModeDuringSession(v: Boolean) = edit { container.settings.setNightModeDuringSession(v) }
    fun setStatisticsEnabled(v: Boolean) = edit { container.settings.setStatisticsEnabled(v) }
    fun setDeveloperMode(v: Boolean) = edit { container.settings.setDeveloperMode(v) }
    fun setSensorEstimationRequested(v: Boolean) = edit { container.settings.setSensorEstimationRequested(v) }
    fun setWbtbConfig(c: WbtbConfig) = edit { container.settings.setWbtbConfig(c) }

    fun setWbtbEnabled(v: Boolean) = edit {
        container.settings.setWbtbEnabled(v)
        // Cancel immediately when switched off mid-session, rather than letting
        // an already-armed wake-up fire anyway.
        if (!v) container.alarmScheduler.cancelWbtb()
    }

    /**
     * Reality-check reminders have to be re-planned when toggled, since they
     * are armed a day or two ahead rather than on demand.
     */
    fun setRealityChecksEnabled(v: Boolean) = edit {
        container.settings.setRealityChecksEnabled(v)
        container.reminderScheduler.rescheduleAll(if (v) "enabled by user" else "disabled by user")
    }
}
