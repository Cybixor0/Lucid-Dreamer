// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.domain.cue

import com.lucidreamer.domain.schedule.ResolvedNight
import com.lucidreamer.domain.schedule.nextOccurrenceAfter
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Turns cue rules into a concrete list of instants for one night.
 *
 * This is a pure function of its inputs, with no clock, no I/O and no Android
 * dependencies, which is what makes the awkward cases - daylight saving,
 * midnight wrap, lie-ins, day sleepers - directly testable.
 *
 * ## Determinism
 *
 * Randomised timings are driven by an explicit [seed] that is generated once per
 * night and persisted. Every later re-plan (reboot, timezone change, app update,
 * watchdog repair) reuses that seed and therefore reproduces exactly the same
 * night. Without this, a crash at 3am would silently reshuffle every remaining
 * cue, and "the app moved my cues" would be unreproducible and undebuggable.
 */
object CuePlanner {

    fun plan(
        night: ResolvedNight,
        profile: CueProfile,
        seed: Long,
        /** Set once the user has actually gone back to bed after WBTB. */
        wbtbReturnAt: ZonedDateTime? = null,
    ): NightPlan {
        val candidates = mutableListOf<PlannedCue>()
        val dropped = mutableListOf<DroppedCue>()

        for (rule in profile.rules) {
            if (!rule.enabled) {
                dropped += DroppedCue(rule.id, null, "Rule \"${ruleName(rule)}\" is switched off")
                continue
            }
            expandRule(rule, night, seed, wbtbReturnAt, candidates, dropped)
        }

        candidates.sortBy { it.at }
        val kept = applyConstraints(candidates, night, profile.constraints, dropped)

        return NightPlan(
            resolved = night,
            cues = kept,
            dropped = dropped,
            seed = seed,
            explanation = buildExplanation(night, profile, kept.size, dropped.size),
        )
    }

    // -----------------------------------------------------------------------
    // Rule expansion
    // -----------------------------------------------------------------------

    private fun expandRule(
        rule: CueRule,
        night: ResolvedNight,
        seed: Long,
        wbtbReturnAt: ZonedDateTime?,
        out: MutableList<PlannedCue>,
        dropped: MutableList<DroppedCue>,
    ) {
        val onset = night.sleepAt
        val onsetLabel = if (night.isOnsetEstimated) "estimated sleep onset" else "reported sleep onset"

        when (val timing = rule.timing) {
            is Timing.AbsoluteTime -> {
                val at = nextOccurrenceAfter(timing.at, onset.minusNanos(1), night.zone)
                out += cue(rule, at, 0, night, "At ${timing.at}")
            }

            is Timing.AfterSleepOnset -> {
                val at = onset.plus(timing.offset)
                out += cue(rule, at, 0, night, "${human(timing.offset)} after $onsetLabel")
            }

            is Timing.AfterBedtime -> {
                val at = night.bedAt.plus(timing.offset)
                out += cue(rule, at, 0, night, "${human(timing.offset)} after getting into bed")
            }

            is Timing.BeforeWake -> {
                val at = night.wakeAt.minus(timing.offset)
                out += cue(rule, at, 0, night, "${human(timing.offset)} before wake-up")
            }

            is Timing.FractionOfNight -> {
                val f = timing.fraction.coerceIn(0f, 1f)
                val total = Duration.between(onset, night.wakeAt)
                val at = onset.plus(Duration.ofSeconds((total.seconds * f).roundToLong()))
                out += cue(rule, at, 0, night, "${(f * 100).roundToLong()}% through the night")
            }

            is Timing.Repeating -> {
                val range = timing.window.resolve(night)
                if (timing.interval <= Duration.ZERO) {
                    dropped += DroppedCue(rule.id, null, "Repeat interval must be greater than zero")
                    return
                }
                val rnd = ruleRandom(seed, rule.id)
                var at = range.start
                var i = 0
                while (!at.isAfter(range.endInclusive) && i < timing.maxCount) {
                    val jittered = applyJitter(at, timing.jitter, rnd)
                    out += cue(
                        rule, jittered, i, night,
                        "Every ${human(timing.interval)} in ${windowLabel(timing.window)}" +
                            if (timing.jitter > Duration.ZERO) ", ±${human(timing.jitter)}" else "",
                    )
                    at = at.plus(timing.interval)
                    i++
                }
                if (i == 0) {
                    dropped += DroppedCue(rule.id, null, "Repeat window is empty for this night")
                }
            }

            is Timing.RandomInWindow -> {
                val range = timing.window.resolve(night)
                val picks = pickRandomTimes(range, timing.count, timing.minGap, ruleRandom(seed, rule.id))
                if (picks.isEmpty()) {
                    dropped += DroppedCue(
                        rule.id, null,
                        "Could not fit ${timing.count} cue(s) ${human(timing.minGap)} apart into ${windowLabel(timing.window)}",
                    )
                }
                picks.forEachIndexed { i, at ->
                    out += cue(rule, at, i, night, "Random time in ${windowLabel(timing.window)}")
                }
            }

            is Timing.AfterWbtbReturn -> {
                if (wbtbReturnAt == null) {
                    dropped += DroppedCue(
                        rule.id, null,
                        "Waiting until you go back to bed after WBTB - this cue is scheduled then",
                    )
                } else {
                    val at = wbtbReturnAt.plus(timing.offset)
                    out += cue(rule, at, 0, night, "${human(timing.offset)} after going back to bed")
                }
            }
        }
    }

