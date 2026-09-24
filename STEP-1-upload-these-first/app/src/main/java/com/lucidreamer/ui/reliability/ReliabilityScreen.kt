// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.reliability

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lucidreamer.ui.components.Note
import com.lucidreamer.ui.components.NoteKind
import com.lucidreamer.ui.components.SectionCard
import com.lucidreamer.ui.containerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReliabilityScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val vm = containerViewModel { ReliabilityViewModel(it) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Will this work overnight?") },
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
                    "No Android app can guarantee it will run all night - manufacturers can stop " +
                        "background work in ways no API prevents. What this app can do is check " +
                        "everything that is checkable, tell you plainly what is wrong, and report " +
                        "in the morning if a night did not go to plan.",
                )
            }

            items(state.checks) { check ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(
                            if (check.passing) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (check.passing) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(22.dp),
                        )
                        Text(check.title, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(check.detail, style = MaterialTheme.typography.bodyMedium)

                    if (!check.passing && check.intent != null) {
                        Button(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        check.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        ) { Text(check.actionLabel ?: "Open settings") }
                    }
                }
            }

            if (state.isSamsung) {
                item {
                    SectionCard(title = "Samsung Galaxy") {
                        Text(
                            "One UI puts apps it thinks are unused to sleep after about three " +
                                "days, and into \"deep sleep\" after about sixteen. Deep sleep " +
                                "stops the app completely until you open it again, and it is the " +
                                "most common reason cues do not play on a Galaxy.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Settings > Battery > Background usage limits > Never sleeping apps - " +
                                "add Lucid Dreamer.\n\n" +
                                "Also turn off \"Put unused apps to sleep\" on the same screen, and " +
                                "set Settings > Apps > Lucid Dreamer > Battery to Unrestricted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.samsungIntent?.let { intent ->
                            Button(
                                onClick = {
                                    runCatching {
                                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Open \"Never sleeping apps\"") }
                        } ?: Note(
                            "This phone does not expose a direct link to that screen, so you will " +
                                "have to navigate to it manually using the path above.",
                            kind = NoteKind.INFO,
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Test it") {
                    Text(
                        "The only test that really counts is a real night. These two get you most " +
                            "of the way there in a few minutes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = vm::scheduleTestCue, modifier = Modifier.fillMaxWidth()) {
                        Text("Play a cue in 2 minutes")
                    }
                    Text(
                        "Lock the phone and put it down. If you hear it, the alarm path works on " +
                            "this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedButton(onClick = vm::runDryRun, modifier = Modifier.fillMaxWidth()) {
                        Text("Dry run: hear tonight in 3 minutes")
                    }
                    Text(
                        "Plays tonight's cues in order, compressed, so you can judge the volumes " +
                            "and fades before trusting them with a real night.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }

            state.lastReport?.let { report ->
                item {
                    SectionCard(title = "Last night") {
                        Text(report.headline, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            report.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (report.freezes.isNotEmpty()) {
                            Text(
                                "The app produced no sign of life for a total of " +
                                    com.lucidreamer.domain.diagnostics.ReliabilityReport
                                        .humanDuration(report.totalFrozen) +
                                    " across ${report.freezes.size} period(s).",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        report.medianLatency?.let {
                            Text(
                                "Cues fired a median of ${it.toMillis()}ms after their target time.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
