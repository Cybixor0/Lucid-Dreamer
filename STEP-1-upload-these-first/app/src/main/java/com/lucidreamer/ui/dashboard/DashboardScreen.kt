// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lucidreamer.data.db.entity.SessionState
import com.lucidreamer.sensing.SensingMode
import com.lucidreamer.ui.components.EstimatedChip
import com.lucidreamer.ui.components.Note
import com.lucidreamer.ui.components.NoteKind
import com.lucidreamer.ui.components.SectionCard
import com.lucidreamer.ui.components.StatValue
import com.lucidreamer.ui.components.display
import com.lucidreamer.ui.containerViewModel
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.format.DateTimeFormatter

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun DashboardScreen(
    onOpenCues: () -> Unit,
    onOpenJournal: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenReliability: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = containerViewModel { DashboardViewModel(it) }
    val state by vm.state.collectAsState()

    // Drives the countdown to the next cue without re-querying the database.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.nextCueAtMillis) {
        while (state.nextCueAtMillis != null) {
            delay(1000)
            tick++
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.pendingReport?.let { report ->
            item {
                SectionCard(title = "Last night") {
                    Text(report.headline, style = MaterialTheme.typography.bodyLarge)
                    if (!report.wentWell) {
                        Text(
                            report.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!report.wentWell) {
                            Button(onClick = onOpenReliability) { Text("How to fix it") }
                        }
                        TextButton(onClick = vm::dismissReport) { Text("Dismiss") }
                    }
                }
            }
        }

        items(state.warnings) { warning ->
            Note(
                text = "${warning.title}. ${warning.detail}",
                kind = NoteKind.WARNING,
            )
        }

        item { TonightCard(state, vm, tick, onOpenSchedule) }

        item { SessionControls(state, vm) }

        item {
            SectionCard(title = "Tonight's cues") {
                if (!state.audioCuesEnabled) {
                    Text(
                        "Audio cues are switched off. The journal and reality checks still work.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(state.scheduleExplanation, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        StatValue(
                            value = if (state.hasSession) "${state.cuesRemaining}" else "-",
                            label = "still to play",
                        )
                        StatValue(
                            value = if (state.hasSession) "${state.cuesPlayed}" else "-",
                            label = "played",
                        )
                    }
                    state.nextCueAtMillis?.let { at ->
                        val remaining = vm.timeUntil(at).coerceAtLeast(Duration.ZERO)
                        Text(
                            "Next cue at ${vm.millisToZdt(at).format(hhmm)}, in ${remaining.display()}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                OutlinedButton(onClick = onOpenCues) {
                    Icon(Icons.Default.MusicNote, null, Modifier.height(18.dp))
                    Spacer(Modifier.height(4.dp))
                    Text("  Cue settings")
                }
            }
        }

        item {
            SectionCard(title = "Dream journal") {
                Text(
                    "Write it down before it fades. Recall is what everything else depends on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenJournal) {
                    Icon(Icons.Default.Book, null, Modifier.height(18.dp))
                    Text("  Open journal")
                }
            }
        }

        item { SleepEstimateCard(state, vm) }
    }
}

/**
 * The estimated sleep stage, when sensing is on.
 *
 * Shows the probability alongside the label, every time, so "possible REM" can
 * never be read as a measurement. When the estimate is not good enough to act
 * on, that is what it says.
 */
@Composable
private fun SleepEstimateCard(state: DashboardState, vm: DashboardViewModel) {
    if (state.sensingMode == SensingMode.OFF) {
        SectionCard(title = "Sleep-stage estimation") {
            Text(
                "Off. Cues play at the times you set.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Note(
                "An experimental estimate is available in Settings. A phone cannot measure sleep " +
                    "stages, so it is a guess with a confidence value - useful to experiment " +
                    "with, not something to rely on.",
                kind = NoteKind.EXPERIMENTAL,
            )
        }
        return
    }

    SectionCard(title = "Sleep-stage estimate") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                state.stageEstimate?.describe() ?: "Not enough information yet",
                style = MaterialTheme.typography.titleMedium,
            )
            EstimatedChip()
        }

        state.stageEstimate?.let { estimate ->
            Text(
                "Based on: ${estimate.basis}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!estimate.isUsable) {
                Note(
                    "Not confident enough to change anything. Cues will play at their scheduled " +
                        "times.",
                )
            }
        }

        if (state.microphoneOpen) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "Microphone in use",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.height(18.dp),
                )
                Text(
                    "Microphone is listening now",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (state.sensingMode.usesMicrophone && state.isRunning) {
            OutlinedButton(onClick = vm::disableMicrophoneTonight, modifier = Modifier.fillMaxWidth()) {
                Text("Turn the microphone off for tonight")
            }
        }

        state.sensingDegradedReason?.let {
            Note(it, kind = NoteKind.WARNING)
        }

        Note(
            "This is an estimate, not a measurement. See Settings > Sleep sensing for what it " +
                "can and cannot do.",
            kind = NoteKind.EXPERIMENTAL,
        )
    }
}