    private fun cue(
        rule: CueRule,
        at: ZonedDateTime,
        index: Int,
        night: ResolvedNight,
        reason: String,
    ) = PlannedCue(
        ruleId = rule.id,
        indexWithinRule = index,
        at = at,
        sound = rule.sound,
        playback = rule.playback,
        stageGate = rule.stageGate,
        alarmRequestCode = requestCode(night, rule.id, index),
        reason = reason,
    )

    /**
     * Stable across re-planning: same night, same rule, same index always gives
     * the same code, so re-arming updates the existing alarm instead of adding
     * a duplicate.
     */
    internal fun requestCode(night: ResolvedNight, ruleId: Long, index: Int): Int {
        var h = night.night.toEpochDay() * 31 + ruleId
        h = h * 31 + index
        // Keep it positive and clear of the small codes reserved for the
        // watchdog, WBTB and reality-check alarms.
        return ((h.toInt() and 0x00FFFFFF) or 0x01000000)
    }

    private fun ruleRandom(seed: Long, ruleId: Long) = Random(seed * 31 + ruleId)

    private fun applyJitter(at: ZonedDateTime, jitter: Duration, rnd: Random): ZonedDateTime {
        if (jitter <= Duration.ZERO) return at
        val range = jitter.seconds
        val delta = rnd.nextLong(-range, range + 1)
        return at.plusSeconds(delta)
    }

    /**
     * Places [count] times inside [range], guaranteeing at least [minGap]
     * between consecutive picks while still being genuinely random.
     *
     * Uses the standard "subtract the gaps, then add them back" construction:
     * reserve (n-1) x gap of the window, draw n sorted uniform points from what
     * is left, then push the i-th point out by i x gap. Consecutive spacing is
     * then (difference of two sorted uniforms) + gap, which is >= gap by
     * construction, and the last point still lands inside the window.
     *
     * Naive slot-based placement does *not* have this property - two picks can
     * sit either side of a slot boundary and end up milliseconds apart - and
     * rejection sampling can fail to terminate on a tight window. This always
     * terminates and degrades predictably: it returns as many as genuinely fit
     * and the caller records the shortfall.
     */
    private fun pickRandomTimes(
        range: ClosedRange<ZonedDateTime>,
        count: Int,
        minGap: Duration,
        rnd: Random,
    ): List<ZonedDateTime> {
        if (count <= 0) return emptyList()
        val totalSeconds = Duration.between(range.start, range.endInclusive).seconds
        if (totalSeconds <= 0) return emptyList()

        val gap = maxOf(minGap.seconds, 0L)
        // n points need (n-1) gaps to fit inside the window.
        val fits = if (gap == 0L) count else ((totalSeconds / gap) + 1).toInt()
        val n = minOf(count, fits).coerceAtLeast(0)
        if (n <= 0) return emptyList()

        val free = totalSeconds - (n - 1) * gap
        if (free < 0) return emptyList()

        val draws = LongArray(n) { if (free > 0) rnd.nextLong(free + 1) else 0L }
        draws.sort()

        return (0 until n).map { i ->
            range.start.plusSeconds(draws[i] + i * gap)
        }
    }

