// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lucidreamer.domain.schedule.ScheduleBasis
import com.lucidreamer.domain.schedule.SleepAnchors
import com.lucidreamer.ui.components.DropdownField
import com.lucidreamer.ui.components.DurationField
import com.lucidreamer.ui.components.EstimatedChip
import com.lucidreamer.ui.components.Note
import com.lucidreamer.ui.components.SectionCard
import com.lucidreamer.ui.components.SwitchRow
import com.lucidreamer.ui.components.TimeField
import com.lucidreamer.ui.components.display
import com.lucidreamer.ui.containerViewModel
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val vm = containerViewModel { ScheduleViewModel(it) }
    val state by vm.state.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Sleep schedule") },
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
                Note(
                    "Getting into bed and falling asleep are two different things, and the app " +
                        "keeps them apart. Cues placed \"after you fall asleep\" use the second one.",
                )
            }

            item {
                AnchorsCard(
                    title = "Usual night",
                    anchors = state.defaultAnchors,
                    onChange = vm::setDefaultAnchors,
                )
            }

            item {
                SectionCard(title = "How sessions start") {
                    SwitchRow(
                        title = "I'll start each night myself",
                        checked = state.requireManualStart,
                        onCheckedChange = vm::setRequireManualStart,
                        subtitle = "Nothing arms automatically. You tap \"I'm going to sleep now\" " +
                            "when you actually go to bed, which also gives the app a real sleep " +
                            "onset instead of an estimate.",
                    )
                }
            }

            item {
                SectionCard(title = "Different schedules for different days") {
                    Text(
                        "Add as many as you like. The highest-priority one that matches tonight wins.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = vm::addProfile, modifier = Modifier.fillMaxWidth()) {
                        Text("Add a schedule")
                    }
                }
            }

            items(state.profiles, key = { it.id }) { profile ->
                SectionCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = profile.name,
                            onValueChange = { vm.renameProfile(profile.id, it) },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.deleteProfile(profile.id) }) {
                            Icon(Icons.Default.Delete, "Delete")
                        }
                    }

                    Text("Applies on these nights", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            FilterChip(
                                selected = day in profile.selector.days,
                                onClick = { vm.toggleDay(profile.id, day) },
                                label = {
                                    Text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault()))
                                },
                            )
                        }
                    }
                    Note(
                        "These are the nights you go to bed. A weekend lie-in means Friday and " +
                            "Saturday nights, not Saturday and Sunday.",
                    )

                    AnchorFields(
                        anchors = profile.anchors,
                        onChange = { vm.setProfileAnchors(profile.id, it) },
                    )
                }
            }

            item {
                SectionCard(title = "Tonight only") {
                    Text(
                        "A one-off change that does not affect any of your usual schedules.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.tonightOverride?.let { override ->
                        AnchorFields(anchors = override, onChange = vm::setTonightOverride)
                        Button(onClick = vm::clearTonightOverride, modifier = Modifier.fillMaxWidth()) {
                            Text("Remove tonight's override")
                        }
                    } ?: Button(onClick = vm::createTonightOverride, modifier = Modifier.fillMaxWidth()) {
                        Text("Change tonight only")
                    }
                }
            }

            item {
                SectionCard(title = "Night wakings") {
                    Text(
                        "If you regularly wake at a particular point, cues around then are skipped - " +
                            "cueing someone who is already awake is at best wasted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Set these per cue profile, under \"Limits for the whole night\".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun AnchorsCard(
    title: String,
    anchors: SleepAnchors,
    onChange: (SleepAnchors) -> Unit,
) {
    SectionCard(title = title) {
        AnchorFields(anchors, onChange)
    }
}

@Composable
private fun AnchorFields(anchors: SleepAnchors, onChange: (SleepAnchors) -> Unit) {
    TimeField(
        label = "I get into bed at",
        value = anchors.bedTime,
        onChange = { onChange(anchors.copy(bedTime = it)) },
    )

    DurationField(
        label = "I usually fall asleep after",
        value = anchors.onsetLatency,
        onChange = { onChange(anchors.copy(onsetLatency = it)) },
        maxHours = 4,
        supporting = "Best guess is fine. Most people take 10-30 minutes.",
    )

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Asleep around ${anchors.bedTime.plus(anchors.onsetLatency).display()}",
            style = MaterialTheme.typography.bodyMedium,
        )
        EstimatedChip()
    }

    DropdownField(
        label = "Which do you actually set?",
        value = anchors.basis,
        options = ScheduleBasis.entries,
        optionLabel = {
            when (it) {
                ScheduleBasis.WAKE_TIME -> "I wake at a fixed time"
                ScheduleBasis.DURATION -> "I sleep for a set length"
            }
        },
        onChange = { onChange(anchors.copy(basis = it)) },
        supporting = "If you go to bed late: a fixed wake time means a shorter night, a set " +
            "length means a later morning.",
    )

    when (anchors.basis) {
        ScheduleBasis.WAKE_TIME -> TimeField(
            label = "I wake up at",
            value = anchors.wakeTime,
            onChange = { onChange(anchors.copy(wakeTime = it)) },
        )

        ScheduleBasis.DURATION -> DurationField(
            label = "I sleep for about",
            value = anchors.sleepDuration,
            onChange = { onChange(anchors.copy(sleepDuration = it)) },
            maxHours = 14,
        )
    }
}
