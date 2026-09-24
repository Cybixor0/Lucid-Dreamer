// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.domain.cue

import com.lucidreamer.domain.schedule.ResolvedNight
import java.time.Duration
import java.time.ZonedDateTime

/**
 * A cue that is going to be armed tonight.
 *
 * [alarmRequestCode] is derived deterministically from the night and the rule,
 * not allocated from a counter. That matters: re-arming has to be idempotent,
 * because boot, timezone changes, app updates and the watchdog all re-run the
 * planner, and a counter would produce a fresh PendingIntent each time and
 * leave stale alarms behind.
 */
data class PlannedCue(
    val ruleId: Long,
    val indexWithinRule: Int,
    val at: ZonedDateTime,
    val sound: CueSound,
    val playback: CuePlayback,
    val stageGate: StageGate,
    val alarmRequestCode: Int,
    /** Plain-language account of why this cue is at this time. Shown in the UI. */
    val reason: String,
)

/** A cue that a rule produced but that did not survive the constraints. */
data class DroppedCue(
    val ruleId: Long,
    val at: ZonedDateTime?,
    val reason: String,
)

/**
 * The full result of planning one night.
 *
 * [dropped] is kept rather than discarded so the app can answer "why didn't it
 * cue me at 5am?" precisely, both in the UI and in the diagnostic log. Silent
 * omission is the thing that makes scheduling software feel haunted.
 */
data class NightPlan(
    val resolved: ResolvedNight,
    val cues: List<PlannedCue>,
    val dropped: List<DroppedCue>,
    val seed: Long,
    val explanation: String,
) {
    val cueCount: Int get() = cues.size

    fun nextCueAfter(now: ZonedDateTime): PlannedCue? = cues.firstOrNull { it.at.isAfter(now) }

    fun timeUntilNextCue(now: ZonedDateTime): Duration? =
        nextCueAfter(now)?.let { Duration.between(now, it.at) }
}
