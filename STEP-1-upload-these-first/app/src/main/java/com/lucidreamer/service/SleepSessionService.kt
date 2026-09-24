// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lucidreamer.LucidDreamerApp
import com.lucidreamer.core.EventLog
import com.lucidreamer.core.Notifications
import com.lucidreamer.data.db.entity.HeartbeatEntity
import com.lucidreamer.data.db.entity.HeartbeatSource
import com.lucidreamer.data.db.entity.SessionState
import com.lucidreamer.data.db.entity.StageSampleEntity
import com.lucidreamer.sensing.SensingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs for the duration of a night.
 *
 * ## What this service is, and is not
 *
 * It is **not** the timing mechanism. Cue timing belongs entirely to
 * AlarmManager (see [AlarmScheduler]), because a foreground service does not
 * keep the CPU awake: in deep Doze the platform ignores app wake locks and the
 * processor suspends, so anything driven by `delay()` inside a service stalls
 * on a phone left on a nightstand. If this service is killed at 3am, every
 * remaining cue still fires.
 *
 * What it *is*: the ongoing notification and its controls, and a proof-of-life
 * heartbeat. The heartbeat is what lets the app tell the user in the morning
 * that it was frozen between 01:40 and 05:22 and missed two cues - a failure
 * that is otherwise completely invisible and gets blamed on the app rather than
 * on the battery manager that caused it.
 *
 * ## Foreground service type
 *
 * `specialUse`, which requires the accompanying manifest property. Not
 * `dataSync`, whose six-hours-per-day cap would kill an eight-hour night
 * outright.
 */
class SleepSessionService : Service() {

    companion object {
        const val ACTION_START = "com.lucidreamer.action.START_SESSION"
        const val ACTION_REFRESH = "com.lucidreamer.action.REFRESH_SESSION"
        const val ACTION_DISABLE_MICROPHONE = "com.lucidreamer.action.DISABLE_MIC"

        /**
         * Whether the caller was a visible activity.
         *
         * This is not bookkeeping - it decides whether the microphone can be
         * used at all. Android refuses while-in-use permissions (microphone,
         * camera, location) to a foreground service that was itself started
         * from the background, and throws a SecurityException rather than
         * degrading. So the caller states where it came from, and sensing
         * reduces itself to motion-only when the answer is "an alarm".
         */
        const val EXTRA_FROM_FOREGROUND = "from_foreground"

        /**
         * Frequent enough to localise a freeze to a few minutes, cheap enough
         * to be irrelevant to battery. The real recovery mechanism is the
         * ten-minute watchdog alarm, which works even when this service is dead.
         */
        private const val HEARTBEAT_INTERVAL_MS = 2 * 60 * 1000L

        private const val MIN_WAKE_LOCK_MS = 60_000L

        /** Hard ceiling, so a stuck session cannot hold the CPU indefinitely. */
        private const val MAX_WAKE_LOCK_MS = 12 * 60 * 60 * 1000L
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    private val container get() = (application as LucidDreamerApp).container

    private var sensingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromForeground = intent?.getBooleanExtra(EXTRA_FROM_FOREGROUND, false) ?: false
        promoteToForeground(wantsMicrophone = fromForeground)

        when (intent?.action) {
            ACTION_REFRESH -> SessionNotifications.refresh(applicationContext)
            ACTION_DISABLE_MICROPHONE -> stopSensing("disabled by the user for tonight")
            else -> startSensingIfEnabled(fromForeground)
        }

        // START_STICKY is free insurance against a memory-pressure kill. It is
        // explicitly not relied upon: it does not apply to a force-stop or to
        // most manufacturer kill paths, and a restarted service arrives with a
        // null intent. The alarms and the persisted plan are the real guarantee.
        return START_STICKY
    }

    // -----------------------------------------------------------------------
    // Sensing
    // -----------------------------------------------------------------------

