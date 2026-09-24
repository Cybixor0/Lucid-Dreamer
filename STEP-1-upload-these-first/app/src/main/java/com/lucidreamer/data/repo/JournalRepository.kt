// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.data.repo

import com.lucidreamer.data.db.LucidDatabase
import com.lucidreamer.data.db.dao.TagFrequency
import com.lucidreamer.data.db.entity.DreamEntity
import com.lucidreamer.data.db.entity.Lucidity
import com.lucidreamer.data.db.entity.TagEntity
import com.lucidreamer.data.db.entity.TagKind
import kotlinx.coroutines.flow.Flow

/** Which subset of the journal a list is showing. */
sealed interface JournalFilter {
    data object All : JournalFilter
    data object Favourites : JournalFilter
    data object LucidOnly : JournalFilter
    data class Search(val query: String) : JournalFilter
    data class ByTag(val tagId: Long, val name: String) : JournalFilter
}

class JournalRepository(private val db: LucidDatabase) {

    private val dreams get() = db.dreamDao()
    private val tags get() = db.tagDao()

    fun observe(filter: JournalFilter): Flow<List<DreamEntity>> = when (filter) {
        JournalFilter.All -> dreams.observeAll()
        JournalFilter.Favourites -> dreams.observeFavourites()
        JournalFilter.LucidOnly -> dreams.observeLucid()
        is JournalFilter.ByTag -> dreams.byTag(filter.tagId)
        is JournalFilter.Search -> dreams.search(toFtsQuery(filter.query))
    }

    /**
     * Turns typed text into an FTS MATCH expression.
     *
     * Users type words, not query syntax, and a stray quote or hyphen would
     * otherwise throw a SQLite syntax error straight into the search box. Each
     * token is quoted and given a prefix wildcard so results appear while
     * typing.
     */
    private fun toFtsQuery(raw: String): String {
        val tokens = raw.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { it.replace("\"", "") }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return "\"\""
        return tokens.joinToString(" ") { "\"$it\"*" }
    }

    fun observeById(id: Long): Flow<DreamEntity?> = dreams.observeById(id)
    fun observeTagsFor(dreamId: Long): Flow<List<TagEntity>> = tags.observeTagsFor(dreamId)
    fun observeTags(kind: TagKind): Flow<List<TagEntity>> = tags.observeByKind(kind)
    fun observeTagFrequencies(kind: TagKind): Flow<List<TagFrequency>> = tags.observeFrequencies(kind)

    fun observeCount(): Flow<Int> = dreams.observeCount()
    fun observeLucidCount(): Flow<Int> = dreams.observeLucidCount()

    suspend fun byId(id: Long): DreamEntity? = dreams.byId(id)

    /**
     * Creates or updates a dream and replaces its tags.
     *
     * @return the id, so a newly-created entry can be navigated to.
     */
    suspend fun save(
        dream: DreamEntity,
        tagNames: List<String>,
        dreamSigns: List<String>,
    ): Long {
        val now = System.currentTimeMillis()
        val id = if (dream.id == 0L) {
            dreams.insert(dream.copy(createdAtMillis = now, updatedAtMillis = now))
        } else {
            dreams.update(dream.copy(updatedAtMillis = now))
            dream.id
        }
        tags.setTags(id, tagNames, TagKind.TAG)
        tags.setTags(id, dreamSigns, TagKind.DREAM_SIGN)
        return id
    }

    suspend fun delete(dream: DreamEntity) {
        dreams.delete(dream)
        tags.pruneOrphans()
    }

    suspend fun toggleFavourite(dream: DreamEntity) {
        dreams.update(dream.copy(favourite = !dream.favourite, updatedAtMillis = System.currentTimeMillis()))
    }

    suspend fun setLucidity(dream: DreamEntity, lucidity: Lucidity) {
        dreams.update(dream.copy(lucidity = lucidity, updatedAtMillis = System.currentTimeMillis()))
    }

    /**
     * The user's most frequent dream signs.
     *
     * Offered as reality-check reminder text, which is the one place the
     * journal and daytime practice actually connect: a reality check prompted
     * by something that genuinely recurs in your dreams is worth more than a
     * generic one.
     */
    suspend fun topDreamSigns(limit: Int = 8): List<TagFrequency> =
        tags.topByKind(TagKind.DREAM_SIGN, limit)

    suspend fun allForExport(): List<DreamEntity> = dreams.allForExport()

    suspend fun deleteAll() {
        dreams.deleteAll()
        tags.pruneOrphans()
    }
}
