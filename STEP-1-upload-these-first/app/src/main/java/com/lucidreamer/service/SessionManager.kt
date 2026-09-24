// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.lucidreamer.audio.CueAssetStore
import com.lucidreamer.core.EventLog
import com.lucidreamer.data.SettingsStore
import com.lucidreamer.data.db.ColumnJson
import com.lucidreamer.data.db.LucidDatabase
import com.lucidreamer.data.db.entity.CueEventEntity
import com.lucidreamer.data.db.entity.CueState
import com.lucidreamer.data.db.entity.SessionEntity
import com.lucidreamer.data.db.entity.SessionState
import com.lucidreamer.domain.cue.CueProfile
import com.lucidreamer.domain.cue.CuePlanner
import com.lucidreamer.domain.cue.NightPlan
import com.lucidreamer.domain.experiment.ExperimentManager
import com.lucidreamer.domain.schedule.ResolvedNight
import com.lucidreamer.domain.schedule.ScheduleResolver
import com.lucidreamer.domain.schedule.SleepAnchors
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * Owns the lifecycle of a night.
 *
 * Everything here works from persisted state rather than memory, so any of the
 * several things that can restart the process overnight - a reboot, an app
 * update, the system reclaiming memory, a manufacturer's battery manager - can
 * be recovered from by reloading the session and re-arming. [replan] is the
 * single idempotent entry point all of those paths use.
 */
