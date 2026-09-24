// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lucidreamer.LucidDreamerApp
import com.lucidreamer.R
import com.lucidreamer.audio.CueAssetStore
import com.lucidreamer.core.EventLog
import com.lucidreamer.core.Notifications
import com.lucidreamer.data.db.ColumnJson
import com.lucidreamer.data.db.entity.CueEventEntity
import com.lucidreamer.data.db.entity.CueOutcome
import com.lucidreamer.data.db.entity.CueState
import com.lucidreamer.domain.cue.AdaptiveScheduler
import com.lucidreamer.domain.cue.CuePlayback
import com.lucidreamer.domain.cue.CueSound
import com.lucidreamer.domain.cue.SchedulingMode
import com.lucidreamer.domain.cue.StageGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Plays a single cue, then stops.
 *
 * A short-lived `mediaPlayback` foreground service. The type is not
 * decorative: on recent Android an app with no visible activity is blocked
 * from producing audio at all unless it is running a foreground service, and
 * the block is silent - no exception is thrown, the sound simply never
 * happens. `mediaPlayback` also has no time limit (unlike `dataSync`, which is
 * capped well short of a night) and, not being a while-in-use type, it can be
 * legitimately started from the background by an exact alarm.
 *
 * The service takes over the wake lock that [CueAlarmReceiver] acquired and
 * releases it when playback finishes.
 */
class CuePlaybackService : Service() {

    companion object {
        const val ACTION_PLAY_CUE = "com.lucidreamer.action.PLAY_CUE"
        const val ACTION_TEST_CUE = "com.lucidreamer.action.TEST_CUE"
        const val EXTRA_CUE_ID = CueAlarmReceiver.EXTRA_CUE_ID
        const val EXTRA_SESSION_ID = CueAlarmReceiver.EXTRA_SESSION_ID
        const val EXTRA_SOUND_JSON = "sound_json"
        const val EXTRA_PLAYBACK_JSON = "playback_json"
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var activeWork: Job? = null

    private val app get() = application as LucidDreamerApp
    private val container get() = app.container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must reach startForeground within a few seconds of being started, or
        // the system kills the process. Done first, before any work.
        promoteToForeground()

        when (intent?.action) {
            ACTION_PLAY_CUE -> {
                val cueId = intent.getLongExtra(EXTRA_CUE_ID, -1L)
                val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L).takeIf { it >= 0 }
                activeWork = scope.launch { playScheduledCue(cueId, sessionId) }
            }

            ACTION_TEST_CUE -> {
                val soundJson = intent.getStringExtra(EXTRA_SOUND_JSON)
                val playbackJson = intent.getStringExtra(EXTRA_PLAYBACK_JSON)
                activeWork = scope.launch { playTestCue(soundJson, playbackJson) }
            }

            else -> stopNow()
        }

