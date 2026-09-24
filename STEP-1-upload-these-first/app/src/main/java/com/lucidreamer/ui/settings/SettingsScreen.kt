// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lucidreamer.BuildConfig
import com.lucidreamer.domain.cue.SchedulingMode
import com.lucidreamer.ui.components.DropdownField
import com.lucidreamer.ui.components.Note
import com.lucidreamer.ui.components.NoteKind
import com.lucidreamer.ui.components.SectionCard
import com.lucidreamer.ui.components.SettingRow
import com.lucidreamer.ui.components.SwitchRow
import com.lucidreamer.ui.containerViewModel
import com.lucidreamer.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    onOpenSchedule: () -> Unit,
    onOpenCues: () -> Unit,
    onOpenWbtb: () -> Unit,
    onOpenRealityChecks: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenReliability: () -> Unit,
    onOpenDeveloper: () -> Unit,
    onOpenSensing: () -> Unit,
    onOpenExperiments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = containerViewModel { SettingsViewModel(it) }
    val state by vm.state.collectAsState()

    // Developer mode is reached the traditional way: repeated taps on the
    // version number. Kept out of the way of people who do not need it.
    var versionTaps by remember { mutableIntStateOf(0) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "Sleep schedule") {
                SettingRow(
                    "Bedtime, sleep onset and wake time",
                    "Day-specific schedules and one-off changes",
                    onClick = onOpenSchedule,
                )
            }
        }

        item {
            SectionCard(title = "Audio cues") {
                SwitchRow(
                    title = "Play cues at night",
                    checked = state.audioCuesEnabled,
                    onCheckedChange = vm::setAudioCuesEnabled,
                    subtitle = "Turn off to use only the journal and reality checks.",
                )
                SettingRow("Cue profiles and sounds", "What plays, when, and how loudly", onClick = onOpenCues)

                SwitchRow(
                    title = "Precise timing",
                    checked = state.preciseAlarms,
                    onCheckedChange = vm::setPreciseAlarms,
                    subtitle = "On: cues fire exactly on time, and Android shows the next cue as " +
                        "the \"next alarm\" on your lock screen. Off: cues may drift by up to an " +
                        "hour, but your morning alarm stays shown instead.",
                )
            }
        }

        item {
            SectionCard(title = "Wake Back To Bed") {
                SwitchRow(
                    title = "Enable WBTB",
                    checked = state.wbtbEnabled,
                    onCheckedChange = vm::setWbtbEnabled,
                    subtitle = "Wakes you part-way through the night, then resumes cues when you " +
                        "go back to bed.",
                )
                SettingRow("WBTB settings", "Timing, how long to stay up, what it says", onClick = onOpenWbtb)
            }
        }

        item {
            SectionCard(title = "Reality checks") {
                SwitchRow(
                    title = "Daytime reminders",
                    checked = state.realityChecksEnabled,
                    onCheckedChange = vm::setRealityChecksEnabled,
                )
                SettingRow("Reminder schedules and prompts", "Times, days, and your own wording", onClick = onOpenRealityChecks)
            }
        }

        item {
            SectionCard(title = "Sleep sensing") {
                SettingRow(
                    title = "Sleep-stage estimation",
                    subtitle = "Currently: ${state.sensingMode.label}" +
                        if (state.schedulingMode == SchedulingMode.ADAPTIVE) ", adaptive cue timing on" else "",
                    onClick = onOpenSensing,
                )
                Note(
                    "Experimental, and off by default. A phone cannot measure sleep stages - what " +
                        "this produces is a guess with a confidence value, and when the guess is " +
                        "weak the app falls back to your fixed schedule.",
                    kind = NoteKind.EXPERIMENTAL,
                )
            }
        }

        item {
            SectionCard(title = "Experiments") {
                SettingRow(
                    title = "Compare two cue setups",
                    subtitle = "Alternate between profiles and record how each night went",
                    onClick = onOpenExperiments,
                )
            }
        }

        item {
            SectionCard(title = "Reliability") {
                SettingRow(
                    "Check this phone will work overnight",
                    "Permissions, battery settings, audio output, and a test cue",
                    onClick = onOpenReliability,
                )
            }
        }

        item {
            SectionCard(title = "Appearance") {
                DropdownField(
                    label = "Theme",
                    value = state.themeMode,
                    options = ThemeMode.entries,
                    optionLabel = {
                        when (it) {
                            ThemeMode.SYSTEM -> "Follow system"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                            ThemeMode.NIGHT -> "Night (very dim, red)"
                        }
                    },
                    onChange = vm::setThemeMode,
                )
                SwitchRow(
                    title = "Dim red screen during a session",
                    checked = state.nightModeDuringSession,
                    onCheckedChange = vm::setNightModeDuringSession,
                    subtitle = "Checking the app at 4am without a face full of blue light.",
                )
                SwitchRow(
                    title = "Use system colours",
                    checked = state.dynamicColour,
                    onCheckedChange = vm::setDynamicColour,
                    subtitle = "Material You. Off by default - it can pull the palette somewhere " +
                        "less calm.",
                )
            }
        }

        item {
            SectionCard(title = "Privacy and data") {
                SwitchRow(
                    title = "Keep statistics",
                    checked = state.statisticsEnabled,
                    onCheckedChange = vm::setStatisticsEnabled,
                    subtitle = "Counts of nights, cues and dreams. Stored only on this device.",
                )
                SettingRow(
                    "Privacy, permissions and export",
                    "What each permission does, and how to back up or erase your data",
                    onClick = onOpenPrivacy,
                )
            }
        }

        item {
            SectionCard(title = "About") {
                SettingRow(
                    title = "Lucid Dreamer ${BuildConfig.VERSION_NAME}",
                    subtitle = "Free and open source, licensed under the GPL-3.0. No accounts, " +
                        "no ads, no tracking, and no internet permission at all.",
                    onClick = {
                        versionTaps++
                        if (versionTaps >= 7) {
                            versionTaps = 0
                            vm.setDeveloperMode(true)
                        }
                    },
                )
                if (state.developerMode) {
                    SettingRow(
                        "Developer options",
                        "Diagnostics, sensor and alarm status, event log",
                        onClick = onOpenDeveloper,
                    )
                    SwitchRow(
                        title = "Developer mode",
                        checked = true,
                        onCheckedChange = { vm.setDeveloperMode(false) },
                    )
                }
            }
        }

        item {
            Text(
                "Nothing in this app is a medical device, and nothing here can guarantee a lucid " +
                    "dream. It is a scheduling and self-experimentation tool.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
