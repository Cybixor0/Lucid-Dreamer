// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucidreamer.AppContainer
import com.lucidreamer.data.db.entity.CueEventEntity
import com.lucidreamer.data.db.entity.CueState
import com.lucidreamer.data.db.entity.SessionEntity
import com.lucidreamer.data.db.entity.SessionState
import com.lucidreamer.domain.diagnostics.ReliabilityReport
import com.lucidreamer.domain.schedule.ResolvedNight
import com.lucidreamer.sensing.SensingMode
import com.lucidreamer.sensing.StageEstimate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

data class DashboardState(
    val loading: Boolean = true,
    val session: SessionEntity? = null,
    val tonight: ResolvedNight? = null,
    val scheduleExplanation: String = "",
    val cues: List<CueEventEntity> = emptyList(),
    val nextCueAtMillis: Long? = null,
    val cuesRemaining: Int = 0,
    val cuesPlayed: Int = 0,
    val audioCuesEnabled: Boolean = true,
    val requireManualStart: Boolean = false,
    val wbtbEnabled: Boolean = false,
    /** Populated the morning after a night that did not go to plan. */
    val pendingReport: ReliabilityReport? = null,
    val warnings: List<Warning> = emptyList(),
    val sensingMode: SensingMode = SensingMode.OFF,
    val stageEstimate: StageEstimate? = null,
    val microphoneOpen: Boolean = false,
    /** Set when sensing had to reduce itself, with the reason. */
    val sensingDegradedReason: String? = null,
) {
    val isRunning: Boolean
        get() = session?.state == SessionState.RUNNING || session?.state == SessionState.WBTB_AWAKE

    val isPaused: Boolean get() = session?.state == SessionState.PAUSED
    val hasSession: Boolean get() = session != null
}

/**
 * Something that will stop tonight working, detected before the user goes to
 * sleep rather than discovered at 4am.
 */
data class Warning(
    val id: String,
    val title: String,
    val detail: String,
    val actionLabel: String?,
)

