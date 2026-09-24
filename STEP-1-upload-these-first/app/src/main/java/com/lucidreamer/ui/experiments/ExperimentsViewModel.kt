// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.experiments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucidreamer.AppContainer
import com.lucidreamer.data.db.entity.ExperimentEntity
import com.lucidreamer.data.db.entity.ExperimentRunEntity
import com.lucidreamer.data.db.entity.NightOutcome
import com.lucidreamer.domain.experiment.ExperimentSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

data class ProfileOption(val id: Long, val name: String)

data class PendingOutcome(
    val run: ExperimentRunEntity,
    val armLabel: String,
    val profileName: String,
)

data class ExperimentsUiState(
    val experiments: List<ExperimentSummary> = emptyList(),
    val awaitingOutcome: List<PendingOutcome> = emptyList(),
    val availableProfiles: List<ProfileOption> = emptyList(),
    val draftName: String = "",
    val draftArmA: ProfileOption = ProfileOption(0, ""),
    val draftArmB: ProfileOption = ProfileOption(0, ""),
)

class ExperimentsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ExperimentsUiState())
    val state: StateFlow<ExperimentsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val profiles = container.db.cueProfileDao().all().map { ProfileOption(it.id, it.name) }

            // Read once rather than observed: the summary arithmetic walks
            // every run, and there is no reason to redo it on unrelated writes.
            val experiments = container.db.experimentDao().all()
                .mapNotNull { container.experimentManager.summarise(it.id) }

            val pending = container.experimentManager.runsAwaitingOutcome().mapNotNull { run ->
                val experiment = container.db.experimentDao().byId(run.experimentId) ?: return@mapNotNull null
                PendingOutcome(
                    run = run,
                    armLabel = if (run.armA) experiment.armALabel else experiment.armBLabel,
                    profileName = profiles.firstOrNull { it.id == run.cueProfileId }?.name ?: "Unknown profile",
                )
            }

            _state.value = _state.value.copy(
                experiments = experiments,
                awaitingOutcome = pending,
                availableProfiles = profiles,
                draftArmA = _state.value.draftArmA.takeIf { it.id != 0L }
                    ?: profiles.firstOrNull() ?: ProfileOption(0, ""),
                draftArmB = _state.value.draftArmB.takeIf { it.id != 0L }
                    ?: profiles.getOrNull(1) ?: ProfileOption(0, ""),
            )
        }
    }

    fun setDraftName(v: String) { _state.value = _state.value.copy(draftName = v) }
    fun setDraftArmA(v: ProfileOption) { _state.value = _state.value.copy(draftArmA = v) }
    fun setDraftArmB(v: ProfileOption) { _state.value = _state.value.copy(draftArmB = v) }

    fun createExperiment() {
        val s = _state.value
        if (s.draftArmA.id == s.draftArmB.id || s.draftName.isBlank()) return

        viewModelScope.launch {
            // Any previously running experiment is stopped first: two
            // experiments competing for the same night would make both
            // meaningless.
            container.db.experimentDao().activeExperiment()?.let {
                container.experimentManager.setActive(it.id, false)
            }

            val id = container.db.experimentDao().upsert(
                ExperimentEntity(
                    name = s.draftName.trim(),
                    createdAtMillis = System.currentTimeMillis(),
                    active = true,
                    armACueProfileId = s.draftArmA.id,
                    armALabel = s.draftArmA.name,
                    armBCueProfileId = s.draftArmB.id,
                    armBLabel = s.draftArmB.name,
                    alternateRandomly = true,
                    // Fixed once so assignment is reproducible after a restore.
                    assignmentSeed = Random.nextLong(),
                ),
            )
            container.settings.setActiveExperimentId(id)
            _state.value = _state.value.copy(draftName = "")
            refresh()
        }
    }

    fun recordOutcome(runId: Long, outcome: NightOutcome) {
        viewModelScope.launch {
            container.experimentManager.recordOutcome(runId, outcome)
            refresh()
        }
    }

    fun setActive(experimentId: Long, active: Boolean) {
        viewModelScope.launch {
            container.experimentManager.setActive(experimentId, active)
            refresh()
        }
    }

    fun delete(experimentId: Long) {
        viewModelScope.launch {
            container.db.experimentDao().byId(experimentId)?.let {
                container.db.experimentDao().deleteRuns(experimentId)
                container.db.experimentDao().delete(it)
            }
            refresh()
        }
    }
}
