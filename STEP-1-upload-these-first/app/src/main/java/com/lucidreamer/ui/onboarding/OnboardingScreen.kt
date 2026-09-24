// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lucidreamer.domain.cue.Technique
import com.lucidreamer.domain.schedule.ScheduleBasis
import com.lucidreamer.ui.components.DropdownField
import com.lucidreamer.ui.components.DurationField
import com.lucidreamer.ui.components.EstimatedChip
import com.lucidreamer.ui.components.Note
import com.lucidreamer.ui.components.NoteKind
import com.lucidreamer.ui.components.SectionCard
import com.lucidreamer.ui.components.SwitchRow
import com.lucidreamer.ui.components.TimeField
import com.lucidreamer.ui.components.display
import com.lucidreamer.ui.containerViewModel

/**
 * First-run setup.
 *
 * Every step can be skipped, and the whole thing can be skipped at once. The
 * answers only pick starting values - nothing here is a decision the user is
 * stuck with, and all of it is reachable in Settings afterwards.
 */
@Composable
fun OnboardingScreen(modifier: Modifier = Modifier) {
    val vm = containerViewModel { OnboardingViewModel(it) }
    val state by vm.state.collectAsState()

    Scaffold(modifier = modifier.fillMaxSize()) { insets ->
        Column(
            Modifier
                .padding(insets)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LinearProgressIndicator(
                progress = { (state.step + 1) / OnboardingViewModel.STEP_COUNT.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )

            when (state.step) {
                0 -> Welcome()
                1 -> BedtimeStep(state, vm)
                2 -> WakeStep(state, vm)
                3 -> WeekendStep(state, vm)
                4 -> TechniqueStep(state, vm)
                5 -> FeaturesStep(state, vm)
                else -> DoneStep()
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.step > 0) {
                    OutlinedButton(onClick = vm::back, modifier = Modifier.weight(1f)) { Text("Back") }
                }
                Button(onClick = vm::next, modifier = Modifier.weight(1f)) {
                    Text(if (state.step >= OnboardingViewModel.STEP_COUNT - 1) "Finish" else "Next")
                }
            }

            TextButton(onClick = vm::skipEverything, modifier = Modifier.fillMaxWidth()) {
                Text("Skip setup - I'll configure it later")
            }
        }
    }
}