class SessionManager(
    private val context: Context,
    private val db: LucidDatabase,
    private val settings: SettingsStore,
    private val alarms: AlarmScheduler,
    private val assets: CueAssetStore,
    private val log: EventLog,
    /**
     * Optional so the session engine has no hard dependency on experiments -
     * the app works identically with the feature unused.
     */
    private val experimentManager: ExperimentManager? = null,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    // -----------------------------------------------------------------------
    // Starting
    // -----------------------------------------------------------------------

    /**
     * Starts tonight's session.
     *
     * @param reportedOnsetNow true when the user pressed "I'm going to sleep
     *   now", which replaces the estimated onset with a real anchor and is the
     *   single biggest improvement available to cue accuracy.
     */
    suspend fun startTonight(
        reportedOnsetNow: Boolean = false,
        /**
         * True when the user started this from the open app.
         *
         * Decides whether microphone sensing is possible at all tonight -
         * Android will not grant a foreground service microphone access if the
         * service was started from the background.
         */
        fromForeground: Boolean = false,
    ): Result<Long> = runCatching {
        db.sessionDao().activeSession()?.let { existing ->
            log.i(EventLog.TAG_SESSION, "Session ${existing.id} already active; reusing it")
            return@runCatching existing.id
        }

        val now = ZonedDateTime.now(zone)
        val night = nightDateFor(now)
        val resolved = resolveNight(night, now, reportedOnsetNow)
        val profile = activeCueProfile(night = night)

        // Generated exactly once per night and persisted. Every later replan
        // reuses it, so randomised cue times survive a crash instead of
        // silently reshuffling the rest of the night.
        val seed = Random.nextLong()

        val plan = CuePlanner.plan(resolved, profile, seed)
        val session = SessionEntity(
            nightEpochDay = night.toEpochDay(),
            state = SessionState.RUNNING,
            startedAtMillis = now.toInstant().toEpochMilli(),
            zoneId = zone.id,
            bedAtMillis = resolved.bedAt.millis(),
            estimatedSleepAtMillis = resolved.estimatedSleepAt.millis(),
            actualSleepAtMillis = resolved.actualSleepAt?.millis(),
            wakeAtMillis = resolved.wakeAt.millis(),
            seed = seed,
            cueProfileId = profile.id,
            anchorsJson = ColumnJson.encodeToString(anchorsFor(night)),
            scheduleExplanation = plan.explanation,
        )

        val sessionId = db.sessionDao().insert(session)
        persistPlan(sessionId, plan)

        log.i(EventLog.TAG_SESSION, "Started session $sessionId. ${plan.explanation}", sessionId)
        plan.dropped.forEach { log.d(EventLog.TAG_SCHEDULER, "Not scheduled: ${it.reason}", sessionId) }

        // Resolve every sound now, while the user is awake to be told about a
        // missing file or an absent speech engine.
        prewarmAssets(plan, sessionId)

        armEverything(sessionId)
        startForegroundService(fromForeground)
        sessionId
    }.onFailure { log.e(EventLog.TAG_SESSION, "Could not start session", it) }

    private suspend fun prewarmAssets(plan: NightPlan, sessionId: Long) {
        plan.cues.map { it.sound }.distinct().forEach { sound ->
            when (val r = assets.resolve(sound)) {
                is CueAssetStore.Resolution.Failed ->
                    log.w(EventLog.TAG_AUDIO, "Cue sound unavailable: ${r.reason}", sessionId)

                else -> Unit
            }
        }
    }

    // -----------------------------------------------------------------------
    // Re-planning - the recovery path
    // -----------------------------------------------------------------------

    /**
     * Recomputes and re-arms an in-flight session.
     *
     * Called after a reboot, an app update, a clock or timezone change, and
     * from every watchdog heartbeat. Deliberately idempotent: cue alarm request
     * codes are derived from (night, rule, index), so re-arming updates the
     * existing alarms rather than piling up duplicates.
     */
    suspend fun replan(reason: String): Result<Unit> = runCatching {
        val session = db.sessionDao().activeSession() ?: return@runCatching
        val now = System.currentTimeMillis()

        // Anything still armed whose moment has passed was never delivered.
        val missed = db.cueEventDao().markMissed(session.id, now)
        if (missed > 0) {
            log.w(EventLog.TAG_SCHEDULER, "$missed cue(s) were never delivered", session.id)
        }

        if (now >= session.wakeAtMillis) {
            log.i(EventLog.TAG_SESSION, "Session ${session.id} is past its wake time; finishing", session.id)
            finish(session.id, SessionState.FINISHED)
            return@runCatching
        }

        val storedZone = runCatching { ZoneId.of(session.zoneId) }.getOrDefault(zone)
        val zoneChanged = storedZone != zone

        if (zoneChanged) {
            log.w(
                EventLog.TAG_SCHEDULER,
                "Timezone changed from ${session.zoneId} to ${zone.id}; recomputing the night",
                session.id,
            )
            rebuildPlan(session)
        } else {
            val armed = db.cueEventDao().allArmed(session.id)
            val precise = settings.preciseAlarms.first()
            alarms.armCues(armed, precise, zone)
        }

        alarms.armNextWatchdog()
        log.d(EventLog.TAG_SCHEDULER, "Replanned ($reason)", session.id)
    }.onFailure { log.e(EventLog.TAG_SCHEDULER, "Replan failed ($reason)", it) }

    /**
     * Rebuilds the cue list from the stored rules.
     *
     * This is why cue rules are persisted as wall-clock rules rather than as
     * resolved timestamps: after a timezone change or a daylight-saving
     * transition the rules still mean what the user intended, and the instants
     * can simply be recomputed.
     */
    private suspend fun rebuildPlan(session: SessionEntity) {
        val old = db.cueEventDao().forSession(session.id)
        alarms.cancelAllCues(old.filter { it.state == CueState.ARMED })

        val anchors = runCatching { ColumnJson.decodeFromString<SleepAnchors>(session.anchorsJson) }
            .getOrDefault(SleepAnchors())
        val night = LocalDate.ofEpochDay(session.nightEpochDay)
        val resolved = anchors.resolveFor(night, zone).let { base ->
            session.actualSleepAtMillis
                ?.let { base.withActualOnset(it.zdt(), anchors.basis, anchors.impliedDuration()) }
                ?: base
        }

        val profile = db.cueProfileDao().byId(session.cueProfileId)?.toDomain() ?: activeCueProfile()
        val plan = CuePlanner.plan(resolved, profile, session.seed, session.wbtbReturnAtMillis?.zdt())

        // Keep the history of what already happened; only replace what is still ahead.
        val fired = old.filter { it.state != CueState.ARMED }.map { it.alarmRequestCode }.toSet()
        db.cueEventDao().deleteForSession(session.id)
        db.cueEventDao().insertAll(
            old.filter { it.alarmRequestCode in fired } +
                plan.cues.filter { it.alarmRequestCode !in fired }.map { it.toEntity(session.id) },
        )

        db.sessionDao().update(session.copy(zoneId = zone.id, scheduleExplanation = plan.explanation))
        armEverything(session.id)
    }

    private suspend fun armEverything(sessionId: Long) {
        val session = db.sessionDao().byId(sessionId) ?: return
        val precise = settings.preciseAlarms.first()
        val armed = db.cueEventDao().allArmed(sessionId)
        alarms.armCues(armed, precise, zone)
        alarms.armNextWatchdog()

        if (settings.wbtbEnabled.first() && session.wbtbReturnAtMillis == null) {
            val config = settings.wbtbConfig.first()
            val wakeAt = config.wakeAt(session.toResolved())
            if (wakeAt.millis() > System.currentTimeMillis()) {
                alarms.armWbtbWake(wakeAt.millis(), sessionId, zone)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Controls
    // -----------------------------------------------------------------------

    suspend fun pause() = withActiveSession { session ->
        db.sessionDao().setState(session.id, SessionState.PAUSED)
        alarms.cancelAllCues(db.cueEventDao().allArmed(session.id))
        log.i(EventLog.TAG_SESSION, "Session paused", session.id)
    }

    suspend fun resume() = withActiveSession { session ->
        db.sessionDao().setState(session.id, SessionState.RUNNING)
        armEverything(session.id)
        log.i(EventLog.TAG_SESSION, "Session resumed", session.id)
    }

    suspend fun stop() = withActiveSession { session ->
        finish(session.id, SessionState.STOPPED)
        log.i(EventLog.TAG_SESSION, "Session stopped by the user", session.id)
    }

    suspend fun skipNextCue() = withActiveSession { session ->
        val next = db.cueEventDao().nextArmed(session.id, System.currentTimeMillis())
        if (next == null) {
            log.d(EventLog.TAG_SESSION, "Skip requested but no cue is pending", session.id)
            return@withActiveSession
        }
        alarms.cancelCue(next)
        db.cueEventDao().skip(next.id)
        log.i(EventLog.TAG_SESSION, "Skipped cue ${next.id}", session.id)
    }

    /** Replays the last cue that actually made a sound. */
    suspend fun replayLastCue() = withActiveSession { session ->
        val last = db.cueEventDao().lastPlayed(session.id)
        if (last == null) {
            log.d(EventLog.TAG_SESSION, "Replay requested but nothing has played yet", session.id)
            return@withActiveSession
        }
        ContextCompat.startForegroundService(
            context,
            Intent(context, CuePlaybackService::class.java).apply {
                action = CuePlaybackService.ACTION_TEST_CUE
                putExtra(CuePlaybackService.EXTRA_SOUND_JSON, last.soundJson)
                putExtra(CuePlaybackService.EXTRA_PLAYBACK_JSON, last.playbackJson)
            },
        )
        log.i(EventLog.TAG_SESSION, "Replaying cue ${last.id}", session.id)
    }

    /**
     * "I'm going to sleep now."
     *
     * Replaces the estimated onset with a real one and re-plans, which shifts
     * every relative cue to where the user actually meant it.
     */
    suspend fun reportSleepingNow() = withActiveSession { session ->
        val anchors = runCatching { ColumnJson.decodeFromString<SleepAnchors>(session.anchorsJson) }
            .getOrDefault(SleepAnchors())
        val onset = ZonedDateTime.now(zone).plus(anchors.onsetLatency)
        val night = LocalDate.ofEpochDay(session.nightEpochDay)
        val resolved = anchors.resolveFor(night, zone)
            .withActualOnset(onset, anchors.basis, anchors.impliedDuration())

        db.sessionDao().setActualOnset(session.id, onset.millis(), resolved.wakeAt.millis())
        log.i(
            EventLog.TAG_SESSION,
            "Sleep onset reported: expecting sleep at ${onset.toLocalTime().withNano(0)}",
            session.id,
        )
        rebuildPlan(db.sessionDao().byId(session.id)!!)
    }

    /** The user has gone back to bed after a WBTB wake; anchor the return cues here. */
    suspend fun wbtbBackToBed() = withActiveSession { session ->
        val now = ZonedDateTime.now(zone)
        db.sessionDao().setWbtbReturn(session.id, now.millis())
        db.sessionDao().setState(session.id, SessionState.RUNNING)
        log.i(EventLog.TAG_WBTB, "Back to bed at ${now.toLocalTime().withNano(0)}", session.id)
        rebuildPlan(db.sessionDao().byId(session.id)!!)
    }

    /** The in-flight session, if there is one. Used when tearing everything down. */
    suspend fun activeSessionOrNull(): SessionEntity? = db.sessionDao().activeSession()

    suspend fun finish(sessionId: Long, state: SessionState) {
        val cues = db.cueEventDao().forSession(sessionId)
        alarms.cancelAllCues(cues.filter { it.state == CueState.ARMED })
        alarms.cancelWatchdog()
        alarms.cancelWbtb()
        db.cueEventDao().markMissed(sessionId, System.currentTimeMillis())
        db.sessionDao().finish(sessionId, state, System.currentTimeMillis())
        assets.clearCache()
        context.stopService(Intent(context, SleepSessionService::class.java))
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private suspend fun withActiveSession(block: suspend (SessionEntity) -> Unit) {
        val session = db.sessionDao().activeSession()
        if (session == null) {
            log.d(EventLog.TAG_SESSION, "No active session for this action")
            return
        }
        runCatching { block(session) }
            .onFailure { log.e(EventLog.TAG_SESSION, "Session action failed", it, session.id) }
    }

    /**
     * The date a session belongs to, where "night" means the date you got into
     * bed.
     *
     * Chosen as the candidate date whose configured bedtime is nearest to now,
     * rather than by a fixed hour-of-day cutoff. A cutoff works fine for a
     * conventional sleeper but breaks the moment the schedule is unusual: a
     * night-shift worker who goes to bed at 09:00 would be assigned to the
     * previous day and end up with a bedtime twenty-four hours in the past.
     * Comparing against the user's own bedtime handles every schedule - late,
     * early, daytime, irregular - with no special cases.
     */
    private suspend fun nightDateFor(now: ZonedDateTime): LocalDate {
        val today = now.toLocalDate()
        return listOf(today.minusDays(1), today, today.plusDays(1))
            .minByOrNull { date ->
                val bedAt = anchorsFor(date).resolveFor(date, zone).bedAt
                kotlin.math.abs(java.time.Duration.between(bedAt, now).toMinutes())
            } ?: today
    }

    private suspend fun anchorsFor(night: LocalDate): SleepAnchors {
        val profiles = db.scheduleProfileDao().all().map { it.toDomain() }
        val overrides = db.nightOverrideDao().from(night.toEpochDay()).map { it.toDomain() }
        return ScheduleResolver.resolveAnchors(
            night = night,
            profiles = profiles,
            overrides = overrides,
            fallback = settings.defaultAnchors.first(),
        ).anchors
    }

    private suspend fun resolveNight(
        night: LocalDate,
        now: ZonedDateTime,
        reportedOnsetNow: Boolean,
    ): ResolvedNight {
        val anchors = anchorsFor(night)
        val base = anchors.resolveFor(night, zone)
        return if (reportedOnsetNow) {
            base.withActualOnset(now.plus(anchors.onsetLatency), anchors.basis, anchors.impliedDuration())
        } else {
            base
        }
    }

    /**
     * Tonight's cue profile.
     *
     * A running experiment overrides the user's selection for the night, which
     * is the entire point of the feature - but the assignment is recorded, so
     * the morning report and the comparison both know which arm this was.
     */
    private suspend fun activeCueProfile(night: LocalDate? = null, sessionId: Long? = null): CueProfile {
        if (night != null) {
            experimentManager?.cueProfileForNight(night, sessionId)?.let { experimentProfileId ->
                db.cueProfileDao().byId(experimentProfileId)?.let { return it.toDomain() }
            }
        }

        val id = settings.activeCueProfileId.first()
        db.cueProfileDao().byId(id)?.let { return it.toDomain() }
        // Falls back to whatever exists rather than failing: a user who deleted
        // their selected profile should still get a working night.
        return db.cueProfileDao().all().firstOrNull()?.toDomain()
            ?: CueProfile(id = 0, name = "Empty", rules = emptyList())
    }

    private suspend fun persistPlan(sessionId: Long, plan: NightPlan) {
        db.cueEventDao().insertAll(plan.cues.map { it.toEntity(sessionId) })
    }

    private fun startForegroundService(fromForeground: Boolean) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, SleepSessionService::class.java).apply {
                action = SleepSessionService.ACTION_START
                putExtra(SleepSessionService.EXTRA_FROM_FOREGROUND, fromForeground)
            },
        )
    }

    private fun com.lucidreamer.domain.cue.PlannedCue.toEntity(sessionId: Long) = CueEventEntity(
        sessionId = sessionId,
        ruleId = ruleId,
        indexWithinRule = indexWithinRule,
        scheduledAtMillis = at.millis(),
        state = CueState.ARMED,
        alarmRequestCode = alarmRequestCode,
        soundJson = ColumnJson.encodeToString(sound),
        playbackJson = ColumnJson.encodeToString(playback),
        reason = reason,
        stageGate = stageGate.name,
    )

    private fun SessionEntity.toResolved(): ResolvedNight = ResolvedNight(
        night = LocalDate.ofEpochDay(nightEpochDay),
        zone = zone,
        bedAt = bedAtMillis.zdt(),
        estimatedSleepAt = estimatedSleepAtMillis.zdt(),
        wakeAt = wakeAtMillis.zdt(),
        actualSleepAt = actualSleepAtMillis?.zdt(),
    )

    private fun ZonedDateTime.millis(): Long = toInstant().toEpochMilli()
    private fun Long.zdt(): ZonedDateTime = Instant.ofEpochMilli(this).atZone(zone)
}
