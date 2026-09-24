// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.domain.wbtb

import com.lucidreamer.core.serialization.DurationSerializer
import com.lucidreamer.core.serialization.LocalTimeSerializer
import com.lucidreamer.domain.cue.CueSound
import com.lucidreamer.domain.schedule.ResolvedNight
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

/** When the WBTB wake-up happens. */
@Serializable
sealed interface WbtbTiming {

    fun resolve(night: ResolvedNight): ZonedDateTime

    /** "Wake me 5h30m after I fall asleep." */
    @Serializable
    @SerialName("after_onset")
    data class AfterSleepOnset(
        @Serializable(with = DurationSerializer::class) val offset: Duration,
    ) : WbtbTiming {
        override fun resolve(night: ResolvedNight): ZonedDateTime = night.sleepAt.plus(offset)
    }

    /** "Wake me at 04:30." */
    @Serializable
    @SerialName("at")
    data class AbsoluteTime(
        @Serializable(with = LocalTimeSerializer::class) val at: LocalTime,
    ) : WbtbTiming {
        override fun resolve(night: ResolvedNight): ZonedDateTime =
            com.lucidreamer.domain.schedule.nextOccurrenceAfter(at, night.sleepAt, night.zone)
    }

    /** "Wake me 90 minutes before my alarm." */
    @Serializable
    @SerialName("before_wake")
    data class BeforeWake(
        @Serializable(with = DurationSerializer::class) val offset: Duration,
    ) : WbtbTiming {
        override fun resolve(night: ResolvedNight): ZonedDateTime = night.wakeAt.minus(offset)
    }
}

/** How the awake period ends and the cues resume. */
@Serializable
enum class WbtbReturnMode {
    /**
     * Cues resume a fixed time after the wake-up, whether or not the user has
     * confirmed anything. Reliable, but assumes they went back to bed on time.
     */
    AUTOMATIC,

    /**
     * Cues are anchored to the moment the user taps "back to bed".
     *
     * More accurate, since the whole point of WBTB is to re-enter sleep at a
     * known moment - but if they fall asleep without tapping, the anchor never
     * arrives, so [fallbackAfter] eventually starts them anyway.
     */
    CONFIRMED,
}

/**
 * Wake Back To Bed.
 *
 * Off by default, and every individual part is optional. The technique works
 * well for some people and not at all for others, so nothing here is assumed:
 * a user can have the wake-up with no spoken instructions, or instructions with
 * no journal prompt, or any other combination.
 */
@Serializable
data class WbtbConfig(
    val timing: WbtbTiming = WbtbTiming.AfterSleepOnset(Duration.ofHours(5).plusMinutes(30)),

    /** How long to stay up. Used for the automatic return and for the countdown. */
    @Serializable(with = DurationSerializer::class)
    val awakeDuration: Duration = Duration.ofMinutes(15),

    val returnMode: WbtbReturnMode = WbtbReturnMode.CONFIRMED,

    /** Backstop for [WbtbReturnMode.CONFIRMED] when the user falls asleep without confirming. */
    @Serializable(with = DurationSerializer::class)
    val fallbackAfter: Duration = Duration.ofMinutes(40),

    /**
     * The wake-up sound. Separate from cue sounds, and deliberately allowed to
     * be much more noticeable - this one is *supposed* to wake you.
     */
    val wakeSound: CueSound = CueSound.BuiltIn(com.lucidreamer.domain.cue.BuiltInTone.CHIME),
    val wakeVolume: Float = 0.8f,
    val wakeRepeatCount: Int = 3,

    /** Optional spoken prompt played on waking, e.g. a MILD intention. */
    val spokenInstructions: String = "",
    val speakInstructions: Boolean = false,

    /** Prompt to write down any dream during the awake window. */
    val journalPrompt: Boolean = true,
) {
    fun wakeAt(night: ResolvedNight): ZonedDateTime = timing.resolve(night)

    /** When cues should resume if the user never confirms going back to bed. */
    fun automaticReturnAt(night: ResolvedNight): ZonedDateTime =
        wakeAt(night).plus(if (returnMode == WbtbReturnMode.AUTOMATIC) awakeDuration else fallbackAfter)
}
