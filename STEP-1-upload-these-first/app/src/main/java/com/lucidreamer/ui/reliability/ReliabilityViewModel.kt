// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.reliability

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucidreamer.AppContainer
import com.lucidreamer.audio.AudioRoutes
import com.lucidreamer.data.db.ColumnJson
import com.lucidreamer.domain.cue.BuiltInTone
import com.lucidreamer.domain.cue.CuePlayback
import com.lucidreamer.domain.cue.CueSound
import com.lucidreamer.domain.diagnostics.ReliabilityReport
import com.lucidreamer.service.CuePlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.time.Duration

data class Check(
    val title: String,
    val detail: String,
    val passing: Boolean,
    val intent: Intent? = null,
    val actionLabel: String? = null,
)

data class ReliabilityState(
    val checks: List<Check> = emptyList(),
    val isSamsung: Boolean = false,
    val samsungIntent: Intent? = null,
    val lastReport: ReliabilityReport? = null,
    val message: String? = null,
)

class ReliabilityViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ReliabilityState())
    val state: StateFlow<ReliabilityState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val status = container.deviceStatus
            val route = AudioRoutes.current(container.appContext)
            val checks = mutableListOf<Check>()

            checks += Check(
                title = "Notifications",
                detail = if (status.notificationsEnabled()) {
                    "Allowed. The session can show its controls."
                } else {
                    "Blocked. The session cannot show controls, and Android is much more likely " +
                        "to stop the app overnight."
                },
                passing = status.notificationsEnabled(),
                intent = status.notificationSettingsIntent(),
                actionLabel = "Allow notifications",
            )

            checks += Check(
                title = "Exact alarms",
                detail = if (status.canScheduleExactAlarms()) {
                    "Permitted. Cues can be scheduled to the second."
                } else {
                    "Not permitted. Cues will be deferred to Android's maintenance windows and " +
                        "may be up to an hour late."
                },
                passing = status.canScheduleExactAlarms(),
                intent = status.exactAlarmSettingsIntent(),
            )

            checks += Check(
                title = "Battery optimisation",
                detail = if (status.isIgnoringBatteryOptimisations()) {
                    "Exempt. The app is allowed to keep running with the screen off."
                } else {
                    "Active. This is the single most common reason overnight cues do not play."
                },
                passing = status.isIgnoringBatteryOptimisations(),
                intent = status.batteryOptimisationIntent(),
                actionLabel = "Allow background use",
            )

            checks += Check(
                title = "Background activity",
                detail = "Android currently rates this app as: ${status.standbyBucketLabel()}." +
                    if (status.standbyBucket() >= 40) {
                        " That is restrictive enough to interfere with cues."
                    } else {
                        ""
                    },
                passing = status.standbyBucket() < 40,
                intent = status.appDetailsIntent(),
            )

            checks += Check(
                title = "Do Not Disturb",
                detail = if (route.dndMayBlockAlarms) {
                    "Set to silence everything, including alarms. Cues play on the alarm stream, " +
                        "so nothing will be audible."
                } else {
                    "Will not block cues. They play on the alarm stream, which your current " +
                        "setting allows."
                },
                passing = !route.dndMayBlockAlarms,
            )

            checks += Check(
                title = "Alarm volume",
                detail = if (route.alarmVolumeIsZero) {
                    "At zero. Cues play on the alarm stream, so nothing will be heard."
                } else {
                    "${route.alarmVolume} of ${route.maxAlarmVolume}. Per-cue volume is applied on " +
                        "top of this, so cues can still be much quieter than an alarm."
                },
                passing = !route.alarmVolumeIsZero,
            )

            checks += Check(
                title = "Audio output",
                detail = "Currently routed to ${route.deviceName}." +
                    if (route.route.isHeadphoneLike) {
                        " If these disconnect overnight, each cue follows the fallback you chose for it."
                    } else {
                        ""
                    },
                passing = true,
            )

            _state.value = ReliabilityState(
                checks = checks,
                isSamsung = status.isSamsung,
                samsungIntent = status.samsungNeverSleepingAppsIntent(),
                lastReport = container.db.sessionDao().lastCompleted()
                    ?.let { container.sessionRepository.reportFor(it.id) },
            )
        }
    }

    /**
     * Arms a genuine alarm two minutes out.
     *
     * Deliberately a real scheduled alarm rather than an immediate playback:
     * the thing being tested is whether Android delivers an alarm to this app
     * with the screen off, which is exactly what an immediate test would skip.
     */
    fun scheduleTestCue() {
        viewModelScope.launch {
            container.alarmScheduler.armTestCue(
                atMillis = System.currentTimeMillis() + Duration.ofMinutes(2).toMillis(),
            )
            _state.value = _state.value.copy(
                message = "Armed. Lock your phone and put it down - the cue will play in two minutes.",
            )
        }
    }

    /**
     * Plays tonight's planned cues back to back, compressed.
     *
     * Lets the user judge volume, fades and ordering in a few minutes instead
     * of discovering at 4am that a cue is inaudible, or loud enough to wake
     * them fully.
     */
    fun runDryRun() {
        viewModelScope.launch {
            val night = container.sessionRepository.previewTonight()
            val profileId = container.settings.activeCueProfileId.first()
            val profile = container.db.cueProfileDao().byId(profileId)?.toDomain()

            if (profile == null || profile.rules.isEmpty()) {
                _state.value = _state.value.copy(message = "No cues are configured, so there is nothing to hear.")
                return@launch
            }

            val plan = com.lucidreamer.domain.cue.CuePlanner.plan(night, profile, seed = 1L)
            if (plan.cues.isEmpty()) {
                _state.value = _state.value.copy(
                    message = "This profile would not play anything tonight. The cue editor shows why.",
                )
                return@launch
            }

            _state.value = _state.value.copy(
                message = "Playing ${plan.cues.size} cue(s) in order, about 20 seconds apart.",
            )

            for (cue in plan.cues.take(8)) {
                runCatching {
                    ContextCompat.startForegroundService(
                        container.appContext,
                        Intent(container.appContext, CuePlaybackService::class.java).apply {
                            action = CuePlaybackService.ACTION_TEST_CUE
                            putExtra(
                                CuePlaybackService.EXTRA_SOUND_JSON,
                                ColumnJson.encodeToString<CueSound>(cue.sound),
                            )
                            putExtra(
                                CuePlaybackService.EXTRA_PLAYBACK_JSON,
                                ColumnJson.encodeToString<CuePlayback>(cue.playback),
                            )
                        },
                    )
                }
                delay(20_000)
            }

            _state.value = _state.value.copy(message = "Dry run finished.")
        }
    }

    private companion object {
        val TEST_SOUND = CueSound.BuiltIn(BuiltInTone.SOFT_BELL)
    }
}
