// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucidreamer.AppContainer
import com.lucidreamer.data.repo.StatsRepository
import com.lucidreamer.ui.components.EmptyState
import com.lucidreamer.ui.components.Note
import com.lucidreamer.ui.components.NoteKind
import com.lucidreamer.ui.components.SectionCard
import com.lucidreamer.ui.components.StatValue
import com.lucidreamer.ui.components.display
import com.lucidreamer.ui.containerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StatsViewModel(private val container: AppContainer) : ViewModel() {
    private val _summary = MutableStateFlow<StatsRepository.Summary?>(null)
    val summary: StateFlow<StatsRepository.Summary?> = _summary.asStateFlow()

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        viewModelScope.launch {
            _enabled.value = container.statsRepository.enabled()
            _summary.value = container.statsRepository.summary()
        }
    }
}

@Composable
fun StatsScreen(modifier: Modifier = Modifier) {
    val vm = containerViewModel { StatsViewModel(it) }
    val summary by vm.summary.collectAsState()
    val enabled by vm.enabled.collectAsState()

    if (!enabled) {
        EmptyState(
            title = "Statistics are switched off",
            body = "Nothing is being counted. You can turn this back on in Settings, under " +
                "Privacy and data.",
            modifier = modifier,
        )
        return
    }

    val s = summary ?: return

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Note(
                "Everything here is a count of what you recorded, not a measurement of your " +
                    "sleep. The app has no way to observe whether you dreamed - only what you " +
                    "chose to write down.",
                kind = NoteKind.INFO,
            )
        }

        item {
            SectionCard(title = "Overall") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatValue("${s.nightsRecorded}", "nights")
                    StatValue("${s.cuesPlayed}", "cues played")
                    StatValue("${s.dreamsRecorded}", "dreams")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatValue("${s.lucidDreams}", "lucid dreams")
                    StatValue(
                        s.lucidRate?.let { "${(it * 100).toInt()}%" } ?: "-",
                        "of recorded dreams",
                    )
                    StatValue("${s.realityChecksLast30Days}", "checks, 30 days")
                }
            }
        }

        item {
            SectionCard(title = "Last 30 days") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatValue("${s.dreamsLast30Days}", "dreams recalled")
                    StatValue("${s.lucidLast30Days}", "lucid")
                }
                if (s.dreamsLast30Days == 0) {
                    Text(
                        "Recall is the foundation. If nothing else is working, a few weeks of " +
                            "writing down whatever you remember on waking is the thing that " +
                            "usually moves the needle.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard(title = "Your sleep pattern") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatValue(
                        s.averageBedTimeMinute?.let { formatMinute(it) } ?: "-",
                        "average into bed",
                        estimated = true,
                    )
                    StatValue(
                        s.averageWakeTimeMinute?.let { formatMinute(it) } ?: "-",
                        "average wake",
                        estimated = true,
                    )
                }
                StatValue(
                    s.averageSleepDuration?.display() ?: "-",
                    "average time asleep",
                    estimated = true,
                )
                Text(
                    "Based on your configured schedule and, where you reported it, when you " +
                        "actually went to sleep. The app does not measure sleep.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "Which cue sounds preceded a lucid dream") {
                if (s.cueEffectiveness.isEmpty()) {
                    Text(
                        "Nothing to compare yet. This fills in once cues have played and you have " +
                            "logged dreams alongside them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    s.cueEffectiveness.forEach { e ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(e.soundLabel, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (e.hasEnoughData) {
                                    "${e.lucidAfter}/${e.timesPlayed} (${(e.rate * 100).toInt()}%)"
                                } else {
                                    "${e.lucidAfter}/${e.timesPlayed} - too few to mean anything"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (e.hasEnoughData) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    Note(
                        "This is a correlation across a small number of self-reported nights, not " +
                            "evidence that one sound works better than another. Treat it as a " +
                            "hint about what to try next, nothing more.",
                        kind = NoteKind.EXPERIMENTAL,
                    )
                }
            }
        }
    }
}

private fun formatMinute(minute: Int): String =
    "%02d:%02d".format(minute / 60, minute % 60)