    private fun startSensingIfEnabled(fromForeground: Boolean) {
        if (sensingJob?.isActive == true) return

        sensingJob = scope.launch {
            val mode = container.settings.sensingMode.first()
            if (mode == SensingMode.OFF) return@launch

            val session = container.db.sessionDao().activeSession() ?: return@launch
            val expectedMinutes =
                ((session.wakeAtMillis - session.estimatedSleepAtMillis) / 60_000L).toInt().coerceAtLeast(60)

            // Only held while sensing is actually running, and bounded by the
            // remaining night so a crashed session cannot pin the CPU awake
            // until the battery dies. Without a battery-optimisation exemption
            // the platform ignores it in deep Doze anyway, which is recorded
            // by the controller rather than hidden.
            acquireWakeLock(session.wakeAtMillis - System.currentTimeMillis())

            try {
                container.sensingController.run(
                    mode = mode,
                    sessionStartMillis = session.actualSleepAtMillis ?: session.estimatedSleepAtMillis,
                    expectedNightMinutes = expectedMinutes,
                    allowMicrophone = fromForeground,
                ) { estimate ->
                    container.db.stageSampleDao().insert(
                        StageSampleEntity(
                            sessionId = session.id,
                            atMillis = estimate.atMillis,
                            awake = estimate.awake,
                            light = estimate.light,
                            deep = estimate.deep,
                            rem = estimate.rem,
                            confidence = estimate.confidence,
                            basis = estimate.basis,
                            breathsPerMinute = container.sensingController.state.value
                                .lastBreathing?.breathsPerMinute,
                        ),
                    )
                    SessionNotifications.refresh(applicationContext)
                }
            } finally {
                releaseWakeLock()
            }
        }
    }

    private fun stopSensing(reason: String) {
        sensingJob?.cancel()
        sensingJob = null
        releaseWakeLock()
        container.eventLog.i(EventLog.TAG_SENSING, "Sensing stopped: $reason")
        SessionNotifications.refresh(applicationContext)
    }

    private fun acquireWakeLock(remainingMillis: Long) {
        if (wakeLock?.isHeld == true) return
        val timeout = remainingMillis.coerceIn(MIN_WAKE_LOCK_MS, MAX_WAKE_LOCK_MS)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LucidDreamer:sensing").apply {
            setReferenceCounted(false)
            acquire(timeout)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /**
     * @param wantsMicrophone whether to claim the microphone service type.
     *
     * Claimed only when the service was started from a visible activity *and*
     * microphone sensing is configured. Claiming it from a background start
     * throws a SecurityException and kills the service, taking the night's
     * notification and controls with it.
     */
    private fun promoteToForeground(wantsMicrophone: Boolean) {
        scope.launch {
            val session = container.db.sessionDao().activeSession()
            val remaining = session?.let { container.db.cueEventDao().allArmed(it.id) }.orEmpty()
            val notification = SessionNotifications.build(
                this@SleepSessionService,
                session,
                remaining.minByOrNull { it.scheduledAtMillis }?.scheduledAtMillis,
                remaining.size,
            )

            val usesMic = wantsMicrophone &&
                container.settings.sensingMode.first().usesMicrophone &&
                ContextCompat.checkSelfPermission(
                    this@SleepSessionService,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED

            val type = if (usesMic) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }

            val promoted = runCatching {
                ServiceCompat.startForeground(
                    this@SleepSessionService,
                    Notifications.ID_SESSION,
                    notification,
                    type,
                )
            }

            promoted.onFailure { first ->
                container.eventLog.w(
                    EventLog.TAG_SESSION,
                    "Could not enter the foreground with the requested type (${first.message}); " +
                        "retrying without the microphone",
                )
                // Falling back keeps the session alive with its controls, minus
                // audio sensing. Losing the microphone is a degraded night;
                // losing the service is a lost one.
                runCatching {
                    ServiceCompat.startForeground(
                        this@SleepSessionService,
                        Notifications.ID_SESSION,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                }.onFailure {
                    container.eventLog.e(EventLog.TAG_SESSION, "Could not enter the foreground at all", it)
                }
            }
        }
    }

    private fun startHeartbeat() {
        scope.launch {
            while (isActive) {
                writeHeartbeat()
                // Not a timing mechanism - see the class comment. If Doze
                // suspends this loop the gap is precisely the information the
                // morning report needs.
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private suspend fun writeHeartbeat() {
        runCatching {
            val session = container.db.sessionDao().activeSession()
            if (session == null || session.state == SessionState.FINISHED) {
                stopSelf()
                return
            }
            val status = container.deviceStatus
            container.db.heartbeatDao().insert(
                HeartbeatEntity(
                    sessionId = session.id,
                    atMillis = System.currentTimeMillis(),
                    source = HeartbeatSource.SESSION_SERVICE,
                    processId = Process.myPid(),
                    serviceRunning = true,
                    wakeLockHeld = false,
                    standbyBucket = status.standbyBucket(),
                    ignoringBatteryOptimisations = status.isIgnoringBatteryOptimisations(),
                    interruptionFilter = status.interruptionFilter(),
                ),
            )
        }
    }

    override fun onDestroy() {
        container.eventLog.d(EventLog.TAG_SESSION, "Session service destroyed")
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }
}