    // -----------------------------------------------------------------------
    // Constraints
    // -----------------------------------------------------------------------

    private fun applyConstraints(
        sorted: List<PlannedCue>,
        night: ResolvedNight,
        c: CueConstraints,
        dropped: MutableList<DroppedCue>,
    ): List<PlannedCue> {
        val earliest = night.sleepAt.plus(c.minimumSleepBeforeFirstCue)
        val latest = night.wakeAt.minus(c.stopBeforeWake)
        val kept = mutableListOf<PlannedCue>()

        for (cue in sorted) {
            if (cue.at.isBefore(night.sleepAt)) {
                dropped += DroppedCue(cue.ruleId, cue.at, "Before you would be asleep")
                continue
            }
            if (cue.at.isBefore(earliest)) {
                dropped += DroppedCue(
                    cue.ruleId, cue.at,
                    "Too early - you asked for at least ${human(c.minimumSleepBeforeFirstCue)} of sleep first",
                )
                continue
            }
            if (cue.at.isAfter(latest)) {
                dropped += DroppedCue(
                    cue.ruleId, cue.at,
                    "Within ${human(c.stopBeforeWake)} of wake-up",
                )
                continue
            }
            val quiet = c.quietPeriods.firstOrNull { q ->
                val from = night.sleepAt.plus(q.fromOnset)
                val to = from.plus(q.length)
                !cue.at.isBefore(from) && !cue.at.isAfter(to)
            }
            if (quiet != null) {
                val name = quiet.label.ifBlank { "a quiet period" }
                dropped += DroppedCue(cue.ruleId, cue.at, "Inside $name")
                continue
            }
            val previous = kept.lastOrNull()
            if (previous != null) {
                val gap = Duration.between(previous.at, cue.at)
                if (gap < c.minGapBetweenCues) {
                    dropped += DroppedCue(
                        cue.ruleId, cue.at,
                        "Less than ${human(c.minGapBetweenCues)} after the previous cue",
                    )
                    continue
                }
            }
            if (c.maxCuesPerNight != null && kept.size >= c.maxCuesPerNight) {
                dropped += DroppedCue(
                    cue.ruleId, cue.at,
                    "Over the limit of ${c.maxCuesPerNight} cues per night",
                )
                continue
            }
            kept += cue
        }
        return kept
    }

    // -----------------------------------------------------------------------
    // Human-readable output
    // -----------------------------------------------------------------------

    private fun buildExplanation(
        night: ResolvedNight,
        profile: CueProfile,
        keptCount: Int,
        droppedCount: Int,
    ): String {
        val onsetWord = if (night.isOnsetEstimated) "estimated" else "reported"
        return buildString {
            append("\"${profile.name}\": $keptCount cue")
            if (keptCount != 1) append("s")
            append(" between ${night.sleepAt.toLocalTime().withSecond(0).withNano(0)} ")
            append("($onsetWord sleep onset) and ${night.wakeAt.toLocalTime().withSecond(0).withNano(0)}")
            if (droppedCount > 0) append(" - $droppedCount not scheduled")
            append(".")
        }
    }

    private fun ruleName(rule: CueRule) = rule.label.ifBlank { "Cue ${rule.id}" }

    private fun windowLabel(window: TimeWindow): String = when (window) {
        is TimeWindow.Absolute -> "${window.from}-${window.to}"
        is TimeWindow.RelativeToOnset -> "${human(window.from)}-${human(window.to)} after sleep onset"
        is TimeWindow.UntilWake -> "${human(window.from)} after onset until wake-up"
    }

    /** "4h 30m", "45m", "90s" - never "PT4H30M". */
    internal fun human(d: Duration): String {
        val h = d.toHours()
        val m = d.toMinutes() % 60
        val s = d.seconds % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            m > 0 && s > 0 -> "${m}m ${s}s"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }
}
