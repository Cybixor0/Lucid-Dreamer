// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import com.lucidreamer.LucidDreamerApp
import com.lucidreamer.core.EventLog
import com.lucidreamer.data.db.entity.HeartbeatEntity
import com.lucidreamer.data.db.entity.HeartbeatSource
import com.lucidreamer.data.db.entity.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Runs suspending work from a receiver without blocking `onReceive`.
 *
 * `goAsync` tells the system to keep the process alive until [PendingResult.finish]
 * is called. Without it the work would be racing process death, which on the
 * paths in this file means losing the night.
 */
private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
        try {
            block()
        } finally {
            runCatching { pending.finish() }
        }
    }
}

private val Context.container get() = (applicationContext as LucidDreamerApp).container

/**
 * The repair loop.
 *
 * Fires every ten minutes - deliberately inside the roughly seven-per-hour Doze
 * quota for while-idle alarms, leaving headroom so it can never crowd out a
 * real cue. Each firing writes a heartbeat, re-arms anything that was dropped,
 * and schedules the next one.
 *
 * This works even when [SleepSessionService] has been killed, which is the
 * entire point: on some devices the service will be killed, and the night has
 * to carry on regardless.
 */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_HEARTBEAT = "com.lucidreamer.action.WATCHDOG"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_HEARTBEAT) return
        val container = context.container

        runAsync {
            val session = container.db.sessionDao().activeSession()
            if (session == null) {
                container.alarmScheduler.cancelWatchdog()
                return@runAsync
            }

            val status = container.deviceStatus
            runCatching {
                container.db.heartbeatDao().insert(
                    HeartbeatEntity(
                        sessionId = session.id,
                        atMillis = System.currentTimeMillis(),
                        source = HeartbeatSource.WATCHDOG_ALARM,
                        processId = Process.myPid(),
                        serviceRunning = false,
                        wakeLockHeld = false,
                        standbyBucket = status.standbyBucket(),
                        ignoringBatteryOptimisations = status.isIgnoringBatteryOptimisations(),
                        interruptionFilter = status.interruptionFilter(),
                    ),
                )
            }

            if (session.state != SessionState.PAUSED) {
                container.sessionManager.replan("watchdog")
                // Restarting the service is harmless if it is already running,
                // and restores the notification and controls if it was killed.
                runCatching {
                    androidx.core.content.ContextCompat.startForegroundService(
                        context,
                        Intent(context, SleepSessionService::class.java).apply {
                            action = SleepSessionService.ACTION_START
                        },
                    )
                }
            }

            container.alarmScheduler.armNextWatchdog()
        }
    }
}

/**
 * Re-arms everything after the system has invalidated the alarm table.
 *
 * All four of these events cancel or invalidate pending alarms, and all four
 * are documented exemptions from the background foreground-service start
 * restriction, so the app is allowed to put itself back together here.
 *
 * Handling clock and timezone changes is precisely why cue rules are stored as
 * wall-clock rules rather than as resolved timestamps: after a change the rules
 * still mean what the user intended and the instants can simply be recomputed.
 */
class BootAndTimeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Named so it cannot shadow Intent.action inside the apply blocks below.
        val trigger = intent.action ?: return
        val container = context.container

        runAsync {
            container.eventLog.i(EventLog.TAG_BOOT, "Re-arming after $trigger")

            runCatching {
                container.db.heartbeatDao().insert(
                    HeartbeatEntity(
                        sessionId = container.db.sessionDao().activeSession()?.id,
                        atMillis = System.currentTimeMillis(),
                        source = HeartbeatSource.BOOT,
                        processId = Process.myPid(),
                        serviceRunning = false,
                        wakeLockHeld = false,
                        standbyBucket = container.deviceStatus.standbyBucket(),
                        ignoringBatteryOptimisations = container.deviceStatus.isIgnoringBatteryOptimisations(),
                        interruptionFilter = container.deviceStatus.interruptionFilter(),
                    ),
                )
            }

            container.sessionManager.replan(trigger)
            container.reminderScheduler.rescheduleAll("after $trigger")

            val session = container.db.sessionDao().activeSession()
            if (session != null && session.state != SessionState.PAUSED) {
                runCatching {
                    androidx.core.content.ContextCompat.startForegroundService(
                        context,
                        Intent(context, SleepSessionService::class.java).apply {
                            action = SleepSessionService.ACTION_START
                        },
                    )
                }
            }
        }
    }
}

/** Notification and lock-screen session controls. */
class SessionControlReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PAUSE = "com.lucidreamer.action.PAUSE"
        const val ACTION_RESUME = "com.lucidreamer.action.RESUME"
        const val ACTION_STOP = "com.lucidreamer.action.STOP"
        const val ACTION_SKIP_NEXT = "com.lucidreamer.action.SKIP_NEXT"
        const val ACTION_REPLAY_LAST = "com.lucidreamer.action.REPLAY_LAST"
        const val ACTION_BACK_TO_BED = "com.lucidreamer.action.BACK_TO_BED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val container = context.container
        val manager = container.sessionManager

        runAsync {
            when (intent.action) {
                ACTION_PAUSE -> manager.pause()
                ACTION_RESUME -> manager.resume()
                ACTION_STOP -> manager.stop()
                ACTION_SKIP_NEXT -> manager.skipNextCue()
                ACTION_REPLAY_LAST -> manager.replayLastCue()
                ACTION_BACK_TO_BED -> manager.wbtbBackToBed()
                else -> return@runAsync
            }
            SessionNotifications.refresh(context)
        }
    }
}

/** Starts the session automatically at bedtime, for users who have not chosen manual start. */
class SessionAutoStartReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_AUTO_START = "com.lucidreamer.action.AUTO_START"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_AUTO_START) return
        val container = context.container

        runAsync {
            container.eventLog.i(EventLog.TAG_SESSION, "Auto-starting tonight's session")
            container.sessionManager.startTonight(reportedOnsetNow = false)
        }
    }
}
