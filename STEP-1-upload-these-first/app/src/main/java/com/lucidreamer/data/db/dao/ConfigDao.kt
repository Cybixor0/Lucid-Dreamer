// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lucidreamer.data.db.entity.CueProfileEntity
import com.lucidreamer.data.db.entity.EventLogEntity
import com.lucidreamer.data.db.entity.LogLevel
import com.lucidreamer.data.db.entity.NightOverrideEntity
import com.lucidreamer.data.db.entity.ScheduleProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ScheduleProfileEntity): Long

    @Delete
    suspend fun delete(profile: ScheduleProfileEntity)

    @Query("SELECT * FROM schedule_profiles ORDER BY priority DESC, name ASC")
    suspend fun all(): List<ScheduleProfileEntity>

    @Query("SELECT * FROM schedule_profiles ORDER BY priority DESC, name ASC")
    fun observeAll(): Flow<List<ScheduleProfileEntity>>

    @Query("SELECT * FROM schedule_profiles WHERE id = :id")
    suspend fun byId(id: Long): ScheduleProfileEntity?

    @Query("SELECT COUNT(*) FROM schedule_profiles")
    suspend fun count(): Int
}

@Dao
interface NightOverrideDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: NightOverrideEntity)

    @Query("SELECT * FROM night_overrides WHERE nightEpochDay = :epochDay")
    suspend fun forNight(epochDay: Long): NightOverrideEntity?

    @Query("SELECT * FROM night_overrides WHERE nightEpochDay >= :fromEpochDay ORDER BY nightEpochDay ASC")
    suspend fun from(fromEpochDay: Long): List<NightOverrideEntity>

    @Query("SELECT * FROM night_overrides WHERE nightEpochDay >= :fromEpochDay ORDER BY nightEpochDay ASC")
    fun observeFrom(fromEpochDay: Long): Flow<List<NightOverrideEntity>>

    @Query("DELETE FROM night_overrides WHERE nightEpochDay = :epochDay")
    suspend fun deleteForNight(epochDay: Long)

    /** Old one-off overrides are noise; drop them once they are well past. */
    @Query("DELETE FROM night_overrides WHERE nightEpochDay < :beforeEpochDay")
    suspend fun pruneBefore(beforeEpochDay: Long)
}

@Dao
interface CueProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: CueProfileEntity): Long

    @Update
    suspend fun update(profile: CueProfileEntity)

    @Query("DELETE FROM cue_profiles WHERE id = :id AND isPreset = 0")
    suspend fun deleteUserProfile(id: Long): Int

    @Query("SELECT * FROM cue_profiles ORDER BY isPreset DESC, name ASC")
    suspend fun all(): List<CueProfileEntity>

    @Query("SELECT * FROM cue_profiles ORDER BY isPreset DESC, name ASC")
    fun observeAll(): Flow<List<CueProfileEntity>>

    @Query("SELECT * FROM cue_profiles WHERE id = :id")
    suspend fun byId(id: Long): CueProfileEntity?

    @Query("SELECT * FROM cue_profiles WHERE id = :id")
    fun observeById(id: Long): Flow<CueProfileEntity?>

    @Query("SELECT COUNT(*) FROM cue_profiles")
    suspend fun count(): Int
}

@Dao
interface EventLogDao {

    @Insert
    suspend fun insert(entry: EventLogEntity)

    @Query("SELECT * FROM event_log ORDER BY atMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<EventLogEntity>>

    @Query("SELECT * FROM event_log WHERE level >= :minLevel ORDER BY atMillis DESC LIMIT :limit")
    suspend fun recent(minLevel: LogLevel, limit: Int): List<EventLogEntity>

    @Query("SELECT * FROM event_log ORDER BY atMillis ASC")
    suspend fun allForExport(): List<EventLogEntity>

    /**
     * Ring-buffer trim. The log is a diagnostic aid, not an archive, and it
     * must never be allowed to grow without bound on a user's phone.
     */
    @Query("DELETE FROM event_log WHERE id NOT IN (SELECT id FROM event_log ORDER BY atMillis DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM event_log")
    suspend fun clear()
}
