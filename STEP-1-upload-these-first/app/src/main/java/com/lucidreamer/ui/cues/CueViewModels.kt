// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.cues

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucidreamer.AppContainer
import com.lucidreamer.data.db.ColumnJson
import com.lucidreamer.data.db.entity.CueProfileEntity
import com.lucidreamer.domain.cue.CueConstraints
import com.lucidreamer.domain.cue.CueProfile
import com.lucidreamer.domain.cue.CueRule
import com.lucidreamer.domain.cue.CueSound
import com.lucidreamer.domain.cue.CuePlanner
import com.lucidreamer.domain.cue.CuePlayback
import com.lucidreamer.domain.cue.NightPlan
import com.lucidreamer.service.CuePlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

data class CueProfileSummary(
    val id: Long,
    val name: String,
    val description: String,
    val ruleCount: Int,
    val isPreset: Boolean,
    val isActive: Boolean,
)

class CueProfileListViewModel(private val container: AppContainer) : ViewModel() {

    val profiles: StateFlow<List<CueProfileSummary>> =
        container.db.cueProfileDao().observeAll()
            .map { entities ->
                val activeId = container.settings.activeCueProfileId.first()
                entities.map { e ->
                    val domain = runCatching { e.toDomain() }.getOrNull()
                    CueProfileSummary(
                        id = e.id,
                        name = e.name,
                        description = e.description,
                        ruleCount = domain?.rules?.count { it.enabled } ?: 0,
                        isPreset = e.isPreset,
                        isActive = e.id == activeId,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setActive(id: Long) {
        viewModelScope.launch { container.settings.setActiveCueProfileId(id) }
    }

    /** Presets are protected from deletion, so editing one starts with a copy. */
    fun duplicate(id: Long, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val original = container.db.cueProfileDao().byId(id) ?: return@launch
            val copy = original.copy(
                id = 0,
                name = "${original.name} (copy)",
                isPreset = false,
            )
            onCreated(container.db.cueProfileDao().upsert(copy))
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { container.db.cueProfileDao().deleteUserProfile(id) }
    }

    fun createBlank(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = container.db.cueProfileDao().upsert(
                CueProfileEntity.from(
                    CueProfile(name = "New profile", rules = emptyList(), constraints = CueConstraints()),
                ),
            )
            onCreated(id)
        }
    }
}

data class CueEditorState(
    val loading: Boolean = true,
    val profile: CueProfile? = null,
    val isPreset: Boolean = false,
    /** A dry-run of the profile against tonight's schedule. */
    val preview: NightPlan? = null,
    val saved: Boolean = false,
)

class CueEditorViewModel(
    private val container: AppContainer,
    private val profileId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(CueEditorState())
    val state: StateFlow<CueEditorState> = _state.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val entity = container.db.cueProfileDao().byId(profileId)
            val profile = entity?.toDomain()
            _state.value = CueEditorState(
                loading = false,
                profile = profile,
                isPreset = entity?.isPreset ?: false,
            )
            refreshPreview()
        }
    }

    /**
     * Shows what this profile would actually do tonight.
     *
     * Rules compose in ways that are hard to predict - three reasonable-looking
     * repeating rules can easily produce forty cues, most of which the
     * constraints then drop. Showing the resolved times, and the reason each
     * dropped cue was dropped, turns the editor from guesswork into something
     * you can reason about.
     */
    fun refreshPreview() {
        viewModelScope.launch {
            val profile = _state.value.profile ?: return@launch
            val night = container.sessionRepository.previewTonight()
            _state.value = _state.value.copy(
                preview = CuePlanner.plan(night, profile, seed = PREVIEW_SEED),
            )
        }
    }

    fun update(transform: (CueProfile) -> CueProfile) {
        val current = _state.value.profile ?: return
        _state.value = _state.value.copy(profile = transform(current))
        refreshPreview()
    }

    fun updateRule(index: Int, transform: (CueRule) -> CueRule) {
        update { profile ->
            profile.copy(
                rules = profile.rules.mapIndexed { i, rule -> if (i == index) transform(rule) else rule },
            )
        }
    }

    fun addRule(rule: CueRule) {
        update { profile ->
            val nextId = (profile.rules.maxOfOrNull { it.id } ?: 0L) + 1
            profile.copy(rules = profile.rules + rule.copy(id = nextId))
        }
    }

    fun removeRule(index: Int) {
        update { profile -> profile.copy(rules = profile.rules.filterIndexed { i, _ -> i != index }) }
    }

    fun save() {
        viewModelScope.launch {
            val profile = _state.value.profile ?: return@launch
            container.db.cueProfileDao().upsert(
                CueProfileEntity.from(profile.copy(id = profileId), isPreset = _state.value.isPreset),
            )
            _state.value = _state.value.copy(saved = true)
        }
    }

    /** Plays a rule's sound at its configured volume, so it can be judged in bed. */
    fun testRule(rule: CueRule) {
        runCatching {
            ContextCompat.startForegroundService(
                container.appContext,
                Intent(container.appContext, CuePlaybackService::class.java).apply {
                    action = CuePlaybackService.ACTION_TEST_CUE
                    putExtra(CuePlaybackService.EXTRA_SOUND_JSON, ColumnJson.encodeToString<CueSound>(rule.sound))
                    putExtra(CuePlaybackService.EXTRA_PLAYBACK_JSON, ColumnJson.encodeToString<CuePlayback>(rule.playback))
                },
            )
        }
    }

    private companion object {
        /** Fixed so the preview does not jitter every time a field is edited. */
        const val PREVIEW_SEED = 20260924L
    }
}
