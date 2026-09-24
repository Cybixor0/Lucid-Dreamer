// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.data.repo

import com.lucidreamer.core.DeviceStatus
import com.lucidreamer.data.SettingsStore
import com.lucidreamer.data.db.ColumnJson
import com.lucidreamer.data.db.LucidDatabase
import com.lucidreamer.data.db.entity.CueEventEntity
import com.lucidreamer.data.db.entity.CueOutcome
import com.lucidreamer.data.db.entity.CueState
import com.lucidreamer.data.db.entity.SessionEntity
import com.lucidreamer.domain.cue.CueSound
import com.lucidreamer.domain.diagnostics.ReliabilityReport
import com.lucidreamer.domain.schedule.ResolvedNight
import com.lucidreamer.domain.schedule.ScheduleResolver
import com.lucidreamer.domain.schedule.SleepAnchors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Read model for sessions, plus assembly of the morning reliability report.
 */
class SessionRepository(
    private val db: LucidDatabase,
    private val settings: SettingsStore,
    private val deviceStatus: DeviceStatus,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun observeActiveSession(): Flow<SessionEntity?> = db.sessionDao().observeActiveSession()

    fun observeRecentSessions(limit: Int = 60): Flow<List<SessionEntity>> =
        db.sessionDao().observeRecent(limit)

    fun observeCues(sessionId: Long): Flow<List<CueEventEntity>> =
        db.cueEventDao().observeForSession(sessionId)

    suspend fun activeSession(): SessionEntity? = db.sessionDao().activeSession()

    /**
     * What tonight looks like before a session has been started.
     *
     * Lets the dashboard show the estimated sleep period and cue count without
     * committing to anything, so the user can see what would happen and adjust
     * first.
     */
    suspend fun previewTonight(): ResolvedNight {
        val now = ZonedDateTime.now(zone)
        val today = now.toLocalDate()
        val night = listOf(today.minusDays(1), today, today.plusDays(1))
            .minByOrNull { date ->
                val bedAt = anchorsFor(date).resolveFor(date, zone).bedAt
                kotlin.math.abs(java.time.Duration.between(bedAt, now).toMinutes())
            } ?: today
        return anchorsFor(night).resolveFor(night, zone)
    }

    suspend fun anchorsFor(night: LocalDate): SleepAnchors {
        val profiles = db.scheduleProfileDao().all().map { it.toDomain() }
        val overrides = db.nightOverrideDao().from(night.toEpochDay()).map { it.toDomain() }
        return ScheduleResolver.resolveAnchors(
            night = night,
            profiles = profiles,
            overrides = overrides,
            fallback = settings.defaultAnchors.first(),
        ).anchors
    }

    suspend fun explainSchedule(night: LocalDate): String {
        val profiles = db.scheduleProfileDao().all().map { it.toDomain() }
        val overrides = db.nightOverrideDao().from(night.toEpochDay()).map { it.toDomain() }
        return ScheduleResolver.resolveAnchors(
            night = night,
            profiles = profiles,
            overrides = overrides,
            fallback = settings.defaultAnchors.first(),
        ).explanation
    }

    // -----------------------------------------------------------------------
    // Reliability report
    // -----------------------------------------------------------------------

    /**
     * Builds the report for the most recently completed session, if the user
     * has not already been shown it.
     */
    suspend fun pendingReport(): ReliabilityReport? {
        val session = db.sessionDao().lastCompleted() ?: return null
        if (settings.lastReportedSessionId.first() == session.id) return null
        return buildReport(session)
    }

    suspend fun markReportSeen(sessionId: Long) = settings.setLastReportedSessionId(sessionId)

    suspend fun reportFor(sessionId: Long): ReliabilityReport? =
        db.sessionDao().byId(sessionId)?.let { buildReport(it) }

    private suspend fun buildReport(session: SessionEntity): ReliabilityReport {
        val cues = db.cueEventDao().forSession(session.id)
        val heartbeats = db.heartbeatDao().forSession(session.id)
        val endMillis = session.endedAtMillis ?: System.currentTimeMillis()

        val latencies = cues
            .filter { it.outcome == CueOutcome.PLAYED && it.firedAtMillis != null }
            .map { (it.firedAtMillis!! - it.scheduledAtMillis).coerceAtLeast(0L) }

        return ReliabilityReport.build(
            ReliabilityReport.Input(
                sessionId = session.id,
                sessionStartMillis = session.startedAtMillis,
                sessionEndMillis = endMillis,
                heartbeatMillis = heartbeats.map { it.atMillis },
                cuesScheduled = cues.count { it.state != CueState.DROPPED },
                cuesPlayed = cues.count { it.outcome == CueOutcome.PLAYED },
                cuesMissed = cues.count { it.state == CueState.MISSED },
                cuesSkippedByUser = cues.count { it.outcome == CueOutcome.SKIPPED_BY_USER },
                cueLatenciesMillis = latencies,
                anyCueSilencedByDnd = cues.any { it.outcome == CueOutcome.SILENCED_BY_DND },
                anyCueHadNoRoute = cues.any { it.outcome == CueOutcome.NO_AUDIO_ROUTE },
                // Current values rather than historical: they are what the user
                // would change now, and the report's purpose is to tell them
                // what to do next.
                ignoringBatteryOptimisations = deviceStatus.isIgnoringBatteryOptimisations(),
                canScheduleExactAlarms = deviceStatus.canScheduleExactAlarms(),
                worstStandbyBucket = heartbeats.maxOfOrNull { it.standbyBucket } ?: deviceStatus.standbyBucket(),
                isSamsung = deviceStatus.isSamsung,
            ),
        )
    }

    // -----------------------------------------------------------------------
    // Helpers for the UI
    // -----------------------------------------------------------------------

    fun SessionEntity.resolved(): ResolvedNight = ResolvedNight(
        night = LocalDate.ofEpochDay(nightEpochDay),
        zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(zone),
        bedAt = bedAtMillis.zdt(),
        estimatedSleepAt = estimatedSleepAtMillis.zdt(),
        wakeAt = wakeAtMillis.zdt(),
        actualSleepAt = actualSleepAtMillis?.zdt(),
    )

    fun cueSoundOf(cue: CueEventEntity): CueSound? =
        runCatching { ColumnJson.decodeFromString<CueSound>(cue.soundJson) }.getOrNull()

    private fun Long.zdt(): ZonedDateTime = Instant.ofEpochMilli(this).atZone(zone)
}
