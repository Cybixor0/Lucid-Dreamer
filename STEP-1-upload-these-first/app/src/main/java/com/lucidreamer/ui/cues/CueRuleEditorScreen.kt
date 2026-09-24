// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.cues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lucidreamer.domain.cue.BuiltInTone
import com.lucidreamer.domain.cue.CueRule
import com.lucidreamer.domain.cue.CueSound
import com.lucidreamer.domain.cue.RouteFallback
import com.lucidreamer.domain.cue.TimeWindow
import com.lucidreamer.domain.cue.Timing
import com.lucidreamer.ui.components.DropdownField
import com.lucidreamer.ui.components.DurationField
import com.lucidreamer.ui.components.Note
import com.lucidreamer.ui.components.NoteKind
import com.lucidreamer.ui.components.SectionCard
import com.lucidreamer.ui.components.SettingRow
import com.lucidreamer.ui.components.SwitchRow
import com.lucidreamer.ui.components.TimeField
import com.lucidreamer.ui.components.display
import com.lucidreamer.ui.containerViewModel
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

/** The user-facing name for each timing variant, and what it actually means. */
private enum class TimingKind(val label: String, val blurb: String) {
    AFTER_ONSET("After I fall asleep", "Follows a late night. The usual choice."),
    ABSOLUTE("At a clock time", "Fixed time, regardless of when you fell asleep."),
    AFTER_BEDTIME("After I get into bed", "Ignores how long you take to fall asleep."),
    BEFORE_WAKE("Before my alarm", "Counts back from when you get up."),
    FRACTION("Part-way through the night", "Scales with how long you actually sleep."),
    REPEATING("Repeating", "Every so often across a window."),
    RANDOM("Random times", "Unpredictable, so you don't learn to sleep through it."),
    AFTER_WBTB("After going back to bed", "Anchored to Wake Back To Bed.")
}

private fun Timing.kind(): TimingKind = when (this) {
    is Timing.AfterSleepOnset -> TimingKind.AFTER_ONSET
    is Timing.AbsoluteTime -> TimingKind.ABSOLUTE
    is Timing.AfterBedtime -> TimingKind.AFTER_BEDTIME
    is Timing.BeforeWake -> TimingKind.BEFORE_WAKE
    is Timing.FractionOfNight -> TimingKind.FRACTION
    is Timing.Repeating -> TimingKind.REPEATING
    is Timing.RandomInWindow -> TimingKind.RANDOM
    is Timing.AfterWbtbReturn -> TimingKind.AFTER_WBTB
}

