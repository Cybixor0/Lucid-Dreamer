// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.lucidreamer.data.db.entity.DreamEntity
import com.lucidreamer.data.db.entity.DreamTagCrossRef
import com.lucidreamer.data.db.entity.Lucidity
import com.lucidreamer.data.db.entity.RealityCheckLogEntity
import com.lucidreamer.data.db.entity.ReminderProfileEntity
import com.lucidreamer.data.db.entity.TagEntity
import com.lucidreamer.data.db.entity.TagKind
import kotlinx.coroutines.flow.Flow

/** A dream together with its tags, which is how the UI always wants it. */
data class DreamWithTags(
    val dream: DreamEntity,
    val tags: List<TagEntity>,
)

/** Tag plus how often it has been used, for autocomplete and dream-sign ranking. */
data class TagFrequency(
    val id: Long,
    val name: String,
    val kind: TagKind,
    val useCount: Int,
)

@Dao
interface DreamDao {

    @Insert
    suspend fun insert(dream: DreamEntity): Long

    @Update
    suspend fun update(dream: DreamEntity)

    @Delete
    suspend fun delete(dream: DreamEntity)

    @Query("SELECT * FROM dreams WHERE id = :id")
    suspend fun byId(id: Long): DreamEntity?

    @Query("SELECT * FROM dreams WHERE id = :id")
    fun observeById(id: Long): Flow<DreamEntity?>

    @Query("SELECT * FROM dreams ORDER BY dreamAtMillis DESC")
    fun observeAll(): Flow<List<DreamEntity>>

    @Query("SELECT * FROM dreams WHERE favourite = 1 ORDER BY dreamAtMillis DESC")
    fun observeFavourites(): Flow<List<DreamEntity>>

    @Query("SELECT * FROM dreams WHERE lucidity != 'NONE' ORDER BY dreamAtMillis DESC")
    fun observeLucid(): Flow<List<DreamEntity>>

    /**
     * Full-text search across title and body.
     *
     * Joined through the FTS table rather than using LIKE, so it stays fast as
     * the journal grows and matches whole words the way a user expects.
     */
    @Query(
        """
        SELECT dreams.* FROM dreams
        JOIN dreams_fts ON dreams.rowid = dreams_fts.rowid
        WHERE dreams_fts MATCH :query
        ORDER BY dreams.dreamAtMillis DESC
        """,
    )
    fun search(query: String): Flow<List<DreamEntity>>

    @Query(
        """
        SELECT dreams.* FROM dreams
        JOIN dream_tags ON dreams.id = dream_tags.dreamId
        WHERE dream_tags.tagId = :tagId
        ORDER BY dreams.dreamAtMillis DESC
        """,
    )
    fun byTag(tagId: Long): Flow<List<DreamEntity>>

    @Transaction
    @Query("SELECT * FROM dreams WHERE id = :id")
    suspend fun withTags(id: Long): DreamEntity?

    @Query("SELECT COUNT(*) FROM dreams")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM dreams WHERE lucidity != 'NONE'")
    fun observeLucidCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM dreams WHERE lucidity != 'NONE' AND dreamAtMillis >= :sinceMillis")
    suspend fun lucidCountSince(sinceMillis: Long): Int

    @Query("SELECT COUNT(*) FROM dreams WHERE dreamAtMillis >= :sinceMillis")
    suspend fun dreamCountSince(sinceMillis: Long): Int

    @Query("SELECT * FROM dreams ORDER BY dreamAtMillis ASC")
    suspend fun allForExport(): List<DreamEntity>

    /**
     * Which cue sound preceded a lucid dream.
     *
     * The honest basis for "most successful cue type" in statistics: it is a
     * correlation over self-reported outcomes with a small sample, and the UI
     * says so rather than presenting it as a finding.
     */
    @Query(
        """
        SELECT ce.soundJson AS soundJson,
               COUNT(*) AS total,
               SUM(CASE WHEN d.lucidity != 'NONE' THEN 1 ELSE 0 END) AS lucidCount
        FROM cue_events ce
        LEFT JOIN dreams d ON d.precedingCueEventId = ce.id
        WHERE ce.outcome = 'PLAYED'
        GROUP BY ce.soundJson
        """,
    )
    suspend fun cueOutcomeStats(): List<CueSoundStat>

    @Query("DELETE FROM dreams")
    suspend fun deleteAll()
}

