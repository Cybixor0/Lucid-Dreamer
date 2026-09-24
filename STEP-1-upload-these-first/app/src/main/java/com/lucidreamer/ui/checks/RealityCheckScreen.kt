// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.checks

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lucidreamer.data.db.entity.ReminderMode
import com.lucidreamer.data.db.entity.ReminderStyle
import com.lucidreamer.ui.components.DropdownField
import com.lucidreamer.ui.components.Note
import com.lucidreamer.ui.components.SectionCard
import com.lucidreamer.ui.components.SettingRow
import com.lucidreamer.ui.components.SwitchRow
import com.lucidreamer.ui.components.TimeField
import com.lucidreamer.ui.containerViewModel
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealityCheckScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val vm = containerViewModel { RealityCheckViewModel(it) }
    val state by vm.state.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Reality checks") },
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
                    "The app does not tell you how to do a reality check - the common methods " +
                        "contradict each other and which one sticks is personal. It just prompts " +
                        "you, with whatever wording you choose.",
                )
            }

            if (state.topDreamSigns.isNotEmpty()) {
                item {
                    SectionCard(title = "From your journal") {
                        Text(
                            "These recur in your dreams. A prompt built from one of them is worth " +
                                "more than a generic one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.topDreamSigns.take(5).forEach { sign ->
                                FilterChip(
                                    selected = false,
                                    onClick = { vm.addPromptFromDreamSign(sign.name) },
                                    label = { Text("${sign.name} (${sign.useCount})") },
                                )
                            }
                        }
                    }
                }
            }

            items(state.profiles, key = { it.id }) { profile ->
                SectionCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = profile.name,
                            onValueChange = { vm.update(profile.id) { p -> p.copy(name = it) } },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.delete(profile.id) }) {
                            Icon(Icons.Default.Delete, "Delete")
                        }
                    }

                    SwitchRow(
                        title = "Active",
                        checked = profile.enabled,
                        onCheckedChange = { v -> vm.update(profile.id) { it.copy(enabled = v) } },
                    )

                    Text("Days", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            FilterChip(
                                selected = vm.hasDay(profile, day),
                                onClick = { vm.toggleDay(profile.id, day) },
                                label = { Text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault())) },
                            )
                        }
                    }

                    TimeField(
                        label = "From",
                        value = LocalTime.ofSecondOfDay(profile.windowStartMinute * 60L),
                        onChange = { t ->
                            vm.update(profile.id) { it.copy(windowStartMinute = t.hour * 60 + t.minute) }
                        },
                    )
                    TimeField(
                        label = "Until",
                        value = LocalTime.ofSecondOfDay(profile.windowEndMinute * 60L),
                        onChange = { t ->
                            vm.update(profile.id) { it.copy(windowEndMinute = t.hour * 60 + t.minute) }
                        },
                    )

                    DropdownField(
                        label = "How often",
                        value = profile.mode,
                        options = ReminderMode.entries,
                        optionLabel = {
                            when (it) {
                                ReminderMode.RANDOM -> "A number of random times"
                                ReminderMode.INTERVAL -> "Every so many minutes"
                                ReminderMode.FIXED -> "Specific times"
                            }
                        },
                        onChange = { m -> vm.update(profile.id) { it.copy(mode = m) } },
                        supporting = "Random times are harder to tune out, which is usually the point.",
                    )

                    when (profile.mode) {
                        ReminderMode.RANDOM -> {
                            SettingRow("How many per day", "${profile.count}")
                            Slider(
                                value = profile.count.toFloat(),
                                onValueChange = { v -> vm.update(profile.id) { it.copy(count = v.toInt()) } },
                                valueRange = 1f..20f,
                            )
                            SettingRow("At least this far apart", "${profile.minGapMinutes} minutes")
                            Slider(
                                value = profile.minGapMinutes.toFloat(),
                                onValueChange = { v -> vm.update(profile.id) { it.copy(minGapMinutes = v.toInt()) } },
                                valueRange = 5f..240f,
                            )
                        }

                        ReminderMode.INTERVAL -> {
                            SettingRow("Every", "${profile.intervalMinutes} minutes")
                            Slider(
                                value = profile.intervalMinutes.toFloat(),
                                onValueChange = { v -> vm.update(profile.id) { it.copy(intervalMinutes = v.toInt()) } },
                                valueRange = 15f..360f,
                            )
                        }

                        ReminderMode.FIXED -> {
                            Note("Enter times as minutes past midnight, separated by commas - e.g. 540 for 09:00.")
                            OutlinedTextField(
                                value = profile.fixedTimesJson.trim('[', ']'),
                                onValueChange = { text ->
                                    vm.update(profile.id) { it.copy(fixedTimesJson = "[$text]") }
                                },
                                label = { Text("Times") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    DropdownField(
                        label = "How it alerts you",
                        value = profile.style,
                        options = ReminderStyle.entries,
                        optionLabel = {
                            when (it) {
                                ReminderStyle.SILENT -> "Silent"
                                ReminderStyle.VIBRATE -> "Vibrate"
                                ReminderStyle.SOUND -> "Sound"
                            }
                        },
                        onChange = { s -> vm.update(profile.id) { it.copy(style = s) } },
                    )

                    OutlinedTextField(
                        value = vm.promptsText(profile),
                        onValueChange = { vm.setPrompts(profile.id, it) },
                        label = { Text("Prompts, one per line") },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    SwitchRow(
                        title = "Pick a random prompt each time",
                        checked = profile.randomiseMessages,
                        onCheckedChange = { v -> vm.update(profile.id) { it.copy(randomiseMessages = v) } },
                    )
                }
            }

            item {
                Button(onClick = vm::addProfile, modifier = Modifier.fillMaxWidth()) {
                    Text("Add a reminder schedule")
                }
            }

            item {
                SectionCard(title = "Recently logged") {
                    if (state.recentChecks.isEmpty()) {
                        Text(
                            "Nothing logged yet. Logging is optional - the reminder works whether " +
                                "or not you record it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "${state.recentChecks.size} check${if (state.recentChecks.size == 1) "" else "s"} recorded.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
