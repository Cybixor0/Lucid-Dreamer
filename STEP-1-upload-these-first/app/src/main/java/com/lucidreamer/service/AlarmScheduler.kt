// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.lucidreamer.core.DeviceStatus
import com.lucidreamer.core.EventLog
import com.lucidreamer.data.db.entity.CueEventEntity
import com.lucidreamer.ui.MainActivity
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Arms the night.
 *
 * ## The rule this class exists to enforce
 *
 * **AlarmManager owns *when*. A service owns *what*. The two are never
 * coupled.** Nothing in this app decides cue timing by sleeping in a coroutine,
 * because a foreground service does *not* keep the CPU awake - in deep Doze the
 * platform explicitly ignores app wake locks and the processor suspends. A
 * `delay()` inside a service silently stalls on a phone sitting on a nightstand
 * and then fires a burst of late cues at the next wake-up.
 *
 * ## Why `setAlarmClock` for ordinary cues
 *
 * `setExactAndAllowWhileIdle` fires in Doze but is throttled to roughly seven
 * per hour, which cannot carry a night of closely-spaced cues.
 * `setAlarmClock` is not throttled at all and is documented to make the system
 * leave low-power mode to deliver on time. That is why it is used for *every*
 * cue, not only the WBTB wake-up.
 *
 * The cost is visibility, not reliability: `setAlarmClock` populates the
 * system's "next alarm" slot, so the lock screen shows the next cue rather than
 * the user's morning alarm. That is a real trade-off, so it is a user setting
 * ([precise]) with an honest explanation rather than a silent decision.
 *
 * ## Why every cue is armed up front
 *
 * Cues are never chained - "fire cue N, then schedule N+1" loses the entire
 * rest of the night to one process death at 3am. All of them are armed
 * immediately and re-armed idempotently on every recovery event, using stable
 * request codes so re-arming updates rather than duplicates.
 */