@Composable
private fun Welcome() {
    Text("Lucid Dreamer", style = MaterialTheme.typography.displaySmall)
    Text(
        "A toolkit for practising lucid dreaming: timed audio cues through the night, a dream " +
            "journal, daytime reality checks and Wake Back To Bed.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Note(
        "Nothing here can make you lucid dream, and the app cannot measure your sleep - a phone " +
            "has no way to do that. What it can do is put a cue where you asked for it, keep your " +
            "journal, and let you experiment properly.",
    )
    Note(
        "Everything stays on this phone. The app has no internet permission at all, so it is " +
            "structurally incapable of sending your data anywhere.",
        kind = NoteKind.INFO,
    )
    Text(
        "A few questions to set sensible defaults. You can skip any of them.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BedtimeStep(state: OnboardingState, vm: OnboardingViewModel) {
    Text("When do you go to bed?", style = MaterialTheme.typography.headlineMedium)
    SectionCard {
        TimeField(
            label = "I get into bed at",
            value = state.anchors.bedTime,
            onChange = { vm.setAnchors(state.anchors.copy(bedTime = it)) },
        )
        DurationField(
            label = "I usually fall asleep after",
            value = state.anchors.onsetLatency,
            onChange = { vm.setAnchors(state.anchors.copy(onsetLatency = it)) },
            maxHours = 4,
            supporting = "A rough guess is fine. Most people take 10-30 minutes.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "So you're asleep around ${state.anchors.bedTime.plus(state.anchors.onsetLatency).display()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            EstimatedChip()
        }
    }
    Note(
        "These are kept as two separate values on purpose. Getting into bed is not falling " +
            "asleep, and a cue set for \"5 hours after you fall asleep\" would otherwise land " +
            "half an hour early every night.",
    )
}

@Composable
private fun WakeStep(state: OnboardingState, vm: OnboardingViewModel) {
    Text("When do you get up?", style = MaterialTheme.typography.headlineMedium)
    SectionCard {
        DropdownField(
            label = "Which is fixed for you?",
            value = state.anchors.basis,
            options = ScheduleBasis.entries,
            optionLabel = {
                when (it) {
                    ScheduleBasis.WAKE_TIME -> "I wake at a particular time"
                    ScheduleBasis.DURATION -> "I sleep for a particular length"
                }
            },
            onChange = { vm.setAnchors(state.anchors.copy(basis = it)) },
            supporting = "If you go to bed late, does your alarm stay put, or do you sleep in?",
        )

        when (state.anchors.basis) {
            ScheduleBasis.WAKE_TIME -> TimeField(
                label = "I wake up at",
                value = state.anchors.wakeTime,
                onChange = { vm.setAnchors(state.anchors.copy(wakeTime = it)) },
            )

            ScheduleBasis.DURATION -> DurationField(
                label = "I sleep for about",
                value = state.anchors.sleepDuration,
                onChange = { vm.setAnchors(state.anchors.copy(sleepDuration = it)) },
                maxHours = 14,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "About ${state.anchors.impliedDuration().display()} of sleep",
                style = MaterialTheme.typography.bodyMedium,
            )
            EstimatedChip()
        }
    }
    Note("Six hours, ten hours, or sleeping during the day are all fine - nothing here assumes a shape.")
}

@Composable
private fun WeekendStep(state: OnboardingState, vm: OnboardingViewModel) {
    Text("Does your schedule change?", style = MaterialTheme.typography.headlineMedium)
    SectionCard {
        SwitchRow(
            title = "Different at weekends",
            checked = state.differentAtWeekends,
            onCheckedChange = vm::setDifferentAtWeekends,
            subtitle = "Adds a second schedule for Friday and Saturday nights, which you can edit " +
                "later. You can add per-day schedules for anything more irregular.",
        )

        if (state.differentAtWeekends) {
            TimeField(
                label = "Weekend: into bed at",
                value = state.weekendAnchors.bedTime,
                onChange = { vm.setWeekendAnchors(state.weekendAnchors.copy(bedTime = it)) },
            )
            TimeField(
                label = "Weekend: wake at",
                value = state.weekendAnchors.wakeTime,
                onChange = { vm.setWeekendAnchors(state.weekendAnchors.copy(wakeTime = it)) },
            )
        }

        SwitchRow(
            title = "I often wake during the night",
            checked = state.wakesDuringNight,
            onCheckedChange = vm::setWakesDuringNight,
            subtitle = "Cues around a habitual waking can be suppressed, so the app is not " +
                "cueing you while you are already awake.",
        )
    }
}

@Composable
private fun TechniqueStep(state: OnboardingState, vm: OnboardingViewModel) {
    Text("Which approach interests you?", style = MaterialTheme.typography.headlineMedium)
    SectionCard {
        DropdownField(
            label = "Technique",
            value = state.technique,
            options = Technique.entries,
            optionLabel = { it.displayName },
            onChange = vm::setTechnique,
        )
        Text(state.technique.blurb, style = MaterialTheme.typography.bodyMedium)
    }
    Note(
        "This only picks a starting cue profile. The evidence for all of these is thin and highly " +
            "individual, so the app does not push one - you can change everything, and comparing " +
            "them yourself is rather the point.",
    )
}

@Composable
private fun FeaturesStep(state: OnboardingState, vm: OnboardingViewModel) {
    Text("What should be switched on?", style = MaterialTheme.typography.headlineMedium)
    SectionCard {
        SwitchRow(
            title = "Audio cues at night",
            checked = state.audioCues,
            onCheckedChange = vm::setAudioCues,
            subtitle = "Quiet sounds during the last part of the night.",
        )
        SwitchRow(
            title = "Wake Back To Bed",
            checked = state.wbtb,
            onCheckedChange = vm::setWbtb,
            subtitle = "Wakes you part-way through the night. Effective for many people, and it " +
                "does cost you sleep.",
        )
        SwitchRow(
            title = "Daytime reality-check reminders",
            checked = state.realityChecks,
            onCheckedChange = vm::setRealityChecks,
        )
        SwitchRow(
            title = "I start each night myself",
            checked = state.manualStart,
            onCheckedChange = vm::setManualStart,
            subtitle = "Nothing runs until you tap \"I'm going to sleep now\". This also gives " +
                "the app a real sleep onset rather than an estimate.",
        )
        SwitchRow(
            title = "Sleep-stage estimation, when available",
            checked = state.sensorEstimation,
            onCheckedChange = vm::setSensorEstimation,
            subtitle = "Not implemented yet. This only records that you want it.",
        )
    }
    Note(
        "Sleep-stage estimation is genuinely hard and a phone cannot do it accurately. When it " +
            "arrives it will be labelled as an estimate with a confidence value, and it will be " +
            "possible to turn off entirely.",
        kind = NoteKind.EXPERIMENTAL,
    )
}

@Composable
private fun DoneStep() {
    Text("That's everything", style = MaterialTheme.typography.headlineMedium)
    Text(
        "You can change all of it in Settings. Before relying on the app for a real night, it is " +
            "worth visiting Settings > Reliability - Android and phone manufacturers stop " +
            "background apps aggressively, and that screen checks what is actually needed.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Note("Tap Finish to go to tonight's dashboard.")
}
