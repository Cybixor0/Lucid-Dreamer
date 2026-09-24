// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.experiments

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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lucidreamer.data.db.entity.NightOutcome
import com.lucidreamer.ui.components.DropdownField
import com.lucidreamer.ui.components.EmptyState
import com.lucidreamer.ui.components.Note
import com.lucidreamer.ui.components.NoteKind
import com.lucidreamer.ui.components.SectionCard
import com.lucidreamer.ui.components.StatValue
import com.lucidreamer.ui.containerViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentsScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val vm = containerViewModel { ExperimentsViewModel(it) }
    val state by vm.state.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Experiments") },
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
                    "Compare two cue setups by alternating between them and recording how each " +
                        "night went. This is a self-experiment: one person, no blinding, and you " +
                        "score the outcome yourself. It cannot prove one setup is better - what " +
                        "it can do is stop you misremembering which one you were actually running.",
                )
            }

            // Nights that finished without an outcome. Asking promptly is the
            // difference between real data and vague recollection.
            if (state.awaitingOutcome.isNotEmpty()) {
                item { Text("How did these nights go?", style = MaterialTheme.typography.titleLarge) }

                items(state.awaitingOutcome, key = { it.run.id }) { pending ->
                    SectionCard {
                        Text(
                            dayFormat.format(LocalDate.ofEpochDay(pending.run.nightEpochDay)),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Arm ${pending.armLabel} - ${pending.profileName}, " +
                                "${pending.run.cuesPlayed} cue(s) played",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            NightOutcome.entries
                                .filter { it != NightOutcome.NOT_RECORDED }
                                .chunked(2)
                                .forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        row.forEach { outcome ->
                                            FilterChip(
                                                selected = false,
                                                onClick = { vm.recordOutcome(pending.run.id, outcome) },
                                                label = { Text(outcome.label) },
                                            )
                                        }
                                    }
                                }
                        }
                    }
                }
            }

            if (state.experiments.isEmpty()) {
                item {
                    EmptyState(
                        title = "No experiments yet",
                        body = "Pick two cue profiles and the app will alternate between them, " +
                            "then ask each morning how the night went.",
                    )
                }
            }

            items(state.experiments, key = { it.experiment.id }) { summary ->
                SectionCard(title = summary.experiment.name) {
                    if (summary.experiment.notes.isNotBlank()) {
                        Text(summary.experiment.notes, style = MaterialTheme.typography.bodySmall)
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatValue(
                            "${summary.armA.lucid}/${summary.armA.nights}",
                            "${summary.armA.label}: lucid nights",
                        )
                        StatValue(
                            "${summary.armB.lucid}/${summary.armB.nights}",
                            "${summary.armB.label}: lucid nights",
                        )
                    }

                    Text(summary.verdict(), style = MaterialTheme.typography.bodyMedium)

                    if (summary.armA.disturbed > 0 || summary.armB.disturbed > 0) {
                        Note(
                            "Nights that woke you: ${summary.armA.label} ${summary.armA.disturbed}, " +
                                "${summary.armB.label} ${summary.armB.disturbed}. A setup that " +
                                "costs you sleep is not a good setup, even if it produces the " +
                                "occasional lucid dream.",
                            kind = NoteKind.WARNING,
                        )
                    }

                    if (summary.nightsPending > 0) {
                        Text(
                            "${summary.nightsPending} night(s) still waiting for an outcome.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (summary.experiment.active) {
                            OutlinedButton(onClick = { vm.setActive(summary.experiment.id, false) }) {
                                Text("Stop")
                            }
                        } else {
                            OutlinedButton(onClick = { vm.setActive(summary.experiment.id, true) }) {
                                Text("Resume")
                            }
                        }
                        TextButton(onClick = { vm.delete(summary.experiment.id) }) { Text("Delete") }
                    }
                }
            }

            item { NewExperimentCard(state, vm) }
        }
    }
}

@Composable
private fun NewExperimentCard(state: ExperimentsUiState, vm: ExperimentsViewModel) {
    SectionCard(title = "New experiment") {
        if (state.availableProfiles.size < 2) {
            Text(
                "You need at least two cue profiles to compare. Create another one under Cues.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }

        OutlinedTextField(
            value = state.draftName,
            onValueChange = vm::setDraftName,
            label = { Text("Name") },
            placeholder = { Text("Bell vs voice") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        DropdownField(
            label = "Arm A",
            value = state.draftArmA,
            options = state.availableProfiles,
            optionLabel = { it.name },
            onChange = vm::setDraftArmA,
        )

        DropdownField(
            label = "Arm B",
            value = state.draftArmB,
            options = state.availableProfiles,
            optionLabel = { it.name },
            onChange = vm::setDraftArmB,
        )

        Note(
            "Nights are assigned at random by default, which avoids one arm accidentally lining " +
                "up with, say, every weekend. Assignment is fixed per date, so it stays the same " +
                "if you reinstall from a backup.",
        )

        Button(
            onClick = vm::createExperiment,
            enabled = state.draftArmA.id != state.draftArmB.id && state.draftName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start experiment") }

        if (state.draftArmA.id == state.draftArmB.id) {
            Text(
                "Pick two different profiles.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
