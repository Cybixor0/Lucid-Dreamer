// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.domain.diagnostics

import java.time.Duration

/** A stretch during which the app produced no proof of life. */
data class Freeze(
    val fromMillis: Long,
    val toMillis: Long,
) {
    val length: Duration get() = Duration.ofMillis(toMillis - fromMillis)
}

/** What most likely went wrong, and therefore what to tell the user to do. */
enum class Culprit {
    NOTHING_WRONG,
    MANUFACTURER_BATTERY_MANAGER,
    BATTERY_OPTIMISATION,
    STANDBY_BUCKET,
    DND_SILENCED_ALARMS,
    NO_AUDIO_ROUTE,
    EXACT_ALARM_PERMISSION,
    UNKNOWN,
}

/**
 * What actually happened last night.
 *
 * This exists because the app's characteristic failure is invisible: a battery
 * manager suspends the process at 01:40, cues silently do not fire, and the
 * user wakes up believing the app is broken. Android exposes no API that says
 * "you were frozen", so the only way to find out is to reconstruct it from
 * heartbeats and from the gap between when each cue was meant to fire and when
 * it did.
 *
 * Turning that into a specific sentence - *"frozen for 3h42m, missed 2 of 4
 * cues, this is almost always One UI's 'Put unused apps to sleep', fix it
 * here"* - is the difference between a diagnosable app and a haunted one.
 *
 * Pure and free of Android types so the attribution logic can be tested
 * directly against synthetic nights.
 */
