// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.lucidreamer.LucidDreamerApp
import com.lucidreamer.core.EventLog

/**
 * Receives a cue alarm and hands off to the playback service.
 *
 * This is the most failure-prone thirty lines in the app, for two reasons.
 *
 * **1. The wake-lock gap.** AlarmManager holds a CPU wake lock only for the
 * duration of `onReceive`. The moment this method returns, the device is free
 * to suspend again - potentially before the service has started, let alone
 * played anything. So a wake lock is taken here, *before* returning, and
 * released by the service once playback is finished.
 *
 * **2. No audio here, ever.** Recent Android blocks background audio unless the
 * app has a visible activity or a foreground service, and it fails *silently* -
 * no exception, no sound. Playing directly from a receiver would work in
 * testing and fail in the field. All audio goes through
 * [CuePlaybackService], which is a `mediaPlayback` foreground service and can
 * legitimately be started from the background by an exact alarm.
 */
class CueAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE_CUE = "com.lucidreamer.action.FIRE_CUE"
        const val EXTRA_CUE_ID = "cue_id"
        const val EXTRA_SESSION_ID = "session_id"

        private const val WAKE_LOCK_TAG = "LucidDreamer:cue"

        /**
         * Generous enough to cover the handoff plus a repeated cue with gaps,
         * but bounded so a crashed session can never pin the CPU awake for the
         * rest of the night.
         */
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 1000L

        private var heldLock: PowerManager.WakeLock? = null

        /**
         * Released by [CuePlaybackService] when playback finishes.
         *
         * Held statically because the lock must outlive `onReceive` and span a
         * process boundary within the app. If the process dies in between, the
         * timeout is the backstop.
         */
        @Synchronized
        fun releaseWakeLock() {
            runCatching { heldLock?.takeIf { it.isHeld }?.release() }
            heldLock = null
        }

        @Synchronized
        private fun acquireWakeLock(context: Context) {
            if (heldLock?.isHeld == true) return
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            heldLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE_CUE) return

        val cueId = intent.getLongExtra(EXTRA_CUE_ID, -1L)
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (cueId < 0) return

        val app = context.applicationContext as? LucidDreamerApp
        val log = app?.container?.eventLog

        // Taken before anything else: everything after this point is on
        // borrowed time otherwise.
        acquireWakeLock(context)
        log?.i(EventLog.TAG_CUE, "Alarm fired for cue $cueId", sessionId.takeIf { it >= 0 })

        val serviceIntent = Intent(context, CuePlaybackService::class.java).apply {
            action = CuePlaybackService.ACTION_PLAY_CUE
            putExtra(EXTRA_CUE_ID, cueId)
            putExtra(EXTRA_SESSION_ID, sessionId)
        }

        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            // Should not happen - an exact alarm is a documented exemption from
            // the background foreground-service start restriction - but if it
            // does, the failure is recorded rather than lost, and the lock is
            // released instead of leaking.
            log?.e(EventLog.TAG_CUE, "Could not start playback service for cue $cueId", e, sessionId.takeIf { it >= 0 })
            releaseWakeLock()
        }
    }
}
