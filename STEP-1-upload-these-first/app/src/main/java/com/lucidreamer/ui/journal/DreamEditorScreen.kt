// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.journal

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lucidreamer.data.db.entity.Lucidity
import com.lucidreamer.ui.components.Note
import com.lucidreamer.ui.components.NoteKind
import com.lucidreamer.ui.components.SectionCard
import com.lucidreamer.ui.containerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DreamEditorScreen(
    dreamId: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = containerViewModel(key = "dream-$dreamId") { DreamEditorViewModel(it, dreamId) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    // System speech recognition. It is a platform service, not part of this
    // app, and on many devices it is not on-device - which is disclosed below
    // rather than glossed over, since the whole app is otherwise offline.
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.let(vm::appendDictation)
        }
    }

    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (dreamId == 0L) "New dream" else "Edit dream") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = vm::toggleFavourite) {
                        Icon(
                            if (state.favourite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Save",
                        )
                    }
                    if (dreamId != 0L) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, "Delete")
                        }
                    }
                },
            )
        },
    ) { insets ->
        Column(
            Modifier
                .padding(insets)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = vm::setTitle,
                label = { Text("Title") },
                placeholder = { Text("A few words to find it again") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.body,
                onValueChange = vm::setBody,
                label = { Text("What happened") },
                placeholder = { Text("Whatever you remember, even fragments.") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe the dream")
                        // Ask for on-device recognition where the platform
                        // supports it. It is a request, not a guarantee.
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                    runCatching { speechLauncher.launch(intent) }
                        .onFailure { vm.reportNoSpeechRecogniser() }
                }) {
                    Icon(Icons.Default.Mic, null)
                    Text("  Dictate")
                }
            }

            if (state.speechUnavailable) {
                Note(
                    "No speech recogniser is available on this device. You can still type, and " +
                        "nothing is lost - dictation is only a convenience.",
                    kind = NoteKind.WARNING,
                )
            }

            Note(
                "Dictation uses Android's own speech recognition, which is not part of this app " +
                    "and may send audio to the system's speech service. Everything you type stays " +
                    "on the device.",
                kind = NoteKind.INFO,
            )

            SectionCard(title = "Was it lucid?") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Lucidity.entries.forEach { level ->
                        FilterChip(
                            selected = state.lucidity == level,
                            onClick = { vm.setLucidity(level) },
                            label = {
                                Text(
                                    when (level) {
                                        Lucidity.NONE -> "No"
                                        Lucidity.PARTIAL -> "Partly"
                                        Lucidity.FULL -> "Yes"
                                    },
                                )
                            },
                        )
                    }
                }
            }

            SectionCard(title = "Tags") {
                OutlinedTextField(
                    value = state.tagsText,
                    onValueChange = vm::setTagsText,
                    label = { Text("Tags, separated by commas") },
                    placeholder = { Text("flying, childhood home, chased") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionCard(title = "Dream signs") {
                Text(
                    "Things that recur in your dreams. The app counts them, so you can turn your " +
                        "most frequent ones into reality-check prompts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.dreamSignsText,
                    onValueChange = vm::setDreamSignsText,
                    label = { Text("Dream signs, separated by commas") },
                    placeholder = { Text("my old school, broken phone, teeth") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionCard(title = "Mood and rating") {
                Text("How did it feel? (optional)", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (-2..2).forEach { m ->
                        FilterChip(
                            selected = state.mood == m,
                            onClick = { vm.setMood(if (state.mood == m) null else m) },
                            label = {
                                Text(
                                    when (m) {
                                        -2 -> "Awful"
                                        -1 -> "Bad"
                                        0 -> "Neutral"
                                        1 -> "Good"
                                        else -> "Great"
                                    },
                                )
                            },
                        )
                    }
                }

                Text("Worth remembering? (optional)", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { r ->
                        FilterChip(
                            selected = state.rating == r,
                            onClick = { vm.setRating(if (state.rating == r) null else r) },
                            label = { Text("$r") },
                        )
                    }
                }
            }

            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this dream?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}