        // Not START_STICKY: a restarted playback service with a null intent has
        // no cue to play, and the schedule is driven by alarms anyway.
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        val notification: Notification = NotificationCompat.Builder(this, Notifications.CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.cue_playing_title))
            .setContentText(getString(R.string.cue_playing_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        ServiceCompat.startForeground(
            this,
            Notifications.ID_CUE_PLAYBACK,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private suspend fun playScheduledCue(cueId: Long, sessionId: Long?) {
        val log = container.eventLog
        val dao = container.db.cueEventDao()

        try {
            val cue = dao.byId(cueId)
            if (cue == null) {
                log.w(EventLog.TAG_CUE, "Cue $cueId no longer exists; nothing played", sessionId)
                return
            }

            // Compare-and-set, so a duplicated alarm delivery plays once.
            val claimed = dao.claimForFiring(cueId, System.currentTimeMillis())
            if (claimed == 0) {
                log.d(EventLog.TAG_CUE, "Cue $cueId was already claimed; skipping duplicate", sessionId)
                return
            }

            val lateBy = System.currentTimeMillis() - cue.scheduledAtMillis
            if (lateBy > TOO_LATE_MS) {
                dao.recordOutcome(cueId, CueState.MISSED, CueOutcome.TOO_LATE, null, "${lateBy / 1000}s late")
                log.w(EventLog.TAG_CUE, "Cue $cueId arrived ${lateBy / 1000}s late; not played", sessionId)
                return
            }

            val sound = ColumnJson.decodeFromString<CueSound>(cue.soundJson)
            val playback = ColumnJson.decodeFromString<CuePlayback>(cue.playbackJson)

            // Adaptive gating. Only consulted when the user asked for it; and
            // when the estimate is not trustworthy the decision is always to
            // play as scheduled, never to swallow the cue.
            if (!passesStageGate(cue, sessionId)) return

            when (val resolution = container.cueAssets.resolve(sound)) {
                is CueAssetStore.Resolution.SilentByDesign -> {
                    dao.recordOutcome(cueId, CueState.FIRED, CueOutcome.PLAYED, "silent", null)
                    log.i(EventLog.TAG_CUE, "Cue $cueId is silent by design (vibration only)", sessionId)
                }

                is CueAssetStore.Resolution.Failed -> {
                    dao.recordOutcome(cueId, CueState.FIRED, CueOutcome.PLAYBACK_ERROR, null, resolution.reason)
                    log.e(EventLog.TAG_CUE, "Cue $cueId could not be prepared: ${resolution.reason}", null, sessionId)
                }

                is CueAssetStore.Resolution.Ready -> {
                    val result = container.cuePlayer.play(resolution.pcm, playback)
                    dao.recordOutcome(
                        cueId,
                        if (result.outcome == CueOutcome.PLAYED) CueState.FIRED else CueState.MISSED,
                        result.outcome,
                        "${result.route.label} (${result.routeName})",
                        result.error,
                    )
                    log.i(
                        EventLog.TAG_CUE,
                        "Cue $cueId -> ${result.outcome} via ${result.route.label}" +
                            (if (!result.focusGranted && playback.requestAudioFocus) ", focus denied" else "") +
                            (result.error?.let { ", $it" } ?: "") +
                            ", ${lateBy}ms after target",
                        sessionId,
                    )
                }
            }
        } catch (e: Exception) {
            log.e(EventLog.TAG_CUE, "Unexpected failure playing cue $cueId", e, sessionId)
            runCatching { dao.recordOutcome(cueId, CueState.MISSED, CueOutcome.PLAYBACK_ERROR, null, e.message) }
        } finally {
            SessionNotifications.refresh(applicationContext)
            stopNow()
        }
    }

    /**
     * Applies the cue's sleep-stage condition, if adaptive scheduling is on.
     *
     * @return true to carry on and play. A deferral re-arms the alarm and
     *   returns false; a skip records the reason and returns false.
     */
    private suspend fun passesStageGate(cue: CueEventEntity, sessionId: Long?): Boolean {
        val gate = runCatching { StageGate.valueOf(cue.stageGate) }.getOrDefault(StageGate.ANY)
        if (gate == StageGate.ANY) return true

        val mode = container.settings.schedulingMode.first()
        if (mode == SchedulingMode.FIXED) return true

        val log = container.eventLog
        val estimate = container.sensingController.currentEstimate()
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val deferred = Duration.ofMillis(
            (System.currentTimeMillis() - cue.scheduledAtMillis).coerceAtLeast(0L),
        )

        val decision = AdaptiveScheduler.evaluate(gate, estimate, now, deferred)
        log.i(EventLog.TAG_CUE, "Cue ${cue.id}: ${decision.action} - ${decision.reason}", sessionId)

        return when (decision.action) {
            AdaptiveScheduler.Action.PLAY_NOW -> true

            AdaptiveScheduler.Action.DEFER -> {
                // Put it back to ARMED and re-arm the alarm for the new time,
                // so a deferral survives this process dying in between.
                container.db.cueEventDao().rearm(cue.id, decision.firedAt.toInstant().toEpochMilli())
                container.db.cueEventDao().byId(cue.id)?.let {
                    container.alarmScheduler.armCues(
                        listOf(it),
                        container.settings.preciseAlarms.first(),
                        ZoneId.systemDefault(),
                    )
                }
                false
            }

            AdaptiveScheduler.Action.SKIP -> {
                container.db.cueEventDao().recordOutcome(
                    cue.id,
                    CueState.SKIPPED,
                    CueOutcome.SKIPPED_BY_STAGE,
                    null,
                    decision.reason,
                )
                false
            }
        }
    }

    private suspend fun playTestCue(soundJson: String?, playbackJson: String?) {
        val log = container.eventLog
        try {
            if (soundJson == null || playbackJson == null) return
            val sound = ColumnJson.decodeFromString<CueSound>(soundJson)
            val playback = ColumnJson.decodeFromString<CuePlayback>(playbackJson)

            when (val resolution = container.cueAssets.resolve(sound)) {
                is CueAssetStore.Resolution.Ready ->
                    container.cuePlayer.play(resolution.pcm, playback).also {
                        log.i(EventLog.TAG_AUDIO, "Test cue -> ${it.outcome} via ${it.route.label}")
                    }

                is CueAssetStore.Resolution.Failed ->
                    log.e(EventLog.TAG_AUDIO, "Test cue could not be prepared: ${resolution.reason}")

                is CueAssetStore.Resolution.SilentByDesign -> Unit
            }
        } catch (e: Exception) {
            log.e(EventLog.TAG_AUDIO, "Test cue failed", e)
        } finally {
            stopNow()
        }
    }

    private fun stopNow() {
        CueAlarmReceiver.releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        CueAlarmReceiver.releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }
}

/**
 * A cue that arrives this far after its target has lost its purpose - the sleep
 * stage it was aimed at has moved on. Recorded as missed rather than played
 * late, so the morning report tells the truth about the night.
 */
private const val TOO_LATE_MS = 5 * 60 * 1000L
