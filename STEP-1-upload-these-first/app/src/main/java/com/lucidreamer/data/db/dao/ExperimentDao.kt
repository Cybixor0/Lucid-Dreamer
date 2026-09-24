// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lucidreamer.data.db.entity.ExperimentEntity
import com.lucidreamer.data.db.entity.ExperimentRunEntity
import com.lucidreamer.data.db.entity.NightOutcome
import com.lucidreamer.data.db.entity.StageSampleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExperimentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(experiment: ExperimentEntity): Long

    @Update
    suspend fun update(experiment: ExperimentEntity)

    @Delete
    suspend fun delete(experiment: ExperimentEntity)

    @Query("SELECT * FROM experiments ORDER BY active DESC, createdAtMillis DESC")
    fun observeAll(): Flow<List<ExperimentEntity>>

    @Query("SELECT * FROM experiments ORDER BY active DESC, createdAtMillis DESC")
    suspend fun all(): List<ExperimentEntity>

    @Query("SELECT * FROM experiments WHERE id = :id")
    suspend fun byId(id: Long): ExperimentEntity?

    @Query("SELECT * FROM experiments WHERE active = 1 ORDER BY createdAtMillis DESC LIMIT 1")
    suspend fun activeExperiment(): ExperimentEntity?

    @Insert
    suspend fun insertRun(run: ExperimentRunEntity): Long

    @Update
    suspend fun updateRun(run: ExperimentRunEntity)

    @Query("SELECT * FROM experiment_runs WHERE experimentId = :experimentId ORDER BY nightEpochDay ASC")
    suspend fun runsFor(experimentId: Long): List<ExperimentRunEntity>

    @Query("SELECT * FROM experiment_runs WHERE experimentId = :experimentId ORDER BY nightEpochDay DESC")
    fun observeRuns(experimentId: Long): Flow<List<ExperimentRunEntity>>

    @Query("SELECT * FROM experiment_runs WHERE sessionId = :sessionId LIMIT 1")
    suspend fun runForSession(sessionId: Long): ExperimentRunEntity?

    @Query("SELECT * FROM experiment_runs WHERE nightEpochDay = :epochDay AND experimentId = :experimentId LIMIT 1")
    suspend fun runForNight(experimentId: Long, epochDay: Long): ExperimentRunEntity?

    /** Nights still awaiting an outcome, so the app can prompt for them. */
    @Query(
        """
        SELECT * FROM experiment_runs
        WHERE outcome = 'NOT_RECORDED' AND nightEpochDay < :beforeEpochDay
        ORDER BY nightEpochDay DESC LIMIT 5
        """,
    )
    suspend fun awaitingOutcome(beforeEpochDay: Long): List<ExperimentRunEntity>

    @Query("UPDATE experiment_runs SET outcome = :outcome, note = :note, recordedAtMillis = :atMillis WHERE id = :id")
    suspend fun recordOutcome(id: Long, outcome: NightOutcome, note: String, atMillis: Long)

    @Query("DELETE FROM experiment_runs WHERE experimentId = :experimentId")
    suspend fun deleteRuns(experimentId: Long)
}

@Dao
interface StageSampleDao {

    @Insert
    suspend fun insert(sample: StageSampleEntity)

    @Insert
    suspend fun insertAll(samples: List<StageSampleEntity>)

    @Query("SELECT * FROM stage_samples WHERE sessionId = :sessionId ORDER BY atMillis ASC")
    suspend fun forSession(sessionId: Long): List<StageSampleEntity>

    @Query("SELECT * FROM stage_samples WHERE sessionId = :sessionId ORDER BY atMillis ASC")
    fun observeForSession(sessionId: Long): Flow<List<StageSampleEntity>>

    /** Recent usable estimates, for the adaptive scheduler. */
    @Query(
        """
        SELECT * FROM stage_samples
        WHERE sessionId = :sessionId AND confidence >= :minConfidence
        ORDER BY atMillis DESC LIMIT :limit
        """,
    )
    suspend fun recentUsable(sessionId: Long, minConfidence: Float, limit: Int = 60): List<StageSampleEntity>

    @Query("SELECT * FROM stage_samples WHERE sessionId = :sessionId ORDER BY atMillis DESC LIMIT 1")
    suspend fun latest(sessionId: Long): StageSampleEntity?

    /** Estimates are diagnostics, not history; keep the table bounded. */
    @Query("DELETE FROM stage_samples WHERE atMillis < :beforeMillis")
    suspend fun pruneBefore(beforeMillis: Long)

    @Query("DELETE FROM stage_samples")
    suspend fun deleteAll()
}
