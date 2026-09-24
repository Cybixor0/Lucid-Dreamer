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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lucidreamer.ui.components.Note
import com.lucidreamer.ui.components.NoteKind
import com.lucidreamer.ui.containerViewModel

@Composable
fun CueProfileListScreen(
    onEditProfile: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = containerViewModel { CueProfileListViewModel(it) }
    val profiles by vm.profiles.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.createBlank(onEditProfile) }) {
                Icon(Icons.Default.Add, contentDescription = "New cue profile")
            }
        },
    ) { insets ->
        LazyColumn(
            Modifier.padding(insets).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Note(
                    "The selected profile is what runs tonight. Keep as many as you like and " +
                        "switch between them - that is how you find out what actually works for you.",
                )
            }

            items(profiles, key = { it.id }) { profile ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (profile.isActive) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = profile.isActive,
                                onClick = { vm.setActive(profile.id) },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (profile.ruleCount == 0) {
                                        "No cues - nothing will be played"
                                    } else {
                                        "${profile.ruleCount} rule${if (profile.ruleCount == 1) "" else "s"}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        if (profile.description.isNotBlank()) {
                            Text(profile.description, style = MaterialTheme.typography.bodySmall)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (profile.isPreset) {
                                // Presets stay intact so there is always a known-good
                                // starting point to come back to.
                                OutlinedButton(onClick = { vm.duplicate(profile.id, onEditProfile) }) {
                                    Text("Copy and edit")
                                }
                            } else {
                                Button(onClick = { onEditProfile(profile.id) }) { Text("Edit") }
                                TextButton(onClick = { vm.delete(profile.id) }) { Text("Delete") }
                            }
                        }
                    }
                }
            }

            item {
                Note(
                    "Built-in profiles cannot be deleted, but you can copy any of them and change " +
                        "everything about the copy.",
                    kind = NoteKind.INFO,
                )
            }
        }
    }
}
