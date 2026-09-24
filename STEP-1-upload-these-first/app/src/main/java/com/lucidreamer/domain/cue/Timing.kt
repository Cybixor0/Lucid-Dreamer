// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.domain.cue

import com.lucidreamer.core.serialization.DurationSerializer
import com.lucidreamer.core.serialization.LocalTimeSerializer
import com.lucidreamer.domain.schedule.ResolvedNight
import com.lucidreamer.domain.schedule.nextOccurrenceAfter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * A span of the night that cues may be placed in.
 *
 * Both absolute ("between 04:30 and 06:30") and relative ("from 4h after I fall
 * asleep until I wake up") forms exist because people genuinely think in both,
 * and forcing one onto the other loses information: a relative window follows a
 * late night, an absolute one does not.
 */
@Serializable
sealed interface TimeWindow {

    fun resolve(night: ResolvedNight): ClosedRange<ZonedDateTime>

    /** "Between 04:30 and 06:30", wall-clock. Follows the clock, not your sleep. */
    @Serializable
    @SerialName("absolute")
    data class Absolute(
        @Serializable(with = LocalTimeSerializer::class) val from: LocalTime,
        @Serializable(with = LocalTimeSerializer::class) val to: LocalTime,
    ) : TimeWindow {
        override fun resolve(night: ResolvedNight): ClosedRange<ZonedDateTime> {
            val start = nextOccurrenceAfter(from, night.sleepAt.minusNanos(1), night.zone)
            val end = nextOccurrenceAfter(to, start, night.zone)
            return start..end
        }
    }

    /** "From 4h to 7h after I fall asleep." Follows your sleep, not the clock. */
    @Serializable
    @SerialName("relative")
    data class RelativeToOnset(
        @Serializable(with = DurationSerializer::class) val from: Duration,
        @Serializable(with = DurationSerializer::class) val to: Duration,
    ) : TimeWindow {
        override fun resolve(night: ResolvedNight): ClosedRange<ZonedDateTime> =
            night.sleepAt.plus(from)..night.sleepAt.plus(to)
    }

    /** "From 5h after onset until I wake up", optionally stopping short of the alarm. */
    @Serializable
    @SerialName("until_wake")
    data class UntilWake(
        @Serializable(with = DurationSerializer::class) val from: Duration,
        @Serializable(with = DurationSerializer::class) val stopBefore: Duration = Duration.ZERO,
    ) : TimeWindow {
        override fun resolve(night: ResolvedNight): ClosedRange<ZonedDateTime> {
            val start = night.sleepAt.plus(from)
            val end = night.wakeAt.minus(stopBefore)
            return if (end.isBefore(start)) start..start else start..end
        }
    }
}

/**
 * When a cue rule wants to fire.
 *
 * Eight variants rather than one generic "offset" because each expresses a
 * genuinely different intent that survives a change elsewhere in the schedule.
 * "45 minutes before I wake up" and "at 06:15" are the same instant tonight and
 * different instants the moment the user lies in.
 */
@Serializable
sealed interface Timing {

    /** "At 03:30." */
    @Serializable
    @SerialName("at")
    data class AbsoluteTime(
        @Serializable(with = LocalTimeSerializer::class) val at: LocalTime,
    ) : Timing

    /** "4h30m after I fall asleep." The most common lucid-dreaming formulation. */
    @Serializable
    @SerialName("after_onset")
    data class AfterSleepOnset(
        @Serializable(with = DurationSerializer::class) val offset: Duration,
    ) : Timing

    /** "5h after I get into bed." Ignores onset latency, for people who prefer it. */
    @Serializable
    @SerialName("after_bedtime")
    data class AfterBedtime(
        @Serializable(with = DurationSerializer::class) val offset: Duration,
    ) : Timing

    /** "45 minutes before my alarm." */
    @Serializable
    @SerialName("before_wake")
    data class BeforeWake(
        @Serializable(with = DurationSerializer::class) val offset: Duration,
    ) : Timing

    /** "75% of the way through the night", which tracks short and long nights alike. */
    @Serializable
    @SerialName("fraction")
    data class FractionOfNight(val fraction: Float) : Timing

    /** "Every 30 minutes between 04:30 and 06:30, give or take 4 minutes." */
    @Serializable
    @SerialName("repeating")
    data class Repeating(
        val window: TimeWindow,
        @Serializable(with = DurationSerializer::class) val interval: Duration,
        @Serializable(with = DurationSerializer::class) val jitter: Duration = Duration.ZERO,
        val maxCount: Int = 12,
    ) : Timing

    /** "5 cues at random times between 04:30 and 06:30, at least 15 minutes apart." */
    @Serializable
    @SerialName("random")
    data class RandomInWindow(
        val window: TimeWindow,
        val count: Int,
        @Serializable(with = DurationSerializer::class) val minGap: Duration = Duration.ofMinutes(10),
    ) : Timing

    /** "20 minutes after I go back to bed following WBTB." */
    @Serializable
    @SerialName("after_wbtb")
    data class AfterWbtbReturn(
        @Serializable(with = DurationSerializer::class) val offset: Duration,
    ) : Timing
}
