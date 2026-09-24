// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.sensing

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucidreamer.AppContainer
import com.lucidreamer.domain.cue.SchedulingMode
import com.lucidreamer.sensing.SensingMode
import com.lucidreamer.sensing.StageEstimate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SensingUiState(
    val mode: SensingMode = SensingMode.OFF,
    val schedulingMode: SchedulingMode = SchedulingMode.FIXED,
    val hasMicPermission: Boolean = false,
    val batteryExempt: Boolean = false,
    val motionAvailable: Boolean = false,
    val lastNightSamples: Int = 0,
    val lastNightUsable: Int = 0,
)

class SensingViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SensingUiState())
    val state: StateFlow<SensingUiState> = _state.asStateFlow()

    /** Mode the user picked that is waiting on a permission result. */
    private var pendingMode: SensingMode? = null

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val lastSession = container.db.sessionDao().lastCompleted()
            val samples = lastSession?.let { container.db.stageSampleDao().forSession(it.id) }.orEmpty()

            _state.value = SensingUiState(
                mode = container.settings.sensingMode.first(),
                schedulingMode = container.settings.schedulingMode.first(),
                hasMicPermission = hasMicPermission(),
                batteryExempt = container.deviceStatus.isIgnoringBatteryOptimisations(),
                motionAvailable = container.sensingController.motionAvailable(),
                lastNightSamples = samples.size,
                lastNightUsable = samples.count { it.confidence >= StageEstimate.USABLE_CONFIDENCE },
            )
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(container.appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun setMode(mode: SensingMode) {
        viewModelScope.launch {
            container.settings.setSensingMode(mode)
            // Consent is recorded when a microphone mode is actually chosen,
            // having read the explanation on this screen - separately from the
            // Android permission, which explains none of it.
            if (mode.usesMicrophone) container.settings.setSensingConsented(true)

            // Adaptive timing is meaningless without sensing, so turning
            // sensing off turns it off too rather than leaving a setting that
            // silently does nothing.
            if (mode == SensingMode.OFF) {
                container.settings.setSchedulingMode(SchedulingMode.FIXED)
            }
            refresh()
        }
    }

    fun rememberPendingMode(mode: SensingMode) {
        pendingMode = mode
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        val requested = pendingMode
        pendingMode = null

        if (granted && requested != null) {
            setMode(requested)
        } else {
            // Refused: fall back to movement-only rather than leaving a
            // microphone mode selected that cannot possibly work.
            viewModelScope.launch {
                if (requested == SensingMode.COMBINED) {
                    container.settings.setSensingMode(SensingMode.MOTION)
                }
                refresh()
            }
        }
    }

    fun setAdaptive(enabled: Boolean) {
        viewModelScope.launch {
            container.settings.setSchedulingMode(
                if (enabled) SchedulingMode.ADAPTIVE else SchedulingMode.FIXED,
            )
            refresh()
        }
    }
}
