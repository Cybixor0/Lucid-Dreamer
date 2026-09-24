// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.domain.cue

import com.lucidreamer.sensing.SleepStage
import com.lucidreamer.sensing.StageEstimate
import java.time.Duration
import java.time.ZonedDateTime

/**
 * Decides whether an estimate justifies moving or holding a cue.
 *
 * ## The guiding rule
 *
 * **Adaptive scheduling is a refinement over the fixed plan, never a
 * replacement for it.** If the estimate is not confident enough to act on, the
 * cue fires exactly where the user put it. A night where sensing fails
 * completely is identical to a night with sensing switched off.
 *
 * This matters because the alternative - letting a low-confidence guess
 * suppress or move cues - would make the app *worse* than not sensing at all,
 * while feeling more sophisticated. A guess that silently eats your cues is the
 * failure mode to design against.
 */
object AdaptiveScheduler {

    /** What the scheduler decided, and why, in words the UI can show. */
    data class Decision(
        val action: Action,
        val firedAt: ZonedDateTime,
        val reason: String,
    )

    enum class Action {
        /** Play it now, as planned. */
        PLAY_NOW,

        /** Hold briefly and re-check - the estimate suggests a better moment is near. */
        DEFER,

        /** Skip entirely - the gate says this stage is wrong for this cue. */
        SKIP,
    }

    /**
     * Evaluates a cue at the moment its alarm fires.
     *
     * @param deferredSoFar how long this cue has already been held, so it
     *   cannot be deferred indefinitely
     */
    fun evaluate(
        gate: StageGate,
        estimate: StageEstimate,
        now: ZonedDateTime,
        deferredSoFar: Duration,
        maxDeferral: Duration = DEFAULT_MAX_DEFERRAL,
    ): Decision {
        if (gate == StageGate.ANY) {
            return Decision(Action.PLAY_NOW, now, "No sleep-stage condition set")
        }

        if (!estimate.isUsable) {
            // The most important branch in this file. An unreliable estimate
            // must never be allowed to silently swallow a cue.
            return Decision(
                Action.PLAY_NOW,
                now,
                "Sleep estimate too uncertain (${(estimate.confidence * 100).toInt()}%), " +
                    "so the cue played as scheduled",
            )
        }

        if (deferredSoFar >= maxDeferral) {
            return Decision(
                Action.PLAY_NOW,
                now,
                "Waited ${deferredSoFar.toMinutes()} minutes for a better moment; played anyway",
            )
        }

        val matches = gateMatches(gate, estimate)
        val description = estimate.describe()

        return when {
            matches -> Decision(Action.PLAY_NOW, now, "Matched \"${gate.label}\" - $description")

            // Deep sleep is the one case where waiting is clearly better than
            // playing: a cue is least likely to reach you and most likely to
            // cost you the sleep you actually need.
            gate == StageGate.NOT_LIKELY_DEEP && estimate.mostLikely == SleepStage.POSSIBLE_DEEP ->
                Decision(
                    Action.DEFER,
                    now.plus(DEFERRAL_STEP),
                    "Possibly in deeper sleep - holding ${DEFERRAL_STEP.toMinutes()} minutes",
                )

            gate == StageGate.ONLY_LIKELY_REM || gate == StageGate.REM_OR_LIGHT ->
                Decision(
                    Action.DEFER,
                    now.plus(DEFERRAL_STEP),
                    "Waiting for a more REM-like window - currently $description",
                )

            else -> Decision(Action.SKIP, now, "Did not match \"${gate.label}\" - $description")
        }
    }

    internal fun gateMatches(gate: StageGate, estimate: StageEstimate): Boolean = when (gate) {
        StageGate.ANY -> true

        StageGate.ONLY_LIKELY_REM ->
            estimate.mostLikely == SleepStage.POSSIBLE_REM &&
                estimate.rem >= REM_THRESHOLD

        StageGate.REM_OR_LIGHT ->
            estimate.mostLikely == SleepStage.POSSIBLE_REM ||
                estimate.mostLikely == SleepStage.LIGHT

        StageGate.NOT_LIKELY_DEEP ->
            estimate.mostLikely != SleepStage.POSSIBLE_DEEP

        // Handled by the planner's minimum-sleep constraint before a cue is
        // ever armed, so by the time we are here it is already satisfied.
        StageGate.AFTER_MIN_SLEEP -> true
    }

    /**
     * Nudges planned cues towards the most REM-like moment inside their own
     * window, using estimates gathered so far tonight.
     *
     * Only moves a cue *within* the window the user configured - it never
     * invents a time outside it - and only when the estimates are good enough
     * to be worth acting on.
     */
    fun suggestShift(
        plannedAt: ZonedDateTime,
        windowStart: ZonedDateTime,
        windowEnd: ZonedDateTime,
        recent: List<StageEstimate>,
        maxShift: Duration = DEFAULT_MAX_SHIFT,
    ): ZonedDateTime? {
        val usable = recent.filter { it.isUsable }
        if (usable.size < MIN_ESTIMATES_FOR_SHIFT) return null

        val earliest = maxOf(windowStart, plannedAt.minus(maxShift))
        val latest = minOf(windowEnd, plannedAt.plus(maxShift))
        if (!earliest.isBefore(latest)) return null

        // Cycles are the only thing that lets us say anything about the
        // *future*: REM recurs roughly every 90 minutes, so the best available
        // guess is one cycle on from the most REM-like moment observed tonight.
        val bestSoFar = usable.maxByOrNull { it.rem } ?: return null
        val candidate = java.time.Instant.ofEpochMilli(bestSoFar.atMillis)
            .atZone(plannedAt.zone)
            .plusMinutes(CYCLE_MINUTES.toLong())

        return when {
            candidate.isBefore(earliest) -> null
            candidate.isAfter(latest) -> null
            // Not worth moving a cue by a couple of minutes on evidence this weak.
            Duration.between(plannedAt, candidate).abs() < MIN_SHIFT -> null
            else -> candidate
        }
    }

    /** How confident the REM estimate must be before a REM-only gate accepts. */
    const val REM_THRESHOLD = 0.4f

    val DEFERRAL_STEP: Duration = Duration.ofMinutes(8)
    val DEFAULT_MAX_DEFERRAL: Duration = Duration.ofMinutes(40)
    val DEFAULT_MAX_SHIFT: Duration = Duration.ofMinutes(30)
    private val MIN_SHIFT: Duration = Duration.ofMinutes(5)

    private const val MIN_ESTIMATES_FOR_SHIFT = 10
    private const val CYCLE_MINUTES = 90
}

/** Whether cue times come straight from the plan or may be nudged by estimates. */
enum class SchedulingMode {
    FIXED,
    ADAPTIVE;

    val label: String get() = if (this == FIXED) "Fixed times" else "Adaptive"

    val description: String
        get() = when (this) {
            FIXED ->
                "Cues play exactly when your rules say. Predictable, and completely independent " +
                    "of any sensing."

            ADAPTIVE ->
                "Cues may be held back by up to 40 minutes to land in a more REM-like moment, " +
                    "within the window you set. Requires sleep sensing. Whenever the estimate is " +
                    "not confident enough, the cue plays at its scheduled time instead - so this " +
                    "can delay a cue, but it will not lose one."
        }
}
