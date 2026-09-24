// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucidreamer.AppContainer
import com.lucidreamer.data.db.entity.DreamEntity
import com.lucidreamer.data.db.entity.Lucidity
import com.lucidreamer.data.db.entity.TagKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DreamEditorState(
    val title: String = "",
    val body: String = "",
    val lucidity: Lucidity = Lucidity.NONE,
    val tagsText: String = "",
    val dreamSignsText: String = "",
    val mood: Int? = null,
    val rating: Int? = null,
    val favourite: Boolean = false,
    val dreamAtMillis: Long = System.currentTimeMillis(),
    val sessionId: Long? = null,
    val precedingCueEventId: Long? = null,
    val saved: Boolean = false,
    val speechUnavailable: Boolean = false,
)

class DreamEditorViewModel(
    private val container: AppContainer,
    private val dreamId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(DreamEditorState())
    val state: StateFlow<DreamEditorState> = _state.asStateFlow()

    init {
        if (dreamId != 0L) load() else prefillFromTonight()
    }

    private fun load() {
        viewModelScope.launch {
            val dream = container.journalRepository.byId(dreamId) ?: return@launch
            val tags = container.db.tagDao().tagsFor(dreamId)
            _state.value = DreamEditorState(
                title = dream.title,
                body = dream.body,
                lucidity = dream.lucidity,
                tagsText = tags.filter { it.kind == TagKind.TAG }.joinToString(", ") { it.name },
                dreamSignsText = tags.filter { it.kind == TagKind.DREAM_SIGN }.joinToString(", ") { it.name },
                mood = dream.mood,
                rating = dream.rating,
                favourite = dream.favourite,
                dreamAtMillis = dream.dreamAtMillis,
                sessionId = dream.sessionId,
                precedingCueEventId = dream.precedingCueEventId,
            )
        }
    }

    /**
     * Links a new dream to the session and the cue that preceded it.
     *
     * This is what later makes "which cue sound actually works for me"
     * answerable at all - without the link, cue effectiveness would be pure
     * guesswork. Only applied to new entries written during or just after a
     * session, so it never rewrites something the user recorded themselves.
     */
    private fun prefillFromTonight() {
        viewModelScope.launch {
            val session = container.sessionRepository.activeSession() ?: return@launch
            val lastCue = container.db.cueEventDao().lastPlayed(session.id)
            _state.value = _state.value.copy(
                sessionId = session.id,
                precedingCueEventId = lastCue?.id,
            )
        }
    }

    fun setTitle(v: String) { _state.value = _state.value.copy(title = v) }
    fun setBody(v: String) { _state.value = _state.value.copy(body = v) }
    fun setLucidity(v: Lucidity) { _state.value = _state.value.copy(lucidity = v) }
    fun setTagsText(v: String) { _state.value = _state.value.copy(tagsText = v) }
    fun setDreamSignsText(v: String) { _state.value = _state.value.copy(dreamSignsText = v) }
    fun setMood(v: Int?) { _state.value = _state.value.copy(mood = v) }
    fun setRating(v: Int?) { _state.value = _state.value.copy(rating = v) }
    fun toggleFavourite() { _state.value = _state.value.copy(favourite = !_state.value.favourite) }
    fun reportNoSpeechRecogniser() { _state.value = _state.value.copy(speechUnavailable = true) }

    /** Appends dictated text rather than replacing, so several passes accumulate. */
    fun appendDictation(text: String) {
        val current = _state.value.body
        _state.value = _state.value.copy(
            body = if (current.isBlank()) text else "$current $text",
        )
    }

    fun save() {
        viewModelScope.launch {
            val s = _state.value
            container.journalRepository.save(
                dream = DreamEntity(
                    id = dreamId,
                    title = s.title.trim(),
                    body = s.body.trim(),
                    dreamAtMillis = s.dreamAtMillis,
                    createdAtMillis = s.dreamAtMillis,
                    updatedAtMillis = System.currentTimeMillis(),
                    lucidity = s.lucidity,
                    rating = s.rating,
                    mood = s.mood,
                    favourite = s.favourite,
                    sessionId = s.sessionId,
                    precedingCueEventId = s.precedingCueEventId,
                ),
                tagNames = splitList(s.tagsText),
                dreamSigns = splitList(s.dreamSignsText),
            )
            _state.value = _state.value.copy(saved = true)
        }
    }

    fun delete() {
        viewModelScope.launch {
            container.journalRepository.byId(dreamId)?.let { container.journalRepository.delete(it) }
            _state.value = _state.value.copy(saved = true)
        }
    }

    private fun splitList(text: String): List<String> =
        text.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct()
}
