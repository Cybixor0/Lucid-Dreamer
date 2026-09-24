// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lucidreamer.ui.components.Note
import com.lucidreamer.ui.components.NoteKind
import com.lucidreamer.ui.components.SectionCard
import com.lucidreamer.ui.containerViewModel

/**
 * Every permission the app declares, what it is for, and what breaks without it.
 *
 * Mirrored in PERMISSIONS.md so the same information is available before
 * installing, not only after.
 */
private data class PermissionInfo(
    val name: String,
    val what: String,
    val why: String,
    val ifDenied: String,
)

private val permissions = listOf(
    PermissionInfo(
        name = "Notifications",
        what = "Shows the ongoing session, its controls, and reality-check reminders.",
        why = "Required for the session to run in the foreground. It is also part of what stops " +
            "Android killing the app overnight.",
        ifDenied = "The session still runs, but you lose the controls and cues are more likely to " +
            "be stopped by the system.",
    ),
    PermissionInfo(
        name = "Alarms and reminders",
        what = "Schedules each cue at an exact time.",
        why = "Without exact alarms Android defers the app to its own maintenance windows, which " +
            "can be an hour late - useless for targeting a sleep stage.",
        ifDenied = "Cues are scheduled inexactly and may drift substantially.",
    ),
    PermissionInfo(
        name = "Run in the background / ignore battery optimisation",
        what = "Lets the app keep its session running while the screen is off.",
        why = "Android and phone manufacturers suspend background apps aggressively. This is the " +
            "single biggest factor in whether cues actually play.",
        ifDenied = "Cues will often not play. The app tells you in the morning when this happens.",
    ),
    PermissionInfo(
        name = "Microphone",
        what = "Only used if you record your own voice cue, or dictate a dream.",
        why = "Recording a personal cue in your own voice, and voice entry for the journal.",
        ifDenied = "You can still use every built-in sound, text-to-speech and typing. Nothing else " +
            "is affected.",
    ),
    PermissionInfo(
        name = "Vibration",
        what = "Optional vibration alongside or instead of a cue sound.",
        why = "A silent alternative if you share a bed.",
        ifDenied = "Cues play as audio only.",
    ),
    PermissionInfo(
        name = "Start at boot",
        what = "Re-arms tonight's cues after the phone restarts.",
        why = "Android cancels all pending alarms when a device shuts down.",
        ifDenied = "A restart mid-night would lose the rest of the session.",
    ),
    PermissionInfo(
        name = "Do Not Disturb access (optional)",
        what = "Reads your Do Not Disturb setting.",
        why = "So the app can warn you at bedtime that DND will silence your cues, rather than " +
            "you finding out in the morning.",
        ifDenied = "Everything works; you simply do not get that warning.",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val vm = containerViewModel { PrivacyViewModel(it) }
    var confirmErase by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Privacy") },
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
                SectionCard(title = "This app has no internet permission") {
                    Text(
                        "Lucid Dreamer does not declare the INTERNET permission at all. It is not " +
                            "that the app chooses not to send your data anywhere - it is " +
                            "structurally incapable of doing so, and you can verify that yourself " +
                            "by reading the manifest in the source code.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "No accounts. No analytics. No crash reporting. No advertising. No cloud " +
                            "backup. Your dream journal exists on this phone and nowhere else.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item {
                Note(
                    "One exception worth being clear about: if you use voice dictation for a dream, " +
                        "that uses Android's own speech recognition, which is a system service and " +
                        "may not run on the device. Typing never leaves the phone.",
                    kind = NoteKind.WARNING,
                )
            }

            item { Text("Permissions", style = MaterialTheme.typography.titleLarge) }

            items(permissions) { p ->
                SectionCard(title = p.name) {
                    Text(p.what, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Why: ${p.why}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "If you refuse: ${p.ifDenied}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard(title = "Backup and export") {
                    Text(
                        "Automatic cloud backup is switched off, so your journal is never swept " +
                            "into a Google backup without you choosing it. Export writes a plain " +
                            "JSON file wherever you choose.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { vm.export { exportResult = it } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Export everything to a file") }
                    exportResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                SectionCard(title = "Erase") {
                    Text(
                        "Removes every dream, session, statistic and setting from this device. " +
                            "There is no copy anywhere else, so this cannot be undone.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = { confirmErase = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Erase everything") }
                }
            }
        }
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("Erase everything?") },
            text = {
                Text(
                    "Every dream, session and setting will be deleted from this phone. Because " +
                        "nothing is stored anywhere else, this cannot be undone. Consider " +
                        "exporting first.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmErase = false
                    vm.eraseEverything()
                }) { Text("Erase") }
            },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Cancel") } },
        )
    }
}