class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private val zone: ZoneId get() = ZoneId.systemDefault()

    init {
        refresh()
        observeSession()
    }

    private fun observeSession() {
        viewModelScope.launch {
            container.sessionRepository.observeActiveSession().collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                container.seeder.seedIfEmpty()

                val session = container.sessionRepository.activeSession()
                val cues = session?.let { container.db.cueEventDao().forSession(it.id) }.orEmpty()
                val armed = cues.filter { it.state == CueState.ARMED }
                val tonight = container.sessionRepository.previewTonight()

                _state.value = DashboardState(
                    loading = false,
                    session = session,
                    tonight = tonight,
                    scheduleExplanation = session?.scheduleExplanation
                        ?: container.sessionRepository.explainSchedule(tonight.night),
                    cues = cues,
                    nextCueAtMillis = armed.minByOrNull { it.scheduledAtMillis }?.scheduledAtMillis,
                    cuesRemaining = armed.size,
                    cuesPlayed = cues.count { it.state == CueState.FIRED },
                    audioCuesEnabled = container.settings.audioCuesEnabled.first(),
                    requireManualStart = container.settings.requireManualStart.first(),
                    wbtbEnabled = container.settings.wbtbEnabled.first(),
                    pendingReport = container.sessionRepository.pendingReport(),
                    warnings = collectWarnings(),
                    sensingMode = container.settings.sensingMode.first(),
                    stageEstimate = container.sensingController.state.value.latest,
                    microphoneOpen = container.sensingController.state.value.microphoneOpen,
                    sensingDegradedReason = container.sensingController.state.value.degradedReason,
                )
            }.onFailure {
                _state.value = _state.value.copy(loading = false)
            }
        }
    }

    /**
     * Pre-flight checks.
     *
     * Each of these is something that silently breaks a night, so the user is
     * told while they are awake and can act. Ordered most-damaging first.
     */
    private suspend fun collectWarnings(): List<Warning> {
        val status = container.deviceStatus
        val warnings = mutableListOf<Warning>()

        if (!status.notificationsEnabled()) {
            warnings += Warning(
                id = "notifications",
                title = "Notifications are switched off",
                detail = "Without them the session cannot show its controls, and Android is far " +
                    "more likely to stop the app overnight.",
                actionLabel = "Allow notifications",
            )
        }

        if (!status.canScheduleExactAlarms()) {
            warnings += Warning(
                id = "exact_alarms",
                title = "Exact alarms are not permitted",
                detail = "Cues will be scheduled inexactly and may fire up to an hour late.",
                actionLabel = "Open settings",
            )
        }

        if (!status.isIgnoringBatteryOptimisations()) {
            warnings += Warning(
                id = "battery",
                title = "Battery optimisation is on",
                detail = if (status.isSamsung) {
                    "On Galaxy phones this is the most common reason cues do not play. Add " +
                        "Lucid Dreamer to \"Never sleeping apps\" as well."
                } else {
                    "Android may suspend the app while the screen is off, which can stop cues playing."
                },
                actionLabel = "Fix this",
            )
        }

        val route = com.lucidreamer.audio.AudioRoutes.current(container.appContext)
        if (route.dndMayBlockAlarms) {
            warnings += Warning(
                id = "dnd",
                title = "Do Not Disturb will silence cues",
                detail = "Cues play on the alarm stream, which most Do Not Disturb settings let " +
                    "through - but yours is set to silence everything.",
                actionLabel = null,
            )
        }

        if (route.alarmVolumeIsZero) {
            warnings += Warning(
                id = "volume",
                title = "Alarm volume is at zero",
                detail = "Cues play on the alarm stream, so nothing will be audible tonight.",
                actionLabel = null,
            )
        }

        val bucket = status.standbyBucket()
        if (bucket >= 40) {
            warnings += Warning(
                id = "bucket",
                title = "Android has restricted this app's background activity",
                detail = "Current state: ${status.standbyBucketLabel()}. Opening the app regularly " +
                    "and allowing unrestricted background use usually resolves it.",
                actionLabel = "Open settings",
            )
        }

        return warnings
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    fun startSession(reportOnsetNow: Boolean) {
        viewModelScope.launch {
            // fromForeground = true: the user tapped this in the open app,
            // which is the only situation where Android will let the session
            // service use the microphone.
            container.sessionManager.startTonight(
                reportedOnsetNow = reportOnsetNow,
                fromForeground = true,
            )
            refresh()
        }
    }

    /** Turns off microphone sensing for the rest of tonight, without ending the session. */
    fun disableMicrophoneTonight() {
        viewModelScope.launch {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    container.appContext,
                    android.content.Intent(
                        container.appContext,
                        com.lucidreamer.service.SleepSessionService::class.java,
                    ).apply {
                        action = com.lucidreamer.service.SleepSessionService.ACTION_DISABLE_MICROPHONE
                    },
                )
            }
            refresh()
        }
    }

    fun stopSession() = act { container.sessionManager.stop() }
    fun pauseSession() = act { container.sessionManager.pause() }
    fun resumeSession() = act { container.sessionManager.resume() }
    fun skipNextCue() = act { container.sessionManager.skipNextCue() }
    fun replayLastCue() = act { container.sessionManager.replayLastCue() }
    fun reportSleepingNow() = act { container.sessionManager.reportSleepingNow() }
    fun backToBed() = act { container.sessionManager.wbtbBackToBed() }

    fun dismissReport() {
        val id = _state.value.pendingReport?.sessionId ?: return
        viewModelScope.launch {
            container.sessionRepository.markReportSeen(id)
            refresh()
        }
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
            refresh()
        }
    }

    // -----------------------------------------------------------------------
    // Formatting helpers used by the screen
    // -----------------------------------------------------------------------

    fun millisToZdt(millis: Long): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(zone)

    fun timeUntil(millis: Long): Duration = Duration.between(ZonedDateTime.now(zone), millisToZdt(millis))
}
