// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.sensing

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lucidreamer.domain.cue.SchedulingMode
import com.lucidreamer.sensing.SensingMode
import com.lucidreamer.ui.components.Note
import com.lucidreamer.ui.components.NoteKind
import com.lucidreamer.ui.components.SectionCard
import com.lucidreamer.ui.components.SwitchRow
import com.lucidreamer.ui.containerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensingScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val vm = containerViewModel { SensingViewModel(it) }
    val state by vm.state.collectAsState()

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> vm.onMicrophonePermissionResult(granted) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Sleep sensing") },
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
                SectionCard(title = "What this can and cannot do") {
                    Text(
                        "A phone cannot measure sleep stages. Sleep staging is defined by brain " +
                            "activity, eye movement and muscle tone, and your phone has none of " +
                            "those sensors.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "What it can do is notice how much you move, and listen for slow rhythms " +
                            "that may be your breathing. Combined with the fact that REM periods " +
                            "get longer towards morning, that produces a guess - with a " +
                            "confidence value attached, and labelled \"possible REM\" rather " +
                            "than \"REM\".",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Whenever the guess is not confident enough to act on, the app says so and " +
                            "falls back to the times you set. It will never silently drop a cue " +
                            "because of a weak estimate.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Note(
                        "Everything here is experimental. If you want a reliable night, leave this " +
                            "off - the fixed schedule works perfectly well on its own, and every " +
                            "technique this app supports predates any of it.",
                        kind = NoteKind.EXPERIMENTAL,
                    )
                }
            }

            item { Text("Mode", style = MaterialTheme.typography.titleLarge) }

            items(SensingMode.entries.size) { index ->
                val mode = SensingMode.entries[index]
                val selected = state.mode == mode

                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    if (mode.requiresMicrophonePermission && !state.hasMicPermission) {
                                        vm.rememberPendingMode(mode)
                                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        vm.setMode(mode)
                                    }
                                },
                            )
                            Text(mode.label, style = MaterialTheme.typography.titleMedium)
                        }
                        Text(mode.honestDescription, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (state.mode.usesMicrophone) {
                item {
                    SectionCard(title = "What happens to the audio") {
                        Text(
                            "The microphone is open for 30 seconds out of every minute while you " +
                                "sleep, and closed the rest of the time.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Each second of audio is reduced to a handful of numbers - how loud, " +
                                "how bright, how much it changed - and the audio itself is " +
                                "overwritten immediately by the next read. Nothing is written to " +
                                "disk. There is no recording, no cache, and no debug mode that " +
                                "keeps raw audio, deliberately.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Those numbers cannot be turned back into sound and cannot recover " +
                                "speech. And the app has no internet permission, so nothing could " +
                                "be sent anywhere even if it existed.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Note(
                            "Your phone will show its microphone indicator while this is running. " +
                                "That is correct, and it is meant to.",
                        )
                        Note(
                            "This uses noticeably more battery than movement-only sensing, and a " +
                                "fan, traffic or another person in the room will defeat it.",
                            kind = NoteKind.WARNING,
                        )
                    }
                }
            }

            if (state.mode != SensingMode.OFF) {
                item {
                    SectionCard(title = "Requirements") {
                        RequirementRow(
                            label = "Battery optimisation exemption",
                            met = state.batteryExempt,
                            detail = if (state.batteryExempt) {
                                "Granted. Sensing can run through the night."
                            } else {
                                "Not granted. Android will suspend the app for long stretches, so " +
                                    "estimates will have large gaps. Cues are unaffected."
                            },
                        )
                        RequirementRow(
                            label = "Accelerometer",
                            met = state.motionAvailable,
                            detail = if (state.motionAvailable) "Present." else "Not present on this device.",
                        )
                        if (state.mode.usesMicrophone) {
                            RequirementRow(
                                label = "Microphone permission",
                                met = state.hasMicPermission,
                                detail = if (state.hasMicPermission) "Granted." else "Not granted.",
                            )
                            Note(
                                "Microphone sensing only works on nights you start the session " +
                                    "yourself from the app. Android does not allow a service " +
                                    "started automatically in the background to use the " +
                                    "microphone - on those nights the app falls back to movement " +
                                    "only and records that it did.",
                                kind = NoteKind.WARNING,
                            )
                        }
                    }
                }

                item {
                    SectionCard(title = "Adaptive cue timing") {
                        Text(SchedulingMode.ADAPTIVE.description, style = MaterialTheme.typography.bodyMedium)
                        SwitchRow(
                            title = "Let estimates nudge cue times",
                            checked = state.schedulingMode == SchedulingMode.ADAPTIVE,
                            onCheckedChange = vm::setAdaptive,
                            subtitle = "Only affects cues whose rule has a sleep-stage condition set.",
                        )
                        Note(
                            "Adaptive timing can delay a cue by up to 40 minutes, and with the " +
                                "strictest condition it can skip one. If you would rather never " +
                                "miss a cue, leave this off.",
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Battery") {
                    Text(
                        "Movement-only sensing costs very little - the accelerometer batches " +
                            "readings in hardware and the processor mostly stays asleep. " +
                            "Microphone sensing is considerably more expensive; expect a " +
                            "noticeably lower battery level by morning.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.lastNightSamples > 0) {
                item {
                    SectionCard(title = "Last night") {
                        Text(
                            "${state.lastNightSamples} estimates recorded, " +
                                "${state.lastNightUsable} of them confident enough to act on.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.lastNightUsable == 0) {
                            Note(
                                "None were usable. That normally means the app was suspended by " +
                                    "battery optimisation, or the room was too noisy.",
                                kind = NoteKind.WARNING,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequirementRow(label: String, met: Boolean, detail: String) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (met) "OK" else "!", style = MaterialTheme.typography.labelLarge,
                color = if (met) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
