// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.core

import android.util.Log
import com.lucidreamer.data.db.dao.EventLogDao
import com.lucidreamer.data.db.entity.EventLogEntity
import com.lucidreamer.data.db.entity.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Persistent, on-device event log.
 *
 * Logcat is useless for this app's actual failure mode: something goes wrong at
 * 04:12 while the phone is in a drawer and the user finds out at breakfast. By
 * then logcat has rolled over, and asking a user to attach a debugger overnight
 * is not a real support plan.
 *
 * So every scheduling decision, alarm arm, cue fire, skip, drop, error and
 * recovery is written here, bounded as a ring buffer, and exportable as text.
 * For an open-source project where the maintainer cannot reproduce the user's
 * phone, this *is* the bug-report format.
 *
 * Nothing here ever leaves the device unless the user explicitly exports it.
 */
class EventLog(
    private val dao: EventLogDao,
    private val scope: CoroutineScope,
) {
    /** Bounded so the log can never grow without limit on someone's phone. */
    private val maxEntries = 4000
    private var writesSinceTrim = 0

    fun d(tag: String, message: String, sessionId: Long? = null) = write(LogLevel.DEBUG, tag, message, sessionId)
    fun i(tag: String, message: String, sessionId: Long? = null) = write(LogLevel.INFO, tag, message, sessionId)
    fun w(tag: String, message: String, sessionId: Long? = null) = write(LogLevel.WARN, tag, message, sessionId)

    fun e(tag: String, message: String, error: Throwable? = null, sessionId: Long? = null) {
        val full = if (error == null) message else "$message -- ${error.javaClass.simpleName}: ${error.message}"
        write(LogLevel.ERROR, tag, full, sessionId)
    }

    private fun write(level: LogLevel, tag: String, message: String, sessionId: Long?) {
        // Mirrored to logcat for development; the database row is the one that
        // survives to the morning.
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }

        scope.launch {
            runCatching {
                dao.insert(
                    EventLogEntity(
                        atMillis = System.currentTimeMillis(),
                        level = level,
                        tag = tag,
                        message = message,
                        sessionId = sessionId,
                    ),
                )
                // Trimming on every insert would be wasteful; every 200 is
                // plenty to keep the table near its ceiling.
                if (++writesSinceTrim >= 200) {
                    writesSinceTrim = 0
                    dao.trimTo(maxEntries)
                }
            }
            // A logging failure must never take down a cue. Swallowed on purpose.
        }
    }

    suspend fun exportAsText(zone: ZoneId = ZoneId.systemDefault()): String {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return buildString {
            appendLine("Lucid Dreamer event log")
            appendLine("Exported ${fmt.format(Instant.now().atZone(zone))} (${zone.id})")
            appendLine("=".repeat(60))
            dao.allForExport().forEach { e ->
                append(fmt.format(Instant.ofEpochMilli(e.atMillis).atZone(zone)))
                append("  ")
                append(e.level.name.padEnd(5))
                append("  ")
                append(e.tag.padEnd(18))
                append("  ")
                appendLine(e.message)
            }
        }
    }

    suspend fun clear() = dao.clear()

    companion object {
        const val TAG_SCHEDULER = "Scheduler"
        const val TAG_SESSION = "Session"
        const val TAG_CUE = "Cue"
        const val TAG_ALARM = "Alarm"
        const val TAG_WATCHDOG = "Watchdog"
        const val TAG_BOOT = "Boot"
        const val TAG_AUDIO = "Audio"
        const val TAG_WBTB = "WBTB"
        const val TAG_REMINDER = "Reminder"
        const val TAG_SENSING = "Sensing"
        const val TAG_EXPERIMENT = "Experiment"
    }
}
