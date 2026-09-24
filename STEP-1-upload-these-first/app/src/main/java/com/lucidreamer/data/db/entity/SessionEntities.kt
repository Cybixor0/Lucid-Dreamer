// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * State of a night.
 *
 * The session is persisted rather than held in memory because the process will
 * be killed at some point during an eight-hour night on a real phone, and the
 * night has to survive that. Any cold start must be able to reconstruct the
 * whole plan from these rows alone.
 */
enum class SessionState { ARMED, RUNNING, PAUSED, WBTB_AWAKE, FINISHED, STOPPED }

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Epoch day of the night the user got into bed. */
    val nightEpochDay: Long,
    val state: SessionState,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,

    /** Zone the plan was computed in, so a timezone change can be detected. */
    val zoneId: String,

    val bedAtMillis: Long,
    val estimatedSleepAtMillis: Long,
    /** Set only when the user pressed "I'm going to sleep now". */
    val actualSleepAtMillis: Long? = null,
    val wakeAtMillis: Long,

    /**
     * Fixed once per night and never regenerated. Re-planning after a reboot,
     * timezone change or crash reuses it so randomised cue times reproduce
     * exactly instead of silently reshuffling the rest of the night.
     */
    val seed: Long,

    val cueProfileId: Long,
    val anchorsJson: String,
    /** Set when the user goes back to bed after a WBTB wake. */
    val wbtbReturnAtMillis: Long? = null,
    val scheduleExplanation: String = "",
)

enum class CueState { ARMED, FIRING, FIRED, MISSED, SKIPPED, DROPPED }

/**
 * Why a cue did or did not make a sound.
 *
 * Silent failure is the normal failure mode for background audio on modern
 * Android, so the outcome is recorded explicitly rather than inferred from the
 * absence of a crash.
 */
enum class CueOutcome {
    PENDING,
    PLAYED,
    SKIPPED_BY_USER,
    NO_AUDIO_ROUTE,
    FOCUS_DENIED,
    SILENCED_BY_DND,
    PLAYBACK_ERROR,
    SERVICE_NOT_RUNNING,
    TOO_LATE,

    /** Adaptive scheduling decided the estimated sleep stage was wrong for this cue. */
    SKIPPED_BY_STAGE,
}

@Entity(
    tableName = "cue_events",
    indices = [Index("sessionId"), Index("scheduledAtMillis"), Index(value = ["alarmRequestCode"], unique = true)],
)
data class CueEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val ruleId: Long,
    val indexWithinRule: Int,

    val scheduledAtMillis: Long,
    /** When it actually fired. The difference is the real latency distribution. */
    val firedAtMillis: Long? = null,

    val state: CueState,
    val outcome: CueOutcome = CueOutcome.PENDING,

    /** Deterministic; see CuePlanner.requestCode. */
    val alarmRequestCode: Int,

    val soundJson: String,
    val playbackJson: String,
    val reason: String,
    /**
     * The rule's [com.lucidreamer.domain.cue.StageGate], stored by name.
     *
     * Denormalised onto the cue so the playback service can apply it without
     * reloading and re-parsing the whole cue profile at 4am.
     *
     * The SQL default is declared here, not just as a Kotlin default, because
     * `ALTER TABLE ADD COLUMN` on a NOT NULL column *requires* a DEFAULT. Room
     * validates the migrated schema against this declaration, so without it the
     * upgrade fails at runtime with "Migration didn't properly handle
     * cue_events" - a crash that only appears on a real upgrade, never on a
     * fresh install.
     */
    @ColumnInfo(defaultValue = "ANY")
    val stageGate: String = "ANY",
    /** Set when adaptive scheduling has already pushed this cue back. */
    val originalScheduledAtMillis: Long? = null,
    /** What the audio was actually routed to when it fired, for diagnostics. */
    val routeAtFire: String? = null,
    val errorDetail: String? = null,
)

/** Where a heartbeat came from, so gaps can be attributed. */
enum class HeartbeatSource { SESSION_SERVICE, WATCHDOG_ALARM, CUE_ALARM, APP_FOREGROUND, BOOT }

/**
 * Periodic proof-of-life.
 *
 * Reconstructing these rows in the morning is how the app tells the user
 * "you were frozen for 3h42m and missed 2 cues" instead of leaving an
 * unattributable failure that feels like the app is simply broken. There is no
 * API that reports a manufacturer having suspended you; this is the only way to
 * find out, and it is the difference between a diagnosable app and a haunted one.
 */
@Entity(tableName = "heartbeats", indices = [Index("sessionId"), Index("atMillis")])
data class HeartbeatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long?,
    val atMillis: Long,
    val source: HeartbeatSource,
    val processId: Int,
    val serviceRunning: Boolean,
    val wakeLockHeld: Boolean,
    /** UsageStatsManager bucket, or -1 below API 28. */
    val standbyBucket: Int,
    val ignoringBatteryOptimisations: Boolean,
    val interruptionFilter: Int,
)

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Developer-mode event log, kept as a bounded ring buffer.
 *
 * Exportable as text, and the intended format for bug reports in an
 * open-source project where the maintainer cannot reproduce a user's phone.
 */
@Entity(tableName = "event_log", indices = [Index("atMillis")])
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val sessionId: Long? = null,
)
