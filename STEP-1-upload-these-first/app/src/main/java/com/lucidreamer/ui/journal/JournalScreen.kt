// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.journal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lucidreamer.data.db.entity.DreamEntity
import com.lucidreamer.data.db.entity.Lucidity
import com.lucidreamer.ui.components.EmptyState
import com.lucidreamer.ui.containerViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

@Composable
fun JournalScreen(
    onOpenDream: (Long) -> Unit,
    onNewDream: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = containerViewModel { JournalViewModel(it) }
    val state by vm.state.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(onClick = onNewDream) {
                Icon(Icons.Default.Add, contentDescription = "New dream")
            }
        },
    ) { insets ->
        Column(Modifier.padding(insets).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                placeholder = { Text("Search your dreams") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.filter == JournalTab.ALL,
                    onClick = { vm.setTab(JournalTab.ALL) },
                    label = { Text("All") },
                )
                FilterChip(
                    selected = state.filter == JournalTab.LUCID,
                    onClick = { vm.setTab(JournalTab.LUCID) },
                    label = { Text("Lucid") },
                )
                FilterChip(
                    selected = state.filter == JournalTab.FAVOURITES,
                    onClick = { vm.setTab(JournalTab.FAVOURITES) },
                    label = { Text("Saved") },
                )
            }

            if (state.dreams.isEmpty()) {
                EmptyState(
                    title = if (state.query.isNotBlank()) "Nothing matched" else "No dreams yet",
                    body = if (state.query.isNotBlank()) {
                        "Try a different word."
                    } else {
                        "Write down whatever you remember, even a fragment. Recall improves " +
                            "quickly once you start recording it, and it is what every induction " +
                            "technique depends on."
                    },
                )
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.dreams, key = { it.id }) { dream ->
                        DreamRow(dream, onClick = { onOpenDream(dream.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DreamRow(dream: DreamEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    dream.title.ifBlank { "Untitled dream" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (dream.favourite) {
                    Icon(Icons.Default.Star, contentDescription = "Saved", Modifier.padding(start = 8.dp))
                }
            }

            Text(
                dateFormat.format(Instant.ofEpochMilli(dream.dreamAtMillis).atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (dream.body.isNotBlank()) {
                Text(
                    dream.body.take(160).let { if (dream.body.length > 160) "$it..." else it },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (dream.lucidity != Lucidity.NONE) {
                Box(Modifier.padding(top = 8.dp)) {
                    Text(
                        when (dream.lucidity) {
                            Lucidity.FULL -> "Lucid"
                            Lucidity.PARTIAL -> "Partly lucid"
                            Lucidity.NONE -> ""
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
