// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucidreamer.AppContainer
import com.lucidreamer.data.db.entity.DreamEntity
import com.lucidreamer.data.db.entity.Lucidity
import com.lucidreamer.data.db.entity.TagKind
import com.lucidreamer.data.repo.JournalFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class JournalTab { ALL, LUCID, FAVOURITES }

data class JournalState(
    val dreams: List<DreamEntity> = emptyList(),
    val query: String = "",
    val filter: JournalTab = JournalTab.ALL,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class JournalViewModel(private val container: AppContainer) : ViewModel() {

    private val query = MutableStateFlow("")
    private val tab = MutableStateFlow(JournalTab.ALL)

    val state: StateFlow<JournalState> =
        combine(
            // Debounced so every keystroke does not run a new FTS query, but
            // short enough that search still feels immediate.
            query.debounce(200),
            tab,
        ) { q, t -> q to t }
            .flatMapLatest { (q, t) ->
                val filter = when {
                    q.isNotBlank() -> JournalFilter.Search(q)
                    t == JournalTab.LUCID -> JournalFilter.LucidOnly
                    t == JournalTab.FAVOURITES -> JournalFilter.Favourites
                    else -> JournalFilter.All
                }
                container.journalRepository.observe(filter).map { dreams ->
                    JournalState(dreams = dreams, query = q, filter = t)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JournalState())

    fun setQuery(value: String) { query.value = value }
    fun setTab(value: JournalTab) { tab.value = value }

    fun toggleFavourite(dream: DreamEntity) {
        viewModelScope.launch { container.journalRepository.toggleFavourite(dream) }
    }

    fun setLucidity(dream: DreamEntity, lucidity: Lucidity) {
        viewModelScope.launch { container.journalRepository.setLucidity(dream, lucidity) }
    }

    fun observeDreamSigns() = container.journalRepository.observeTagFrequencies(TagKind.DREAM_SIGN)
}
