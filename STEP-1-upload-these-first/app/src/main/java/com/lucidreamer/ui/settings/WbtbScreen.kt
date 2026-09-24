// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lucidreamer.domain.cue.BuiltInTone
import com.lucidreamer.domain.cue.CueSound
import com.lucidreamer.domain.wbtb.WbtbReturnMode
import com.lucidreamer.domain.wbtb.WbtbTiming
import com.lucidreamer.ui.components.DropdownField
import com.lucidreamer.ui.components.DurationField
import com.lucidreamer.ui.components.Note
import com.lucidreamer.ui.components.SectionCard
import com.lucidreamer.ui.components.SettingRow
import com.lucidreamer.ui.components.SwitchRow
import com.lucidreamer.ui.components.TimeField
import com.lucidreamer.ui.containerViewModel
import java.time.Duration
import java.time.LocalTime

private enum class WbtbTimingKind(val label: String) {
    AFTER_ONSET("A set time after I fall asleep"),
    ABSOLUTE("At a clock time"),
    BEFORE_WAKE("A set time before my alarm"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WbtbScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val vm = containerViewModel { SettingsViewModel(it) }
    val state by vm.state.collectAsState()
    val config = state.wbtbConfig

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Wake Back To Bed") },
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
                    "WBTB means waking part-way through the night, staying up briefly, then going " +
                        "back to sleep. It is the technique with the most consistent anecdotal " +
                        "support, and it costs you sleep - which is a real trade-off, not a " +
                        "detail. Everything below is optional.",
                )
            }

            item {
                SectionCard(title = "Enabled") {
                    SwitchRow(
                        title = "Use Wake Back To Bed",
                        checked = state.wbtbEnabled,
                        onCheckedChange = vm::setWbtbEnabled,
                    )
                }
            }

            item {
                SectionCard(title = "When to wake you") {
                    val kind = when (config.timing) {
                        is WbtbTiming.AfterSleepOnset -> WbtbTimingKind.AFTER_ONSET
                        is WbtbTiming.AbsoluteTime -> WbtbTimingKind.ABSOLUTE
                        is WbtbTiming.BeforeWake -> WbtbTimingKind.BEFORE_WAKE
                    }

                    DropdownField(
                        label = "Timing",
                        value = kind,
                        options = WbtbTimingKind.entries,
                        optionLabel = { it.label },
                        onChange = {
                            vm.setWbtbConfig(
                                config.copy(
                                    timing = when (it) {
                                        WbtbTimingKind.AFTER_ONSET ->
                                            WbtbTiming.AfterSleepOnset(Duration.ofHours(5).plusMinutes(30))
                                        WbtbTimingKind.ABSOLUTE -> WbtbTiming.AbsoluteTime(LocalTime.of(4, 30))
                                        WbtbTimingKind.BEFORE_WAKE -> WbtbTiming.BeforeWake(Duration.ofMinutes(90))
                                    },
                                ),
                            )
                        },
                    )

                    when (val t = config.timing) {
                        is WbtbTiming.AfterSleepOnset -> DurationField(
                            "Wake me after", t.offset,
                            { vm.setWbtbConfig(config.copy(timing = WbtbTiming.AfterSleepOnset(it))) },
                            supporting = "Five to six hours is the usual starting point - late " +
                                "enough that most deep sleep is behind you.",
                        )

                        is WbtbTiming.AbsoluteTime -> TimeField(
                            "Wake me at", t.at,
                            { vm.setWbtbConfig(config.copy(timing = WbtbTiming.AbsoluteTime(it))) },
                        )

                        is WbtbTiming.BeforeWake -> DurationField(
                            "Before my alarm", t.offset,
                            { vm.setWbtbConfig(config.copy(timing = WbtbTiming.BeforeWake(it))) },
                            maxHours = 6,
                        )
                    }
                }
            }

            item {
                SectionCard(title = "How long to stay up") {
                    DurationField(
                        "Awake for", config.awakeDuration,
                        { vm.setWbtbConfig(config.copy(awakeDuration = it)) },
                        maxHours = 2,
                        supporting = "Long enough to become properly awake, short enough to get " +
                            "back to sleep. Fifteen to thirty minutes is typical.",
                    )

                    DropdownField(
                        label = "When do cues resume?",
                        value = config.returnMode,
                        options = WbtbReturnMode.entries,
                        optionLabel = {
                            when (it) {
                                WbtbReturnMode.CONFIRMED -> "When I say I'm back in bed"
                                WbtbReturnMode.AUTOMATIC -> "Automatically, after the time above"
                            }
                        },
                        onChange = { vm.setWbtbConfig(config.copy(returnMode = it)) },
                        supporting = "Confirming is more accurate, since the whole point is to " +
                            "know when you went back to sleep.",
                    )

                    if (config.returnMode == WbtbReturnMode.CONFIRMED) {
                        DurationField(
                            "If I never confirm, resume after", config.fallbackAfter,
                            { vm.setWbtbConfig(config.copy(fallbackAfter = it)) },
                            maxHours = 3,
                            supporting = "In case you fall asleep without tapping - which is a " +
                                "success, not a failure.",
                        )
                    }
                }
            }

            item {
                SectionCard(title = "The wake-up itself") {
                    Text(
                        "Unlike a cue, this one is supposed to wake you, so it can be much more " +
                            "noticeable.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )

                    DropdownField(
                        label = "Sound",
                        value = (config.wakeSound as? CueSound.BuiltIn)?.tone ?: BuiltInTone.CHIME,
                        options = BuiltInTone.entries,
                        optionLabel = { it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase) },
                        onChange = { vm.setWbtbConfig(config.copy(wakeSound = CueSound.BuiltIn(it))) },
                    )

                    SettingRow("Volume", "${(config.wakeVolume * 100).toInt()}%")
                    Slider(
                        value = config.wakeVolume,
                        onValueChange = { vm.setWbtbConfig(config.copy(wakeVolume = it)) },
                        valueRange = 0.05f..1f,
                    )

                    SettingRow("Repeat", "${config.wakeRepeatCount} time(s)")
                    Slider(
                        value = config.wakeRepeatCount.toFloat(),
                        onValueChange = { vm.setWbtbConfig(config.copy(wakeRepeatCount = it.toInt())) },
                        valueRange = 1f..6f,
                        steps = 4,
                    )
                }
            }

            item {
                SectionCard(title = "While you're awake") {
                    SwitchRow(
                        title = "Speak an instruction",
                        checked = config.speakInstructions,
                        onCheckedChange = { vm.setWbtbConfig(config.copy(speakInstructions = it)) },
                        subtitle = "Read aloud when you wake. Often used for a MILD intention.",
                    )

                    if (config.speakInstructions) {
                        OutlinedTextField(
                            value = config.spokenInstructions,
                            onValueChange = { vm.setWbtbConfig(config.copy(spokenInstructions = it)) },
                            label = { Text("What to say") },
                            placeholder = { Text("Next time I'm dreaming, I will realise I'm dreaming.") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    SwitchRow(
                        title = "Offer to log a dream",
                        checked = config.journalPrompt,
                        onCheckedChange = { vm.setWbtbConfig(config.copy(journalPrompt = it)) },
                        subtitle = "Adds a shortcut to the notification. This is the best moment " +
                            "for recall - you have just come out of a REM period.",
                    )
                }
            }
        }
    }
}
