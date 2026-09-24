// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.dev

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lucidreamer.data.db.entity.LogLevel
import com.lucidreamer.ui.components.SectionCard
import com.lucidreamer.ui.components.SettingRow
import com.lucidreamer.ui.containerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val vm = containerViewModel { DeveloperViewModel(it) }
    val state by vm.state.collectAsState()
    val log by vm.log.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Developer") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Default.ArrowBack, "Back") } },
            )
        },
    ) { insets ->
        LazyColumn(
            Modifier.padding(insets).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(title = "Session") {
                    SettingRow("State", state.sessionState)
                    SettingRow("Session id", state.sessionId)
                    SettingRow("Cues armed", state.cuesArmed)
                    SettingRow("Next cue", state.nextCue)
                    SettingRow("System next alarm", state.systemNextAlarm)
                    SettingRow("Foreground service", state.serviceRunning)
                }
            }

            item {
                SectionCard(title = "Device") {
                    SettingRow("Manufacturer", state.manufacturer)
                    SettingRow("Android", state.androidVersion)
                    SettingRow("Exact alarms", state.exactAlarms)
                    SettingRow("Battery optimisation", state.batteryOptimisation)
                    SettingRow("Standby bucket", state.standbyBucket)
                    SettingRow("Background restricted", state.backgroundRestricted)
                    SettingRow("Notifications", state.notifications)
                    SettingRow("DND filter", state.dndFilter)
                }
            }

            item {
                SectionCard(title = "Audio") {
                    SettingRow("Output", state.audioRoute)
                    SettingRow("Alarm volume", state.alarmVolume)
                    SettingRow("Cue assets on disk", state.assetBytes)
                }
            }

            item {
                SectionCard(title = "Sensors") {
                    SettingRow("Accelerometer", state.accelerometer)
                    SettingRow("Gyroscope", state.gyroscope)
                }
            }

            item {
                SectionCard(title = "Sleep sensing") {
                    SettingRow("Mode", state.sensingMode)
                    SettingRow("Loop", state.sensingRunning)
                    SettingRow("Microphone", state.microphoneOpen)
                    SettingRow("Epochs completed", state.sensingEpochs)
                    SettingRow("Latest estimate", state.stageEstimate)
                    SettingRow("Confidence", state.stageConfidence)
                    SettingRow("Signals used", state.stageBasis)
                    SettingRow("Breathing analysis", state.breathing)
                    SettingRow("Cue timing", state.schedulingMode)
                    Text(
                        "Every value here is an estimate derived from indirect signals. None of it " +
                            "is a measurement of sleep.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard(title = "Experiment") {
                    SettingRow("Active", state.activeExperiment)
                }
            }

            item {
                SectionCard(title = "Event log") {
                    Text(
                        "Everything the scheduler, alarms and audio engine did. This is the right " +
                            "thing to attach to a bug report - it is the only record that survives " +
                            "to the morning.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::exportLog) { Text("Export") }
                        OutlinedButton(onClick = vm::clearLog) { Text("Clear") }
                    }
                    state.exportPath?.let {
                        Text("Saved to $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            items(log) { entry ->
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        vm.formatTime(entry.atMillis),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        entry.tag,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = when (entry.level) {
                            LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            LogLevel.WARN -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        entry.message,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