private fun TimingKind.default(): Timing = when (this) {
    TimingKind.AFTER_ONSET -> Timing.AfterSleepOnset(Duration.ofHours(5))
    TimingKind.ABSOLUTE -> Timing.AbsoluteTime(LocalTime.of(5, 0))
    TimingKind.AFTER_BEDTIME -> Timing.AfterBedtime(Duration.ofHours(5))
    TimingKind.BEFORE_WAKE -> Timing.BeforeWake(Duration.ofMinutes(45))
    TimingKind.FRACTION -> Timing.FractionOfNight(0.75f)
    TimingKind.REPEATING -> Timing.Repeating(
        window = TimeWindow.UntilWake(Duration.ofHours(5), Duration.ofMinutes(20)),
        interval = Duration.ofMinutes(30),
        jitter = Duration.ofMinutes(3),
        maxCount = 6,
    )
    TimingKind.RANDOM -> Timing.RandomInWindow(
        window = TimeWindow.RelativeToOnset(Duration.ofHours(5), Duration.ofHours(7)),
        count = 3,
        minGap = Duration.ofMinutes(20),
    )
    TimingKind.AFTER_WBTB -> Timing.AfterWbtbReturn(Duration.ofMinutes(20))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CueRuleEditorScreen(
    profileId: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = containerViewModel(key = "cue-$profileId") { CueEditorViewModel(it, profileId) }
    val state by vm.state.collectAsState()

    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    val profile = state.profile

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(profile?.name ?: "Cue profile") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = { TextButton(onClick = vm::save) { Text("Save") } },
            )
        },
    ) { insets ->
        if (profile == null) return@Scaffold

        LazyColumn(
            Modifier.padding(insets).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(title = "Profile") {
                    OutlinedTextField(
                        value = profile.name,
                        onValueChange = { name -> vm.update { it.copy(name = name) } },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = profile.description,
                        onValueChange = { d -> vm.update { it.copy(description = d) } },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // The preview is the most useful thing on this screen: it shows
            // what the rules would actually do tonight, and why anything that
            // was dropped got dropped.
            item { PreviewCard(state) }

            itemsIndexed(profile.rules, key = { _, rule -> rule.id }) { index, rule ->
                RuleCard(
                    rule = rule,
                    onChange = { transform -> vm.updateRule(index, transform) },
                    onDelete = { vm.removeRule(index) },
                    onTest = { vm.testRule(rule) },
                )
            }

            item {
                Button(
                    onClick = {
                        vm.addRule(
                            CueRule(
                                label = "New cue",
                                timing = Timing.AfterSleepOnset(Duration.ofHours(5)),
                                sound = CueSound.BuiltIn(BuiltInTone.SOFT_BELL),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add a cue") }
            }

            item { ConstraintsCard(state, vm) }
        }
    }
}

@Composable
private fun PreviewCard(state: CueEditorState) {
    val plan = state.preview ?: return

    SectionCard(title = "What this would do tonight") {
        Text(plan.explanation, style = MaterialTheme.typography.bodyMedium)

        if (plan.cues.isEmpty()) {
            Text(
                "No cues would play.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            plan.cues.forEach { cue ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        hhmm.format(cue.at),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        cue.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (plan.dropped.isNotEmpty()) {
            Text(
                "Not scheduled:",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            plan.dropped.take(8).forEach { dropped ->
                Text(
                    "- ${dropped.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Note("Times are based on tonight's schedule. A different bedtime moves them.")
    }
}

@Composable
private fun RuleCard(
    rule: CueRule,
    onChange: ((CueRule) -> CueRule) -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = rule.label,
                onValueChange = { label -> onChange { it.copy(label = label) } },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = rule.enabled,
                onCheckedChange = { enabled -> onChange { it.copy(enabled = enabled) } },
                modifier = Modifier.padding(start = 8.dp),
            )
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove") }
        }

        // --- Timing ---------------------------------------------------------
        DropdownField(
            label = "When",
            value = rule.timing.kind(),
            options = TimingKind.entries,
            optionLabel = { it.label },
            onChange = { kind -> onChange { it.copy(timing = kind.default()) } },
            supporting = rule.timing.kind().blurb,
        )

        TimingFields(rule.timing) { timing -> onChange { it.copy(timing = timing) } }

        // --- Sound ----------------------------------------------------------
        SoundFields(rule.sound) { sound -> onChange { it.copy(sound = sound) } }

        // --- Playback -------------------------------------------------------
        SettingRow(
            title = "Volume",
            subtitle = "${(rule.playback.volume * 100).toInt()}% - independent of your phone's " +
                "volume, so a cue can be genuinely faint",
        )
        Slider(
            value = rule.playback.volume,
            onValueChange = { v -> onChange { it.copy(playback = it.playback.copy(volume = v)) } },
            valueRange = 0.01f..1f,
        )

        DurationField(
            label = "Fade in",
            value = rule.playback.fadeIn,
            onChange = { d -> onChange { it.copy(playback = it.playback.copy(fadeIn = d)) } },
            maxHours = 0,
        )
        DurationField(
            label = "Fade out",
            value = rule.playback.fadeOut,
            onChange = { d -> onChange { it.copy(playback = it.playback.copy(fadeOut = d)) } },
            maxHours = 0,
        )

        SettingRow(title = "Repeat", subtitle = "${rule.playback.repeatCount} time(s)")
        Slider(
            value = rule.playback.repeatCount.toFloat(),
            onValueChange = { v -> onChange { it.copy(playback = it.playback.copy(repeatCount = v.toInt())) } },
            valueRange = 1f..5f,
            steps = 3,
        )

        SwitchRow(
            title = "Vibrate as well",
            checked = rule.playback.vibrate,
            onCheckedChange = { v -> onChange { it.copy(playback = it.playback.copy(vibrate = v)) } },
            subtitle = "Useful if you share a bed",
        )

        SwitchRow(
            title = "Only with headphones",
            checked = rule.playback.requireHeadphones,
            onCheckedChange = { v -> onChange { it.copy(playback = it.playback.copy(requireHeadphones = v)) } },
        )

        if (rule.playback.requireHeadphones) {
            DropdownField(
                label = "If headphones are disconnected",
                value = rule.playback.routeFallback,
                options = RouteFallback.entries,
                optionLabel = {
                    when (it) {
                        RouteFallback.SPEAKER -> "Play through the speaker"
                        RouteFallback.SPEAKER_REDUCED -> "Play quietly through the speaker"
                        RouteFallback.SKIP -> "Skip the cue"
                    }
                },
                onChange = { f -> onChange { it.copy(playback = it.playback.copy(routeFallback = f)) } },
                supporting = "Earbuds often disconnect overnight. Full speaker volume at 4am can " +
                    "wake a partner, so quieter is the default.",
            )
        }

        SwitchRow(
            title = "Duck other audio",
            checked = rule.playback.requestAudioFocus,
            onCheckedChange = { v -> onChange { it.copy(playback = it.playback.copy(requestAudioFocus = v)) } },
            subtitle = "Briefly lowers anything else playing, such as white noise. Turn off to " +
                "layer the cue over it instead.",
        )

        TextButton(onClick = onTest) {
            Icon(Icons.Default.PlayArrow, null)
            Text("  Hear it")
        }
    }
}

@Composable
private fun TimingFields(timing: Timing, onChange: (Timing) -> Unit) {
    when (timing) {
        is Timing.AfterSleepOnset -> DurationField(
            "How long after falling asleep", timing.offset, { onChange(Timing.AfterSleepOnset(it)) },
        )

        is Timing.AfterBedtime -> DurationField(
            "How long after getting into bed", timing.offset, { onChange(Timing.AfterBedtime(it)) },
        )

        is Timing.BeforeWake -> DurationField(
            "How long before my alarm", timing.offset, { onChange(Timing.BeforeWake(it)) }, maxHours = 6,
        )

        is Timing.AbsoluteTime -> TimeField(
            "Time", timing.at, { onChange(Timing.AbsoluteTime(it)) },
        )

        is Timing.AfterWbtbReturn -> DurationField(
            "How long after going back to bed", timing.offset, { onChange(Timing.AfterWbtbReturn(it)) }, maxHours = 3,
        )

        is Timing.FractionOfNight -> {
            SettingRow(
                title = "How far through the night",
                subtitle = "${(timing.fraction * 100).toInt()}%",
            )
            Slider(
                value = timing.fraction,
                onValueChange = { onChange(Timing.FractionOfNight(it)) },
                valueRange = 0.1f..0.95f,
            )
        }

        is Timing.Repeating -> {
            WindowFields(timing.window) { onChange(timing.copy(window = it)) }
            DurationField("Every", timing.interval, { onChange(timing.copy(interval = it)) }, maxHours = 4)
            DurationField(
                "Random variation", timing.jitter, { onChange(timing.copy(jitter = it)) }, maxHours = 1,
                supporting = "Shifts each cue a little so the timing is not perfectly regular.",
            )
            SettingRow(title = "At most", subtitle = "${timing.maxCount} cues from this rule")
            Slider(
                value = timing.maxCount.toFloat(),
                onValueChange = { onChange(timing.copy(maxCount = it.toInt())) },
                valueRange = 1f..24f,
            )
        }

        is Timing.RandomInWindow -> {
            WindowFields(timing.window) { onChange(timing.copy(window = it)) }
            SettingRow(title = "How many", subtitle = "${timing.count} cues")
            Slider(
                value = timing.count.toFloat(),
                onValueChange = { onChange(timing.copy(count = it.toInt())) },
                valueRange = 1f..12f,
            )
            DurationField(
                "At least this far apart", timing.minGap, { onChange(timing.copy(minGap = it)) }, maxHours = 3,
            )
        }
    }
}

private enum class WindowKind(val label: String) {
    RELATIVE("Relative to falling asleep"),
    ABSOLUTE("Between two clock times"),
    UNTIL_WAKE("From a point until my alarm"),
}

@Composable
private fun WindowFields(window: TimeWindow, onChange: (TimeWindow) -> Unit) {
    val kind = when (window) {
        is TimeWindow.RelativeToOnset -> WindowKind.RELATIVE
        is TimeWindow.Absolute -> WindowKind.ABSOLUTE
        is TimeWindow.UntilWake -> WindowKind.UNTIL_WAKE
    }

    DropdownField(
        label = "Window",
        value = kind,
        options = WindowKind.entries,
        optionLabel = { it.label },
        onChange = {
            onChange(
                when (it) {
                    WindowKind.RELATIVE -> TimeWindow.RelativeToOnset(Duration.ofHours(5), Duration.ofHours(7))
                    WindowKind.ABSOLUTE -> TimeWindow.Absolute(LocalTime.of(4, 30), LocalTime.of(6, 30))
                    WindowKind.UNTIL_WAKE -> TimeWindow.UntilWake(Duration.ofHours(5), Duration.ofMinutes(20))
                },
            )
        },
    )

    when (window) {
        is TimeWindow.RelativeToOnset -> {
            DurationField("From", window.from, { onChange(window.copy(from = it)) })
            DurationField("Until", window.to, { onChange(window.copy(to = it)) })
        }

        is TimeWindow.Absolute -> {
            TimeField("From", window.from, { onChange(window.copy(from = it)) })
            TimeField("Until", window.to, { onChange(window.copy(to = it)) })
        }

        is TimeWindow.UntilWake -> {
            DurationField("Starting after", window.from, { onChange(window.copy(from = it)) })
            DurationField(
                "Stop before my alarm", window.stopBefore, { onChange(window.copy(stopBefore = it)) }, maxHours = 3,
            )
        }
    }
}

private enum class SoundKind(val label: String) {
    BUILT_IN("Built-in sound"),
    SPEECH("Spoken words"),
    SILENT("Vibration only"),
}

@Composable
private fun SoundFields(sound: CueSound, onChange: (CueSound) -> Unit) {
    val kind = when (sound) {
        is CueSound.BuiltIn -> SoundKind.BUILT_IN
        is CueSound.Speech -> SoundKind.SPEECH
        is CueSound.Silent -> SoundKind.SILENT
        // Imported files and recordings are chosen from the sound library
        // rather than created here; they display as built-in until changed.
        else -> SoundKind.BUILT_IN
    }

    DropdownField(
        label = "Sound",
        value = kind,
        options = SoundKind.entries,
        optionLabel = { it.label },
        onChange = {
            onChange(
                when (it) {
                    SoundKind.BUILT_IN -> CueSound.BuiltIn(BuiltInTone.SOFT_BELL)
                    SoundKind.SPEECH -> CueSound.Speech("Am I dreaming?")
                    SoundKind.SILENT -> CueSound.Silent
                },
            )
        },
    )

    when (sound) {
        is CueSound.BuiltIn -> DropdownField(
            label = "Tone",
            value = sound.tone,
            options = BuiltInTone.entries,
            optionLabel = { tone ->
                when (tone) {
                    BuiltInTone.SOFT_BELL -> "Soft bell"
                    BuiltInTone.PURE_TONE -> "Pure tone"
                    BuiltInTone.CHIME -> "Chime"
                    BuiltInTone.LOW_DRONE -> "Low drone"
                    BuiltInTone.NOISE_SWELL -> "Noise swell"
                    BuiltInTone.DOUBLE_BEEP -> "Double beep"
                }
            },
            onChange = { onChange(CueSound.BuiltIn(it)) },
            supporting = "Generated on the device - there are no audio files in this app.",
        )

        is CueSound.Speech -> {
            OutlinedTextField(
                value = sound.text,
                onValueChange = { onChange(CueSound.Speech(it, sound.voiceId)) },
                label = { Text("What to say") },
                modifier = Modifier.fillMaxWidth(),
            )
            Note(
                "Rendered to an audio file when the session starts, not spoken live - a speech " +
                    "engine waking up at 4am is a reliable way to get silence instead of a cue.",
            )
        }

        is CueSound.Silent -> Note(
            "Nothing will be played. Turn on \"Vibrate as well\" below, or this cue does nothing.",
            kind = NoteKind.WARNING,
        )

        else -> Unit
    }
}

@Composable
private fun ConstraintsCard(state: CueEditorState, vm: CueEditorViewModel) {
    val profile = state.profile ?: return
    val c = profile.constraints

    SectionCard(title = "Limits for the whole night") {
        Text(
            "Applied across every rule together. Rules compose, and it is easy to end up with " +
                "far more cues than you intended.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        DurationField(
            "Sleep at least this long first",
            c.minimumSleepBeforeFirstCue,
            { d -> vm.update { it.copy(constraints = it.constraints.copy(minimumSleepBeforeFirstCue = d)) } },
            supporting = "Protects the deep sleep early in the night, which is the part you most " +
                "need and the part cues are least likely to help with.",
        )

        DurationField(
            "Minimum gap between cues",
            c.minGapBetweenCues,
            { d -> vm.update { it.copy(constraints = it.constraints.copy(minGapBetweenCues = d)) } },
            maxHours = 4,
        )

        DurationField(
            "Stop before my alarm",
            c.stopBeforeWake,
            { d -> vm.update { it.copy(constraints = it.constraints.copy(stopBeforeWake = d)) } },
            maxHours = 3,
        )

        SettingRow(
            title = "Most cues per night",
            subtitle = c.maxCuesPerNight?.toString() ?: "No limit",
        )
        Slider(
            value = (c.maxCuesPerNight ?: 25).toFloat(),
            onValueChange = { v ->
                val n = v.toInt()
                vm.update {
                    it.copy(constraints = it.constraints.copy(maxCuesPerNight = if (n >= 25) null else n))
                }
            },
            valueRange = 0f..25f,
        )
        Text(
            "Slide to the far right for no limit.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