data class CueSoundStat(
    val soundJson: String,
    val total: Int,
    val lucidCount: Int,
)

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Query("SELECT * FROM tags WHERE name = :name AND kind = :kind LIMIT 1")
    suspend fun find(name: String, kind: TagKind): TagEntity?

    /** Insert-or-get, so the UI can just hand over a typed tag name. */
    @Transaction
    suspend fun resolve(name: String, kind: TagKind): TagEntity {
        val trimmed = name.trim()
        find(trimmed, kind)?.let { return it }
        val id = insert(TagEntity(name = trimmed, kind = kind))
        return find(trimmed, kind) ?: TagEntity(id = id, name = trimmed, kind = kind)
    }

    @Query("SELECT * FROM tags WHERE kind = :kind ORDER BY name ASC")
    fun observeByKind(kind: TagKind): Flow<List<TagEntity>>

    @Query(
        """
        SELECT t.id, t.name, t.kind, COUNT(dt.dreamId) AS useCount
        FROM tags t LEFT JOIN dream_tags dt ON t.id = dt.tagId
        WHERE t.kind = :kind
        GROUP BY t.id ORDER BY useCount DESC, t.name ASC
        """,
    )
    fun observeFrequencies(kind: TagKind): Flow<List<TagFrequency>>

    @Query(
        """
        SELECT t.id, t.name, t.kind, COUNT(dt.dreamId) AS useCount
        FROM tags t LEFT JOIN dream_tags dt ON t.id = dt.tagId
        WHERE t.kind = :kind
        GROUP BY t.id ORDER BY useCount DESC, t.name ASC LIMIT :limit
        """,
    )
    suspend fun topByKind(kind: TagKind, limit: Int): List<TagFrequency>

    @Query("SELECT t.* FROM tags t JOIN dream_tags dt ON t.id = dt.tagId WHERE dt.dreamId = :dreamId ORDER BY t.name")
    suspend fun tagsFor(dreamId: Long): List<TagEntity>

    @Query("SELECT t.* FROM tags t JOIN dream_tags dt ON t.id = dt.tagId WHERE dt.dreamId = :dreamId ORDER BY t.name")
    fun observeTagsFor(dreamId: Long): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(ref: DreamTagCrossRef)

    @Query("DELETE FROM dream_tags WHERE dreamId = :dreamId")
    suspend fun unlinkAll(dreamId: Long)

    @Transaction
    suspend fun setTags(dreamId: Long, names: List<String>, kind: TagKind) {
        // Replace wholesale: simpler than diffing, and tag lists are tiny.
        val existing = tagsFor(dreamId).filter { it.kind == kind }
        existing.forEach { unlinkOne(dreamId, it.id) }
        names.filter { it.isNotBlank() }.distinct().forEach {
            link(DreamTagCrossRef(dreamId, resolve(it, kind).id))
        }
    }

    @Query("DELETE FROM dream_tags WHERE dreamId = :dreamId AND tagId = :tagId")
    suspend fun unlinkOne(dreamId: Long, tagId: Long)

    /** Tags nothing references any more. Cheap housekeeping after a delete. */
    @Query("DELETE FROM tags WHERE id NOT IN (SELECT DISTINCT tagId FROM dream_tags)")
    suspend fun pruneOrphans()
}

@Dao
interface ReminderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ReminderProfileEntity): Long

    @Delete
    suspend fun delete(profile: ReminderProfileEntity)

    @Query("SELECT * FROM reminder_profiles ORDER BY name ASC")
    suspend fun all(): List<ReminderProfileEntity>

    @Query("SELECT * FROM reminder_profiles ORDER BY name ASC")
    fun observeAll(): Flow<List<ReminderProfileEntity>>

    @Query("SELECT * FROM reminder_profiles WHERE enabled = 1")
    suspend fun enabled(): List<ReminderProfileEntity>

    @Query("SELECT * FROM reminder_profiles WHERE id = :id")
    suspend fun byId(id: Long): ReminderProfileEntity?

    @Insert
    suspend fun logCheck(entry: RealityCheckLogEntity)

    @Query("SELECT COUNT(*) FROM reality_check_log WHERE atMillis >= :sinceMillis AND performed = 1")
    suspend fun performedSince(sinceMillis: Long): Int

    @Query("SELECT * FROM reality_check_log ORDER BY atMillis DESC LIMIT :limit")
    fun observeRecentChecks(limit: Int = 100): Flow<List<RealityCheckLogEntity>>

    @Query("DELETE FROM reality_check_log")
    suspend fun clearLog()
}
