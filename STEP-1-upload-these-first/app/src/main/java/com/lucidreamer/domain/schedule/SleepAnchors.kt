// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.domain.schedule

import com.lucidreamer.core.serialization.DurationSerializer
import com.lucidreamer.core.serialization.LocalTimeSerializer
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Which of {wake time, sleep duration} the user considers authoritative.
 *
 * Any two of (sleep onset, wake time, duration) determine the third, so the app
 * has to remember which one the user actually set. Without this, editing your
 * bedtime would silently rewrite either your wake time or your sleep duration,
 * and you would have no way to say which you meant.
 */
@Serializable
enum class ScheduleBasis {
    /** "I wake up at 07:00." Duration is derived. */
    WAKE_TIME,

    /** "I sleep about 7h30m." Wake time is derived. */
    DURATION,
}

/**
 * A description of a typical night, in wall-clock terms.
 *
 * The three values are deliberately independent. Getting into bed is not the
 * same as falling asleep, and conflating them is the single most common way
 * sleep apps get cue timing wrong - a cue placed "5 hours after bedtime" lands
 * 25 minutes earlier in the night than intended if it actually takes you 25
 * minutes to fall asleep.
 *
 * Nothing here is an instant. These are recurring wall-clock rules, resolved
 * against a specific date and zone by [resolveFor]. Storing rules rather than
 * timestamps is what makes daylight-saving transitions and timezone changes
 * correct rather than approximately correct.
 */
@Serializable
data class SleepAnchors(
    /** "I get into bed at..." */
    @Serializable(with = LocalTimeSerializer::class)
    val bedTime: LocalTime = LocalTime.of(23, 0),

    /** "I usually fall asleep after..." - sleep onset latency. */
    @Serializable(with = DurationSerializer::class)
    val onsetLatency: Duration = Duration.ofMinutes(20),

    val basis: ScheduleBasis = ScheduleBasis.WAKE_TIME,

    /** Authoritative when [basis] is [ScheduleBasis.WAKE_TIME]. */
    @Serializable(with = LocalTimeSerializer::class)
    val wakeTime: LocalTime = LocalTime.of(7, 0),

    /** Authoritative when [basis] is [ScheduleBasis.DURATION]. */
    @Serializable(with = DurationSerializer::class)
    val sleepDuration: Duration = Duration.ofHours(7).plusMinutes(40),
) {
    /**
     * Resolves this recurring rule into concrete instants for one night.
     *
     * @param night the calendar date on which the user gets into bed. Using
     *   "date you get into bed" rather than "date you wake up" is what lets the
     *   same code serve a 23:00 sleeper, a 02:00 sleeper and a day sleeper who
     *   goes to bed at 09:00, with no special cases.
     */
    fun resolveFor(night: LocalDate, zone: ZoneId): ResolvedNight {
        // ZonedDateTime.of moves forward out of a DST spring-forward gap rather
        // than throwing, which is the behaviour we want for a bedtime.
        val bedAt = ZonedDateTime.of(night, bedTime, zone)

        // Onset latency is real elapsed time, so this is deliberately an
        // absolute-time addition: 20 minutes of lying awake is 20 minutes even
        // if the clocks change while you are doing it.
        val sleepAt = bedAt.plus(onsetLatency)

        val wakeAt = when (basis) {
            ScheduleBasis.DURATION -> sleepAt.plus(sleepDuration)
            ScheduleBasis.WAKE_TIME -> nextOccurrenceAfter(wakeTime, sleepAt, zone)
        }

        return ResolvedNight(
            night = night,
            zone = zone,
            bedAt = bedAt,
            estimatedSleepAt = sleepAt,
            wakeAt = wakeAt,
            actualSleepAt = null,
        )
    }

    /** Sleep duration implied by these anchors, whichever way round they were entered. */
    fun impliedDuration(): Duration = when (basis) {
        ScheduleBasis.DURATION -> sleepDuration
        ScheduleBasis.WAKE_TIME -> {
            val sleepAt = bedTime.plus(onsetLatency)
            var minutes = java.time.temporal.ChronoUnit.MINUTES.between(sleepAt, wakeTime)
            if (minutes <= 0) minutes += Duration.ofDays(1).toMinutes()
            Duration.ofMinutes(minutes)
        }
    }
}

/**
 * Finds the first occurrence of [time] strictly after [after].
 *
 * Handles midnight wrap without any "is this an evening or a morning time?"
 * heuristic, which is what makes irregular and daytime sleep schedules work.
 */
internal fun nextOccurrenceAfter(
    time: LocalTime,
    after: ZonedDateTime,
    zone: ZoneId,
): ZonedDateTime {
    var candidate = ZonedDateTime.of(after.toLocalDate(), time, zone)
    // plusDays keeps the wall-clock time across a DST boundary, which is what a
    // user means by "07:00" - not "24 hours later".
    var guard = 0
    while (!candidate.isAfter(after) && guard < 3) {
        candidate = candidate.plusDays(1)
        guard++
    }
    return candidate
}

/**
 * One night's anchors resolved to real instants.
 *
 * [estimatedSleepAt] is always an estimate derived from the user's stated onset
 * latency. [actualSleepAt] is only set when the user actually told the app they
 * were going to sleep. The distinction is surfaced everywhere in the UI rather
 * than quietly averaged away - see [isOnsetEstimated].
 */
data class ResolvedNight(
    val night: LocalDate,
    val zone: ZoneId,
    val bedAt: ZonedDateTime,
    val estimatedSleepAt: ZonedDateTime,
    val wakeAt: ZonedDateTime,
    val actualSleepAt: ZonedDateTime?,
) {
    /** The onset the scheduler should anchor to: measured if we have it, estimated otherwise. */
    val sleepAt: ZonedDateTime get() = actualSleepAt ?: estimatedSleepAt

    /** True when [sleepAt] is inferred rather than reported by the user. */
    val isOnsetEstimated: Boolean get() = actualSleepAt == null

    /** Time actually available for cueing, from onset to wake. */
    val sleepDuration: Duration get() = Duration.between(sleepAt, wakeAt)

    /** Re-anchors this night to a real onset the user reported. */
    fun withActualOnset(onset: ZonedDateTime, basis: ScheduleBasis, duration: Duration): ResolvedNight =
        copy(
            actualSleepAt = onset,
            // If the user's schedule is duration-based, a late night shifts the
            // whole morning; if it is wake-time based, the alarm stays put.
            wakeAt = if (basis == ScheduleBasis.DURATION) onset.plus(duration) else wakeAt,
        )
}
