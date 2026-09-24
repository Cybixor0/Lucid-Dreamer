// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lucidreamer.data.db.entity.CueEventEntity
import com.lucidreamer.data.db.entity.CueOutcome
import com.lucidreamer.data.db.entity.CueState
import com.lucidreamer.data.db.entity.HeartbeatEntity
import com.lucidreamer.data.db.entity.SessionEntity
import com.lucidreamer.data.db.entity.SessionState
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: Long): SessionEntity?

    /**
     * The session a cold-started process should adopt.
     *
     * Anything not yet finished counts, because a process killed at 3am leaves a
     * RUNNING row behind and the whole point is to pick that night back up.
     */
    @Query(
        """
        SELECT * FROM sessions
        WHERE state IN ('ARMED', 'RUNNING', 'PAUSED', 'WBTB_AWAKE')
        ORDER BY startedAtMillis DESC LIMIT 1
        """,
    )
    suspend fun activeSession(): SessionEntity?

    @Query(
        """
        SELECT * FROM sessions
        WHERE state IN ('ARMED', 'RUNNING', 'PAUSED', 'WBTB_AWAKE')
        ORDER BY startedAtMillis DESC LIMIT 1
        """,
    )
    fun observeActiveSession(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY startedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 60): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE state = 'FINISHED' OR state = 'STOPPED' ORDER BY startedAtMillis DESC LIMIT 1")
    suspend fun lastCompleted(): SessionEntity?

    @Query("UPDATE sessions SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: SessionState)

    @Query("UPDATE sessions SET state = :state, endedAtMillis = :endedAt WHERE id = :id")
    suspend fun finish(id: Long, state: SessionState, endedAt: Long)

    @Query("UPDATE sessions SET actualSleepAtMillis = :onset, wakeAtMillis = :wakeAt WHERE id = :id")
    suspend fun setActualOnset(id: Long, onset: Long, wakeAt: Long)

    @Query("UPDATE sessions SET wbtbReturnAtMillis = :at WHERE id = :id")
    suspend fun setWbtbReturn(id: Long, at: Long)

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun sessionCount(): Int
}

@Dao
interface CueEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cues: List<CueEventEntity>)

    @Query("SELECT * FROM cue_events WHERE sessionId = :sessionId ORDER BY scheduledAtMillis ASC")
    suspend fun forSession(sessionId: Long): List<CueEventEntity>

    @Query("SELECT * FROM cue_events WHERE sessionId = :sessionId ORDER BY scheduledAtMillis ASC")
    fun observeForSession(sessionId: Long): Flow<List<CueEventEntity>>

    @Query("SELECT * FROM cue_events WHERE id = :id")
    suspend fun byId(id: Long): CueEventEntity?

    @Query("SELECT * FROM cue_events WHERE alarmRequestCode = :code LIMIT 1")
    suspend fun byRequestCode(code: Int): CueEventEntity?

    /** The most recent cue that actually played, for "replay previous cue". */
    @Query(
        """
        SELECT * FROM cue_events
        WHERE sessionId = :sessionId AND outcome = 'PLAYED'
        ORDER BY firedAtMillis DESC LIMIT 1
        """,
    )
    suspend fun lastPlayed(sessionId: Long): CueEventEntity?

    @Query(
        """
        SELECT * FROM cue_events
        WHERE sessionId = :sessionId AND state = 'ARMED' AND scheduledAtMillis > :afterMillis
        ORDER BY scheduledAtMillis ASC LIMIT 1
        """,
    )
    suspend fun nextArmed(sessionId: Long, afterMillis: Long): CueEventEntity?

    @Query("SELECT * FROM cue_events WHERE sessionId = :sessionId AND state = 'ARMED'")
    suspend fun allArmed(sessionId: Long): List<CueEventEntity>

    /**
     * Claims a cue for playback.
     *
     * A compare-and-set on the state column, so a duplicated alarm delivery
     * plays once rather than twice. Returns the number of rows changed: zero
     * means somebody else already took it.
     */
    @Query("UPDATE cue_events SET state = 'FIRING', firedAtMillis = :now WHERE id = :id AND state = 'ARMED'")
    suspend fun claimForFiring(id: Long, now: Long): Int

    @Query("UPDATE cue_events SET state = :state, outcome = :outcome, routeAtFire = :route, errorDetail = :error WHERE id = :id")
    suspend fun recordOutcome(
        id: Long,
        state: CueState,
        outcome: CueOutcome,
        route: String?,
        error: String?,
    )

    @Query("UPDATE cue_events SET state = 'SKIPPED', outcome = 'SKIPPED_BY_USER' WHERE id = :id")
    suspend fun skip(id: Long)

    /**
     * Pushes a cue back to a later time and returns it to ARMED.
     *
     * Used by adaptive scheduling when it decides to wait for a better moment.
     * The original time is preserved the first time only, so repeated deferrals
     * still report how far the cue has moved from where the user put it.
     */
    @Query(
        """
        UPDATE cue_events
        SET state = 'ARMED',
            firedAtMillis = NULL,
            originalScheduledAtMillis = COALESCE(originalScheduledAtMillis, scheduledAtMillis),
            scheduledAtMillis = :newTimeMillis
        WHERE id = :id
        """,
    )
    suspend fun rearm(id: Long, newTimeMillis: Long)

    /** Anything still ARMED once its time has passed was never delivered. */
    @Query(
        """
        UPDATE cue_events SET state = 'MISSED', outcome = 'SERVICE_NOT_RUNNING'
        WHERE sessionId = :sessionId AND state = 'ARMED' AND scheduledAtMillis < :beforeMillis
        """,
    )
    suspend fun markMissed(sessionId: Long, beforeMillis: Long): Int

    @Query("DELETE FROM cue_events WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Query("SELECT COUNT(*) FROM cue_events WHERE outcome = 'PLAYED'")
    suspend fun totalPlayed(): Int
}

@Dao
interface HeartbeatDao {

    @Insert
    suspend fun insert(beat: HeartbeatEntity)

    @Query("SELECT * FROM heartbeats WHERE sessionId = :sessionId ORDER BY atMillis ASC")
    suspend fun forSession(sessionId: Long): List<HeartbeatEntity>

    /** Keeps the table bounded; heartbeats are diagnostics, not history. */
    @Query("DELETE FROM heartbeats WHERE atMillis < :beforeMillis")
    suspend fun pruneBefore(beforeMillis: Long)
}