class AlarmScheduler(
    private val context: Context,
    private val log: EventLog,
    private val deviceStatus: DeviceStatus,
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Small, fixed request codes. Cue codes start above 0x01000000; see CuePlanner. */
    object RequestCodes {
        const val WATCHDOG = 1
        const val WBTB_WAKE = 2
        const val WBTB_END = 3
        const val SESSION_AUTO_START = 4
        const val TEST_CUE = 5
        const val REMINDER_BASE = 100_000
    }

    /**
     * Interval between watchdog heartbeats.
     *
     * Deliberately under the ~7-per-hour Doze cap on while-idle alarms, leaving
     * headroom so the watchdog can never crowd out a real cue.
     */
    val watchdogInterval: Duration = Duration.ofMinutes(10)

    // -----------------------------------------------------------------------
    // Cues
    // -----------------------------------------------------------------------

    /**
     * Arms every unfired cue for a session.
     *
     * Idempotent: safe to call on boot, on a timezone change, after an app
     * update and from the watchdog, as often as needed.
     */
    fun armCues(cues: List<CueEventEntity>, precise: Boolean, zone: ZoneId): ArmResult {
        var armed = 0
        var failed = 0
        val now = System.currentTimeMillis()

        for (cue in cues) {
            if (cue.scheduledAtMillis <= now) continue
            val ok = armOne(cue, precise)
            if (ok) armed++ else failed++
        }

        val next = cues.filter { it.scheduledAtMillis > now }.minByOrNull { it.scheduledAtMillis }
        log.i(
            EventLog.TAG_ALARM,
            "Armed $armed cue(s)" +
                (if (failed > 0) ", $failed failed" else "") +
                (next?.let { ", next at ${format(it.scheduledAtMillis, zone)}" } ?: ", none upcoming") +
                ", mode=${if (precise) "precise" else "low-profile"}",
            sessionId = cues.firstOrNull()?.sessionId,
        )
        return ArmResult(armed, failed, next?.scheduledAtMillis)
    }

    private fun armOne(cue: CueEventEntity, precise: Boolean): Boolean {
        val intent = Intent(context, CueAlarmReceiver::class.java).apply {
            action = CueAlarmReceiver.ACTION_FIRE_CUE
            putExtra(CueAlarmReceiver.EXTRA_CUE_ID, cue.id)
            putExtra(CueAlarmReceiver.EXTRA_SESSION_ID, cue.sessionId)
            // Alarms are matched by (action, data, type, class, categories) -
            // NOT by extras. Without distinct data, every cue would collapse
            // onto one alarm and only the last one armed would survive.
            data = android.net.Uri.parse("lucid://cue/${cue.id}")
        }
        val pending = PendingIntent.getBroadcast(
            context,
            cue.alarmRequestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return schedule(cue.scheduledAtMillis, pending, precise, "cue ${cue.id}")
    }

    fun cancelCue(cue: CueEventEntity) {
        val intent = Intent(context, CueAlarmReceiver::class.java).apply {
            action = CueAlarmReceiver.ACTION_FIRE_CUE
            data = android.net.Uri.parse("lucid://cue/${cue.id}")
        }
        PendingIntent.getBroadcast(
            context,
            cue.alarmRequestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    fun cancelAllCues(cues: List<CueEventEntity>) {
        cues.forEach { cancelCue(it) }
        log.i(EventLog.TAG_ALARM, "Cancelled ${cues.size} cue alarm(s)")
    }

    // -----------------------------------------------------------------------
    // WBTB
    // -----------------------------------------------------------------------

    /**
     * The Wake Back To Bed alarm. Always precise - this one is explicitly meant
     * to wake the user, so the lock-screen alarm indicator is correct and
     * wanted.
     */
    fun armWbtbWake(atMillis: Long, sessionId: Long, zone: ZoneId): Boolean {
        val pending = broadcast(
            RequestCodes.WBTB_WAKE,
            WbtbReceiver.ACTION_WBTB_WAKE,
            "lucid://wbtb/wake",
            WbtbReceiver::class.java,
        ) { putExtra(WbtbReceiver.EXTRA_SESSION_ID, sessionId) }

        val ok = schedule(atMillis, pending, precise = true, what = "WBTB wake")
        log.i(EventLog.TAG_WBTB, "WBTB wake armed for ${format(atMillis, zone)}", sessionId)
        return ok
    }

    fun armWbtbEnd(atMillis: Long, sessionId: Long) {
        val pending = broadcast(
            RequestCodes.WBTB_END,
            WbtbReceiver.ACTION_WBTB_END,
            "lucid://wbtb/end",
            WbtbReceiver::class.java,
        ) { putExtra(WbtbReceiver.EXTRA_SESSION_ID, sessionId) }
        schedule(atMillis, pending, precise = true, what = "WBTB end")
    }

    fun cancelWbtb() {
        cancelBroadcast(RequestCodes.WBTB_WAKE, WbtbReceiver.ACTION_WBTB_WAKE, "lucid://wbtb/wake", WbtbReceiver::class.java)
        cancelBroadcast(RequestCodes.WBTB_END, WbtbReceiver.ACTION_WBTB_END, "lucid://wbtb/end", WbtbReceiver::class.java)
    }

    // -----------------------------------------------------------------------
    // Watchdog
    // -----------------------------------------------------------------------

    /**
     * Schedules the next heartbeat.
     *
     * Uses `setExactAndAllowWhileIdle` rather than `setAlarmClock`: the
     * watchdog must not appear on the lock screen as a pending alarm, and at
     * one every ten minutes it sits comfortably inside the Doze quota.
     */
    fun armWatchdog(atMillis: Long) {
        val pending = broadcast(
            RequestCodes.WATCHDOG,
            WatchdogReceiver.ACTION_HEARTBEAT,
            "lucid://watchdog",
            WatchdogReceiver::class.java,
        )
        runCatching {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }.onFailure {
            // Falls back to an inexact window rather than giving up: a late
            // heartbeat is still worth having.
            runCatching { alarmManager.set(AlarmManager.RTC_WAKEUP, atMillis, pending) }
        }
    }

    fun armNextWatchdog() = armWatchdog(System.currentTimeMillis() + watchdogInterval.toMillis())

    fun cancelWatchdog() =
        cancelBroadcast(RequestCodes.WATCHDOG, WatchdogReceiver.ACTION_HEARTBEAT, "lucid://watchdog", WatchdogReceiver::class.java)

    // -----------------------------------------------------------------------
    // Reliability test
    // -----------------------------------------------------------------------

    /**
     * Arms a real cue a short way out, for the "test it with the screen off" check.
     *
     * Goes through the ordinary alarm path on purpose. Playing a sound
     * immediately would prove only that the speaker works; what actually needs
     * testing is whether this particular phone delivers a scheduled alarm to
     * this app while it is locked and idle.
     */
    fun armTestCue(atMillis: Long) {
        val pending = broadcast(
            RequestCodes.TEST_CUE,
            TestCueReceiver.ACTION_TEST,
            "lucid://test/cue",
            TestCueReceiver::class.java,
        )
        schedule(atMillis, pending, precise = true, what = "test cue")
        log.i(EventLog.TAG_ALARM, "Test cue armed for ${format(atMillis, ZoneId.systemDefault())}")
    }

    // -----------------------------------------------------------------------
    // Session auto-start
    // -----------------------------------------------------------------------

    /** Arms the automatic session start at bedtime, when the user has not chosen manual start. */
    fun armSessionAutoStart(atMillis: Long, zone: ZoneId) {
        val pending = broadcast(
            RequestCodes.SESSION_AUTO_START,
            SessionAutoStartReceiver.ACTION_AUTO_START,
            "lucid://session/autostart",
            SessionAutoStartReceiver::class.java,
        )
        schedule(atMillis, pending, precise = true, what = "session auto-start")
        log.i(EventLog.TAG_SESSION, "Auto-start armed for ${format(atMillis, zone)}")
    }

    fun cancelSessionAutoStart() = cancelBroadcast(
        RequestCodes.SESSION_AUTO_START,
        SessionAutoStartReceiver.ACTION_AUTO_START,
        "lucid://session/autostart",
        SessionAutoStartReceiver::class.java,
    )

    // -----------------------------------------------------------------------
    // Shared plumbing
    // -----------------------------------------------------------------------

    /**
     * The tiering that makes cues land on time, degrading loudly rather than
     * silently when a permission is missing.
     */
    private fun schedule(
        atMillis: Long,
        pending: PendingIntent,
        precise: Boolean,
        what: String,
    ): Boolean {
        val canExact = deviceStatus.canScheduleExactAlarms()

        return when {
            precise && canExact -> runCatching {
                val show = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, show), pending)
            }.onFailure {
                log.w(EventLog.TAG_ALARM, "setAlarmClock refused for $what (${it.message}); falling back")
            }.isSuccess || fallbackWhileIdle(atMillis, pending, what)

            canExact -> runCatching {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            }.onFailure {
                log.w(EventLog.TAG_ALARM, "setExactAndAllowWhileIdle refused for $what (${it.message})")
            }.isSuccess || fallbackInexact(atMillis, pending, what)

            else -> {
                // No exact-alarm permission at all: the user is told plainly
                // that cues may drift, rather than discovering it themselves.
                log.w(
                    EventLog.TAG_ALARM,
                    "No exact alarm permission - $what scheduled inexactly and may be up to an hour late",
                )
                fallbackInexact(atMillis, pending, what)
            }
        }
    }

    private fun fallbackWhileIdle(atMillis: Long, pending: PendingIntent, what: String): Boolean =
        runCatching {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }.isSuccess || fallbackInexact(atMillis, pending, what)

    private fun fallbackInexact(atMillis: Long, pending: PendingIntent, what: String): Boolean =
        runCatching {
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                atMillis,
                Duration.ofMinutes(10).toMillis(),
                pending,
            )
        }.onFailure { log.e(EventLog.TAG_ALARM, "Could not schedule $what at all", it) }.isSuccess

    private fun broadcast(
        requestCode: Int,
        action: String,
        uri: String,
        cls: Class<*>,
        extras: Intent.() -> Unit = {},
    ): PendingIntent {
        val intent = Intent(context, cls).apply {
            this.action = action
            data = android.net.Uri.parse(uri)
            extras()
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelBroadcast(requestCode: Int, action: String, uri: String, cls: Class<*>) {
        val intent = Intent(context, cls).apply {
            this.action = action
            data = android.net.Uri.parse(uri)
        }
        PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    /** What the system thinks the next alarm is - shown in developer mode. */
    fun systemNextAlarmMillis(): Long? =
        runCatching { alarmManager.nextAlarmClock?.triggerTime }.getOrNull()

    private fun format(millis: Long, zone: ZoneId): String =
        timeFormat.format(Instant.ofEpochMilli(millis).atZone(zone))

    data class ArmResult(val armed: Int, val failed: Int, val nextAtMillis: Long?)
}
