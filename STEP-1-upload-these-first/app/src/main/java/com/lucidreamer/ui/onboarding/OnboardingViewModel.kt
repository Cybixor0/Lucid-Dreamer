// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucidreamer.AppContainer
import com.lucidreamer.domain.cue.Technique
import com.lucidreamer.domain.schedule.SleepAnchors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalTime

data class OnboardingState(
    val step: Int = 0,
    val anchors: SleepAnchors = SleepAnchors(),
    val weekendAnchors: SleepAnchors = SleepAnchors(
        bedTime = LocalTime.of(0, 30),
        onsetLatency = Duration.ofMinutes(20),
        wakeTime = LocalTime.of(9, 0),
    ),
    val differentAtWeekends: Boolean = false,
    val wakesDuringNight: Boolean = false,
    val technique: Technique = Technique.UNDECIDED,
    val audioCues: Boolean = true,
    val wbtb: Boolean = false,
    val realityChecks: Boolean = false,
    val manualStart: Boolean = false,
    val sensorEstimation: Boolean = false,
)

class OnboardingViewModel(private val container: AppContainer) : ViewModel() {

    companion object {
        const val STEP_COUNT = 7
    }

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.seeder.seedIfEmpty()
            // Resume rather than restart: setup is skippable and long-ish, and
            // being thrown back to step one after closing the app is annoying.
            _state.value = _state.value.copy(
                step = container.settings.onboardingStep.first(),
                anchors = container.settings.defaultAnchors.first(),
            )
        }
    }

    fun setAnchors(a: SleepAnchors) { _state.value = _state.value.copy(anchors = a) }
    fun setWeekendAnchors(a: SleepAnchors) { _state.value = _state.value.copy(weekendAnchors = a) }
    fun setDifferentAtWeekends(v: Boolean) { _state.value = _state.value.copy(differentAtWeekends = v) }
    fun setWakesDuringNight(v: Boolean) { _state.value = _state.value.copy(wakesDuringNight = v) }
    fun setTechnique(v: Technique) { _state.value = _state.value.copy(technique = v) }
    fun setAudioCues(v: Boolean) { _state.value = _state.value.copy(audioCues = v) }
    fun setWbtb(v: Boolean) { _state.value = _state.value.copy(wbtb = v) }
    fun setRealityChecks(v: Boolean) { _state.value = _state.value.copy(realityChecks = v) }
    fun setManualStart(v: Boolean) { _state.value = _state.value.copy(manualStart = v) }
    fun setSensorEstimation(v: Boolean) { _state.value = _state.value.copy(sensorEstimation = v) }

    fun back() {
        val step = (_state.value.step - 1).coerceAtLeast(0)
        _state.value = _state.value.copy(step = step)
        viewModelScope.launch { container.settings.setOnboardingStep(step) }
    }

    fun next() {
        val current = _state.value.step
        if (current >= STEP_COUNT - 1) {
            finish()
            return
        }
        val step = current + 1
        _state.value = _state.value.copy(step = step)
        viewModelScope.launch { container.settings.setOnboardingStep(step) }
    }

    /**
     * Leaves setup immediately, keeping whatever has been answered so far.
     *
     * Skipping must not be punished: the defaults are deliberately safe, and
     * everything is reachable in Settings.
     */
    fun skipEverything() = finish()

    private fun finish() {
        viewModelScope.launch {
            val s = _state.value
            container.seeder.applyOnboarding(
                anchors = s.anchors,
                weekendAnchors = s.weekendAnchors.takeIf { s.differentAtWeekends },
                technique = s.technique,
                audioCues = s.audioCues,
                realityChecks = s.realityChecks,
                manualStart = s.manualStart,
                wbtb = s.wbtb,
                sensorEstimation = s.sensorEstimation,
            )
            if (s.realityChecks) {
                container.reminderScheduler.rescheduleAll("onboarding finished")
            }
            container.settings.setOnboardingComplete(true)
        }
    }
}
