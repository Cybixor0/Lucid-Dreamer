// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.dev

import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucidreamer.AppContainer
import com.lucidreamer.audio.AudioRoutes
import com.lucidreamer.data.db.entity.EventLogEntity
import com.lucidreamer.service.SleepSessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DeveloperState(
    val sessionState: String = "-",
    val sessionId: String = "-",
    val cuesArmed: String = "-",
    val nextCue: String = "-",
    val systemNextAlarm: String = "-",
    val serviceRunning: String = "-",
    val manufacturer: String = "-",
    val androidVersion: String = "-",
    val exactAlarms: String = "-",
    val batteryOptimisation: String = "-",
    val standbyBucket: String = "-",
    val backgroundRestricted: String = "-",
    val notifications: String = "-",
    val dndFilter: String = "-",
    val audioRoute: String = "-",
    val alarmVolume: String = "-",
    val assetBytes: String = "-",
    val accelerometer: String = "-",
    val gyroscope: String = "-",
    val sensingMode: String = "-",
    val sensingRunning: String = "-",
    val microphoneOpen: String = "-",
    val sensingEpochs: String = "-",
    val stageEstimate: String = "-",
    val stageConfidence: String = "-",
    val stageBasis: String = "-",
    val breathing: String = "-",
    val schedulingMode: String = "-",
    val activeExperiment: String = "-",
    val exportPath: String? = null,
)

class DeveloperViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(DeveloperState())
    val state: StateFlow<DeveloperState> = _state.asStateFlow()

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val fullFormat = DateTimeFormatter.ofPattern("dd MMM HH:mm:ss")

    val log: StateFlow<List<EventLogEntity>> =
        container.db.eventLogDao().observeRecent(400)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val status = container.deviceStatus
            val session = container.db.sessionDao().activeSession()
            val armed = session?.let { container.db.cueEventDao().allArmed(it.id) }.orEmpty()
            val route = AudioRoutes.current(container.appContext)
            val sensors = container.appContext
                .getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val sensing = container.sensingController.state.value

            _state.value = DeveloperState(
                sessionState = session?.state?.name ?: "none",
                sessionId = session?.id?.toString() ?: "-",
                cuesArmed = armed.size.toString(),
                nextCue = armed.minByOrNull { it.scheduledAtMillis }
                    ?.let { fullFormat.format(Instant.ofEpochMilli(it.scheduledAtMillis).atZone(ZoneId.systemDefault())) }
                    ?: "none",
                systemNextAlarm = container.alarmScheduler.systemNextAlarmMillis()
                    ?.let { fullFormat.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
                    ?: "none",
                serviceRunning = if (isServiceRunning()) "running" else "not running",
                manufacturer = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                exactAlarms = if (status.canScheduleExactAlarms()) "permitted" else "DENIED",
                batteryOptimisation = if (status.isIgnoringBatteryOptimisations()) "exempt" else "ACTIVE",
                standbyBucket = status.standbyBucketLabel(),
                backgroundRestricted = if (status.isBackgroundRestricted()) "RESTRICTED" else "normal",
                notifications = if (status.notificationsEnabled()) "enabled" else "BLOCKED",
                dndFilter = dndLabel(status.interruptionFilter()),
                audioRoute = "${route.route.label} - ${route.deviceName}",
                alarmVolume = "${route.alarmVolume}/${route.maxAlarmVolume}",
                assetBytes = "${container.cueAssets.storageBytes() / 1024} KB",
                accelerometer = sensorLabel(sensors, Sensor.TYPE_ACCELEROMETER),
                gyroscope = sensorLabel(sensors, Sensor.TYPE_GYROSCOPE),
                sensingMode = container.settings.sensingMode.first().label,
                sensingRunning = if (sensing.running) "running" else "not running",
                microphoneOpen = if (sensing.microphoneOpen) "OPEN NOW" else "closed",
                sensingEpochs = sensing.epochsCompleted.toString(),
                stageEstimate = sensing.latest?.describe() ?: "none",
                stageConfidence = sensing.latest?.let { "${(it.confidence * 100).toInt()}%" } ?: "-",
                stageBasis = sensing.latest?.basis ?: "-",
                breathing = sensing.lastBreathing?.let { b ->
                    val rate = b.breathsPerMinute?.let { "%.1f bpm".format(it) } ?: "no rate found"
                    "$rate, regularity ${"%.2f".format(b.regularity)}, " +
                        "confidence ${(b.confidence * 100).toInt()}%"
                } ?: "-",
                schedulingMode = container.settings.schedulingMode.first().label,
                activeExperiment = container.db.experimentDao().activeExperiment()
                    ?.let { "${it.name} (A=${it.armALabel}, B=${it.armBLabel})" }
                    ?: "none",
            )
        }
    }

    private fun sensorLabel(sensors: SensorManager?, type: Int): String {
        val sensor = sensors?.getDefaultSensor(type) ?: return "not present"
        return "${sensor.name} (wake-up: ${sensor.isWakeUpSensor})"
    }

    private fun dndLabel(filter: Int): String = when (filter) {
        android.app.NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
        android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority only"
        android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms only"
        android.app.NotificationManager.INTERRUPTION_FILTER_NONE -> "TOTAL SILENCE (blocks cues)"
        else -> "unknown ($filter)"
    }

    /**
     * getRunningServices is deprecated and only reports this app's own services
     * on modern Android - which is exactly what is wanted here.
     */
    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val am = container.appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return runCatching {
            am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == SleepSessionService::class.java.name }
        }.getOrDefault(false)
    }

    fun formatTime(millis: Long): String =
        timeFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    fun exportLog() {
        viewModelScope.launch {
            val path = runCatching {
                withContext(Dispatchers.IO) {
                    val text = container.eventLog.exportAsText()
                    val dir = File(container.appContext.getExternalFilesDir(null), "exports")
                    dir.mkdirs()
                    val file = File(dir, "lucid-dreamer-log.txt")
                    file.writeText(text)
                    file.absolutePath
                }
            }.getOrNull()
            _state.value = _state.value.copy(exportPath = path)
        }
    }

    fun clearLog() {
        viewModelScope.launch { container.eventLog.clear() }
    }
}