@Composable
private fun TonightCard(
    state: DashboardState,
    vm: DashboardViewModel,
    @Suppress("UNUSED_PARAMETER") tick: Long,
    onOpenSchedule: () -> Unit,
) {
    val night = state.tonight ?: return

    SectionCard(title = "Tonight") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                Text("Into bed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(night.bedAt.format(hhmm), style = MaterialTheme.typography.titleLarge)
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Asleep", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (night.isOnsetEstimated) EstimatedChip()
                }
                Text(night.sleepAt.format(hhmm), style = MaterialTheme.typography.titleLarge)
            }
            Column {
                Text("Awake", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(night.wakeAt.format(hhmm), style = MaterialTheme.typography.titleLarge)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "About ${night.sleepDuration.display()} of sleep",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EstimatedChip()
        }

        if (night.isOnsetEstimated) {
            Text(
                "Sleep onset is estimated from your bedtime plus how long you usually take to " +
                    "fall asleep. Telling the app when you actually go to sleep makes every " +
                    "relative cue land where you meant it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(onClick = onOpenSchedule) { Text("Change schedule") }
    }
}

@Composable
private fun SessionControls(state: DashboardState, vm: DashboardViewModel) {
    SectionCard {
        when {
            state.session?.state == SessionState.WBTB_AWAKE -> {
                Text("Wake Back To Bed - you're up.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tap when you get back into bed, so the cues are anchored to the right moment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = vm::backToBed, modifier = Modifier.fillMaxWidth()) {
                    Text("I'm back in bed")
                }
            }

            state.isRunning -> {
                Text("Session running", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::pauseSession, modifier = Modifier.weight(1f)) { Text("Pause") }
                    OutlinedButton(onClick = vm::skipNextCue, modifier = Modifier.weight(1f)) { Text("Skip next") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::replayLastCue, modifier = Modifier.weight(1f)) { Text("Replay last") }
                    Button(
                        onClick = vm::stopSession,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Default.Stop, null, Modifier.height(18.dp))
                        Text(" Stop")
                    }
                }
                if (state.session?.actualSleepAtMillis == null) {
                    TextButton(onClick = vm::reportSleepingNow, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Bedtime, null, Modifier.height(18.dp))
                        Text("  I'm going to sleep now")
                    }
                }
            }

            state.isPaused -> {
                Text("Session paused", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::resumeSession, modifier = Modifier.weight(1f)) { Text("Resume") }
                    OutlinedButton(onClick = vm::stopSession, modifier = Modifier.weight(1f)) { Text("Stop") }
                }
            }

            else -> {
                Text("No session tonight yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.requireManualStart) {
                        "You've chosen to start each night yourself, so nothing runs until you say so."
                    } else {
                        "Starting now also lets you tell the app when you actually fall asleep."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { vm.startSession(reportOnsetNow = true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Bedtime, null, Modifier.height(18.dp))
                    Text("  I'm going to sleep now")
                }
                OutlinedButton(
                    onClick = { vm.startSession(reportOnsetNow = false) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.height(18.dp))
                    Text("  Arm tonight on my usual schedule")
                }
            }
        }
    }
}

private fun java.time.ZonedDateTime.format(f: DateTimeFormatter): String = f.format(this)