data class ReliabilityReport(
    val sessionId: Long,
    val sessionStartMillis: Long,
    val sessionEndMillis: Long,
    val cuesScheduled: Int,
    val cuesPlayed: Int,
    val cuesMissed: Int,
    val cuesSkippedByUser: Int,
    val freezes: List<Freeze>,
    val longestFreeze: Duration,
    val totalFrozen: Duration,
    /** Median delay between a cue's target time and when it actually fired. */
    val medianLatency: Duration?,
    val worstLatency: Duration?,
    val culprit: Culprit,
    val headline: String,
    val detail: String,
) {
    val wentWell: Boolean get() = culprit == Culprit.NOTHING_WRONG

    companion object {

        /**
         * A gap longer than this means the app was not running.
         *
         * Heartbeats are written every two minutes by the session service and
         * every ten by the watchdog alarm, so anything past roughly fifteen
         * means even the alarm-driven path was not being delivered - which is
         * the signature of a suspended app rather than ordinary Doze.
         */
        val FREEZE_THRESHOLD: Duration = Duration.ofMinutes(15)

        fun build(input: Input): ReliabilityReport {
            val freezes = findFreezes(input.heartbeatMillis, input.sessionStartMillis, input.sessionEndMillis)
            val longest = freezes.maxOfOrNull { it.length } ?: Duration.ZERO
            val total = freezes.fold(Duration.ZERO) { acc, f -> acc.plus(f.length) }

            val latencies = input.cueLatenciesMillis.sorted()
            val median = latencies.takeIf { it.isNotEmpty() }
                ?.let { Duration.ofMillis(it[it.size / 2]) }
            val worst = latencies.maxOrNull()?.let { Duration.ofMillis(it) }

            val culprit = attribute(input, longest)
            val headline = headlineFor(culprit, input, longest)

            return ReliabilityReport(
                sessionId = input.sessionId,
                sessionStartMillis = input.sessionStartMillis,
                sessionEndMillis = input.sessionEndMillis,
                cuesScheduled = input.cuesScheduled,
                cuesPlayed = input.cuesPlayed,
                cuesMissed = input.cuesMissed,
                cuesSkippedByUser = input.cuesSkippedByUser,
                freezes = freezes,
                longestFreeze = longest,
                totalFrozen = total,
                medianLatency = median,
                worstLatency = worst,
                culprit = culprit,
                headline = headline,
                detail = detailFor(culprit),
            )
        }

        /**
         * Ordered so the most actionable explanation wins.
         *
         * A silenced alarm stream and a missing permission are checked before
         * freezes, because they are precise causes with precise fixes, whereas
         * "the app was frozen" is a symptom that several things can produce.
         */
        private fun attribute(input: Input, longestFreeze: Duration): Culprit = when {
            input.cuesMissed == 0 && longestFreeze < FREEZE_THRESHOLD -> Culprit.NOTHING_WRONG
            input.anyCueSilencedByDnd -> Culprit.DND_SILENCED_ALARMS
            input.anyCueHadNoRoute -> Culprit.NO_AUDIO_ROUTE
            !input.canScheduleExactAlarms -> Culprit.EXACT_ALARM_PERMISSION
            longestFreeze >= FREEZE_THRESHOLD && input.isSamsung && !input.ignoringBatteryOptimisations ->
                Culprit.MANUFACTURER_BATTERY_MANAGER
            longestFreeze >= FREEZE_THRESHOLD && !input.ignoringBatteryOptimisations ->
                Culprit.BATTERY_OPTIMISATION
            input.worstStandbyBucket >= 40 -> Culprit.STANDBY_BUCKET
            longestFreeze >= FREEZE_THRESHOLD && input.isSamsung -> Culprit.MANUFACTURER_BATTERY_MANAGER
            input.cuesMissed > 0 -> Culprit.UNKNOWN
            else -> Culprit.NOTHING_WRONG
        }

        private fun headlineFor(culprit: Culprit, input: Input, longestFreeze: Duration): String {
            val missed = input.cuesMissed
            val scheduled = input.cuesScheduled
            val frozenFor = humanDuration(longestFreeze)

            return when (culprit) {
                Culprit.NOTHING_WRONG ->
                    if (scheduled == 0) {
                        "No cues were scheduled last night."
                    } else {
                        "All $scheduled cue${s(scheduled)} played on time."
                    }

                Culprit.MANUFACTURER_BATTERY_MANAGER ->
                    "The app was frozen for $frozenFor and missed $missed of $scheduled cue${s(scheduled)}. " +
                        "On Samsung phones this is almost always \"Put unused apps to sleep\"."

                Culprit.BATTERY_OPTIMISATION ->
                    "The app was frozen for $frozenFor and missed $missed of $scheduled cue${s(scheduled)}. " +
                        "Battery optimisation is still switched on for Lucid Dreamer."

                Culprit.STANDBY_BUCKET ->
                    "Android has placed the app in a restricted background state, which limits how " +
                        "often it may run. $missed of $scheduled cue${s(scheduled)} did not play."

                Culprit.DND_SILENCED_ALARMS ->
                    "Cues were played but silenced. Do Not Disturb is set to block alarms, so " +
                        "nothing could be heard."

                Culprit.NO_AUDIO_ROUTE ->
                    "Cues were skipped because the headphones they were set to require were not connected."

                Culprit.EXACT_ALARM_PERMISSION ->
                    "The app cannot schedule exact alarms, so cues may drift by up to an hour. " +
                        "$missed of $scheduled did not play on time."

                Culprit.UNKNOWN ->
                    "$missed of $scheduled cue${s(scheduled)} did not play, and the cause is not clear."
            }
        }

        private fun detailFor(culprit: Culprit): String = when (culprit) {
            Culprit.NOTHING_WRONG ->
                "Nothing to do."

            Culprit.MANUFACTURER_BATTERY_MANAGER ->
                "One UI puts apps it considers unused to sleep after about three days, and into " +
                    "\"deep sleep\" after about sixteen - deep sleep stops the app entirely until " +
                    "you open it again. Adding Lucid Dreamer to \"Never sleeping apps\" is the " +
                    "single most effective fix for overnight reliability on a Galaxy."

            Culprit.BATTERY_OPTIMISATION ->
                "With battery optimisation on, Android may suspend the app while the screen is " +
                    "off. Allowing unrestricted background use lets the session keep running."

            Culprit.STANDBY_BUCKET ->
                "Android reduces how often rarely-used apps may run. Opening the app more often, " +
                    "and allowing unrestricted background use, usually moves it back."

            Culprit.DND_SILENCED_ALARMS ->
                "Cues play on the alarm audio stream, which most Do Not Disturb settings allow " +
                    "through. Yours is set to silence everything, including alarms."

            Culprit.NO_AUDIO_ROUTE ->
                "One or more cues are set to play only when headphones are connected. You can " +
                    "change what happens when they are not: play through the speaker, play " +
                    "quietly, or skip."

            Culprit.EXACT_ALARM_PERMISSION ->
                "Without permission to schedule exact alarms, Android defers the app's alarms to " +
                    "its own maintenance windows, which can be up to an hour late."

            Culprit.UNKNOWN ->
                "The event log in developer mode has the full sequence for last night, and is the " +
                    "right thing to attach to a bug report."
        }

        /**
         * Finds stretches with no proof of life.
         *
         * The session start and end are treated as implicit heartbeats so that
         * a freeze at either end of the night is caught - a phone suspended
         * from 02:00 until the morning produces no *gap between* heartbeats at
         * all, only a long silence after the last one.
         */
        internal fun findFreezes(heartbeats: List<Long>, startMillis: Long, endMillis: Long): List<Freeze> {
            val points = (listOf(startMillis) + heartbeats.sorted() + listOf(endMillis)).distinct().sorted()
            val threshold = FREEZE_THRESHOLD.toMillis()
            return points.zipWithNext()
                .filter { (a, b) -> b - a >= threshold }
                .map { (a, b) -> Freeze(a, b) }
        }

        private fun s(n: Int) = if (n == 1) "" else "s"

        internal fun humanDuration(d: Duration): String {
            val h = d.toHours()
            val m = d.toMinutes() % 60
            return when {
                h > 0 && m > 0 -> "${h}h ${m}m"
                h > 0 -> "${h}h"
                else -> "${m}m"
            }
        }
    }

    /** Everything the report needs, gathered by the repository from the database. */
    data class Input(
        val sessionId: Long,
        val sessionStartMillis: Long,
        val sessionEndMillis: Long,
        val heartbeatMillis: List<Long>,
        val cuesScheduled: Int,
        val cuesPlayed: Int,
        val cuesMissed: Int,
        val cuesSkippedByUser: Int,
        val cueLatenciesMillis: List<Long>,
        val anyCueSilencedByDnd: Boolean,
        val anyCueHadNoRoute: Boolean,
        val ignoringBatteryOptimisations: Boolean,
        val canScheduleExactAlarms: Boolean,
        val worstStandbyBucket: Int,
        val isSamsung: Boolean,
    )
}
