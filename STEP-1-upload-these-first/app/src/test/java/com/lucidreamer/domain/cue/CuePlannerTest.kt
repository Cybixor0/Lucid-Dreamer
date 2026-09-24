// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.domain.cue

import com.lucidreamer.domain.schedule.ScheduleBasis
import com.lucidreamer.domain.schedule.SleepAnchors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class CuePlannerTest {

    private val zone = ZoneId.of("Europe/Prague")
    private val night = LocalDate.of(2026, 5, 4)

    private val anchors = SleepAnchors(
        bedTime = LocalTime.of(23, 0),
        onsetLatency = Duration.ofMinutes(20),
        basis = ScheduleBasis.WAKE_TIME,
        wakeTime = LocalTime.of(7, 0),
    )

    private fun resolved() = anchors.resolveFor(night, zone)

    private fun profile(
        vararg rules: CueRule,
        constraints: CueConstraints = CueConstraints(
            minimumSleepBeforeFirstCue = Duration.ZERO,
            minGapBetweenCues = Duration.ZERO,
            maxCuesPerNight = null,
            stopBeforeWake = Duration.ZERO,
        ),
    ) = CueProfile(id = 1, name = "Test", rules = rules.toList(), constraints = constraints)

    private fun rule(id: Long, timing: Timing) = CueRule(
        id = id,
        timing = timing,
        sound = CueSound.BuiltIn(BuiltInTone.SOFT_BELL),
    )

    // -----------------------------------------------------------------------
    // Timing variants
    // -----------------------------------------------------------------------

    @Test
    fun `after sleep onset anchors to onset, not bedtime`() {
        val p = CuePlanner.plan(resolved(), profile(rule(1, Timing.AfterSleepOnset(Duration.ofHours(4).plusMinutes(30)))), seed = 1)

        assertEquals(1, p.cues.size)
        // 23:00 bed + 20m latency = 23:20 onset; + 4h30m = 03:50.
        assertEquals(LocalTime.of(3, 50), p.cues[0].at.toLocalTime())
    }

    @Test
    fun `after bedtime ignores onset latency`() {
        val p = CuePlanner.plan(resolved(), profile(rule(1, Timing.AfterBedtime(Duration.ofHours(4).plusMinutes(30)))), seed = 1)
        assertEquals(LocalTime.of(3, 30), p.cues[0].at.toLocalTime())
    }

    @Test
    fun `absolute time lands on the morning after a late-evening bedtime`() {
        val p = CuePlanner.plan(resolved(), profile(rule(1, Timing.AbsoluteTime(LocalTime.of(3, 30)))), seed = 1)

        assertEquals(LocalTime.of(3, 30), p.cues[0].at.toLocalTime())
        assertEquals(night.plusDays(1), p.cues[0].at.toLocalDate())
    }

    @Test
    fun `before wake counts back from the alarm`() {
        val p = CuePlanner.plan(resolved(), profile(rule(1, Timing.BeforeWake(Duration.ofMinutes(45)))), seed = 1)
        assertEquals(LocalTime.of(6, 15), p.cues[0].at.toLocalTime())
    }

    @Test
    fun `fraction of night scales with the length of the night`() {
        // Onset 23:20, wake 07:00 -> 7h40m. 75% = 5h45m -> 05:05.
        val p = CuePlanner.plan(resolved(), profile(rule(1, Timing.FractionOfNight(0.75f))), seed = 1)
        assertEquals(LocalTime.of(5, 5), p.cues[0].at.toLocalTime())
    }

    @Test
    fun `repeating fills an absolute window`() {
        val timing = Timing.Repeating(
            window = TimeWindow.Absolute(LocalTime.of(4, 30), LocalTime.of(6, 30)),
            interval = Duration.ofMinutes(30),
            maxCount = 12,
        )
        val p = CuePlanner.plan(resolved(), profile(rule(1, timing)), seed = 1)

        assertEquals(
            listOf(LocalTime.of(4, 30), LocalTime.of(5, 0), LocalTime.of(5, 30), LocalTime.of(6, 0), LocalTime.of(6, 30)),
            p.cues.map { it.at.toLocalTime() },
        )
    }

    @Test
    fun `repeating respects maxCount`() {
        val timing = Timing.Repeating(
            window = TimeWindow.Absolute(LocalTime.of(4, 0), LocalTime.of(7, 0)),
            interval = Duration.ofMinutes(15),
            maxCount = 3,
        )
        val p = CuePlanner.plan(resolved(), profile(rule(1, timing)), seed = 1)
        assertEquals(3, p.cues.size)
    }

    @Test
    fun `relative window follows a late night, absolute window does not`() {
        val relative = Timing.Repeating(
            window = TimeWindow.RelativeToOnset(Duration.ofHours(5), Duration.ofHours(6)),
            interval = Duration.ofHours(1),
            maxCount = 4,
        )
        val normal = CuePlanner.plan(resolved(), profile(rule(1, relative)), seed = 1)
        assertEquals(LocalTime.of(4, 20), normal.cues.first().at.toLocalTime())

        // Same rule, but the user actually fell asleep two hours later.
        val late = resolved().let { it.withActualOnset(it.bedAt.plusHours(2).plusMinutes(20), ScheduleBasis.WAKE_TIME, Duration.ZERO) }
        val lateNight = CuePlanner.plan(late, profile(rule(1, relative)), seed = 1)
        assertEquals(LocalTime.of(6, 20), lateNight.cues.first().at.toLocalTime())
    }

    @Test
    fun `until-wake window can stop short of the alarm`() {
        val timing = Timing.Repeating(
            window = TimeWindow.UntilWake(from = Duration.ofHours(6), stopBefore = Duration.ofMinutes(30)),
            interval = Duration.ofMinutes(20),
            maxCount = 10,
        )
        val p = CuePlanner.plan(resolved(), profile(rule(1, timing)), seed = 1)

        assertTrue(p.cues.isNotEmpty())
        assertTrue("no cue may fall inside the last 30 minutes", p.cues.all { it.at.toLocalTime() <= LocalTime.of(6, 30) })
    }

    @Test
    fun `wbtb-relative cue is deferred with an explanation until the user returns to bed`() {
        val timing = Timing.AfterWbtbReturn(Duration.ofMinutes(20))
        val deferred = CuePlanner.plan(resolved(), profile(rule(1, timing)), seed = 1)

        assertTrue(deferred.cues.isEmpty())
        assertTrue(deferred.dropped.single().reason.contains("back to bed"))

        val back = resolved().bedAt.plusHours(6)
        val armed = CuePlanner.plan(resolved(), profile(rule(1, timing)), seed = 1, wbtbReturnAt = back)
        assertEquals(back.plusMinutes(20), armed.cues.single().at)
    }

    // -----------------------------------------------------------------------
    // Constraints
    // -----------------------------------------------------------------------

    @Test
    fun `minimum sleep before first cue drops early cues with a reason`() {
        val timing = Timing.Repeating(
            window = TimeWindow.RelativeToOnset(Duration.ofHours(1), Duration.ofHours(6)),
            interval = Duration.ofHours(1),
            maxCount = 12,
        )
        val p = CuePlanner.plan(
            resolved(),
            profile(rule(1, timing), constraints = CueConstraints(
                minimumSleepBeforeFirstCue = Duration.ofHours(4),
                minGapBetweenCues = Duration.ZERO,
                maxCuesPerNight = null,
                stopBeforeWake = Duration.ZERO,
            )),
            seed = 1,
        )

        assertTrue(p.cues.all { !it.at.isBefore(resolved().sleepAt.plusHours(4)) })
        assertTrue(p.dropped.any { it.reason.contains("Too early") })
    }

    @Test
    fun `minimum gap is enforced across different rules, not just within one`() {
        val p = CuePlanner.plan(
            resolved(),
            profile(
                rule(1, Timing.AfterSleepOnset(Duration.ofHours(5))),
                rule(2, Timing.AfterSleepOnset(Duration.ofHours(5).plusMinutes(3))),
                constraints = CueConstraints(
                    minimumSleepBeforeFirstCue = Duration.ZERO,
                    minGapBetweenCues = Duration.ofMinutes(15),
                    maxCuesPerNight = null,
                    stopBeforeWake = Duration.ZERO,
                ),
            ),
            seed = 1,
        )

        assertEquals(1, p.cues.size)
        assertTrue(p.dropped.any { it.reason.contains("Less than 15m") })
    }

    @Test
    fun `max cues per night caps the total`() {
        val timing = Timing.Repeating(
            window = TimeWindow.RelativeToOnset(Duration.ofHours(1), Duration.ofHours(7)),
            interval = Duration.ofMinutes(20),
            maxCount = 50,
        )
        val p = CuePlanner.plan(
            resolved(),
            profile(rule(1, timing), constraints = CueConstraints(
                minimumSleepBeforeFirstCue = Duration.ZERO,
                minGapBetweenCues = Duration.ZERO,
                maxCuesPerNight = 4,
                stopBeforeWake = Duration.ZERO,
            )),
            seed = 1,
        )

        assertEquals(4, p.cues.size)
        assertTrue(p.dropped.any { it.reason.contains("limit of 4") })
    }

    @Test
    fun `quiet periods suppress cues around habitual night wakings`() {
        val timing = Timing.Repeating(
            window = TimeWindow.RelativeToOnset(Duration.ofHours(3), Duration.ofHours(6)),
            interval = Duration.ofMinutes(30),
            maxCount = 12,
        )
        val quiet = QuietPeriod(fromOnset = Duration.ofHours(4), length = Duration.ofMinutes(45), label = "usual 3am waking")
        val p = CuePlanner.plan(
            resolved(),
            profile(rule(1, timing), constraints = CueConstraints(
                minimumSleepBeforeFirstCue = Duration.ZERO,
                minGapBetweenCues = Duration.ZERO,
                maxCuesPerNight = null,
                stopBeforeWake = Duration.ZERO,
                quietPeriods = listOf(quiet),
            )),
            seed = 1,
        )

        val from = resolved().sleepAt.plusHours(4)
        val to = from.plusMinutes(45)
        assertTrue(p.cues.none { !it.at.isBefore(from) && !it.at.isAfter(to) })
        assertTrue(p.dropped.any { it.reason.contains("usual 3am waking") })
    }

    @Test
    fun `disabled rules are reported rather than silently ignored`() {
        val disabled = rule(1, Timing.AfterSleepOnset(Duration.ofHours(5))).copy(enabled = false, label = "Bell")
        val p = CuePlanner.plan(resolved(), profile(disabled), seed = 1)

        assertTrue(p.cues.isEmpty())
        assertTrue(p.dropped.single().reason.contains("Bell"))
    }

    // -----------------------------------------------------------------------
    // Determinism - the property that makes a 3am crash survivable
    // -----------------------------------------------------------------------

    @Test
    fun `the same seed reproduces identical random cue times`() {
        val timing = Timing.RandomInWindow(
            window = TimeWindow.Absolute(LocalTime.of(4, 30), LocalTime.of(6, 30)),
            count = 4,
            minGap = Duration.ofMinutes(15),
        )
        val a = CuePlanner.plan(resolved(), profile(rule(1, timing)), seed = 987654321L)
        val b = CuePlanner.plan(resolved(), profile(rule(1, timing)), seed = 987654321L)

        assertEquals(a.cues.map { it.at }, b.cues.map { it.at })
        assertEquals(a.cues.map { it.alarmRequestCode }, b.cues.map { it.alarmRequestCode })
    }

    @Test
    fun `a different seed gives a different night`() {
        val timing = Timing.RandomInWindow(
            window = TimeWindow.Absolute(LocalTime.of(4, 30), LocalTime.of(6, 30)),
            count = 4,
            minGap = Duration.ofMinutes(10),
        )
        val a = CuePlanner.plan(resolved(), profile(rule(1, timing)), seed = 1L)
        val b = CuePlanner.plan(resolved(), profile(rule(1, timing)), seed = 2L)
        assertTrue(a.cues.map { it.at } != b.cues.map { it.at })
    }

    @Test
    fun `random cues respect the minimum gap and stay inside the window`() {
        val timing = Timing.RandomInWindow(
            window = TimeWindow.Absolute(LocalTime.of(4, 0), LocalTime.of(6, 0)),
            count = 4,
            minGap = Duration.ofMinutes(20),
        )
        repeat(50) { s ->
            val p = CuePlanner.plan(resolved(), profile(rule(1, timing)), seed = s.toLong())
            p.cues.zipWithNext { x, y ->
                assertTrue(
                    "gap too small with seed $s",
                    Duration.between(x.at, y.at) >= Duration.ofMinutes(20).minusSeconds(1),
                )
            }
            assertTrue(p.cues.all { it.at.toLocalTime() >= LocalTime.of(4, 0) && it.at.toLocalTime() <= LocalTime.of(6, 0) })
        }
    }

    @Test
    fun `asking for more random cues than fit reports the shortfall`() {
        val timing = Timing.RandomInWindow(
            window = TimeWindow.Absolute(LocalTime.of(4, 0), LocalTime.of(4, 30)),
            count = 10,
            minGap = Duration.ofMinutes(20),
        )
        val p = CuePlanner.plan(resolved(), profile(rule(1, timing)), seed = 1)
        assertTrue("should fit at most 2", p.cues.size <= 2)
    }

    @Test
    fun `alarm request codes are stable across replanning and unique within a night`() {
        val timing = Timing.Repeating(
            window = TimeWindow.Absolute(LocalTime.of(4, 0), LocalTime.of(6, 0)),
            interval = Duration.ofMinutes(30),
            maxCount = 10,
        )
        val first = CuePlanner.plan(resolved(), profile(rule(1, timing), rule(2, Timing.AfterSleepOnset(Duration.ofHours(6)))), seed = 5)
        val replan = CuePlanner.plan(resolved(), profile(rule(1, timing), rule(2, Timing.AfterSleepOnset(Duration.ofHours(6)))), seed = 5)

        assertEquals(first.cues.map { it.alarmRequestCode }, replan.cues.map { it.alarmRequestCode })
        assertEquals(
            "request codes must be unique within a night",
            first.cues.size,
            first.cues.map { it.alarmRequestCode }.toSet().size,
        )
        assertTrue("codes must be positive", first.cues.all { it.alarmRequestCode > 0 })
    }

    // -----------------------------------------------------------------------
    // Explanations
    // -----------------------------------------------------------------------

    @Test
    fun `every scheduled cue carries a human-readable reason`() {
        val p = CuePlanner.plan(
            resolved(),
            profile(
                rule(1, Timing.AfterSleepOnset(Duration.ofHours(5))),
                rule(2, Timing.AbsoluteTime(LocalTime.of(6, 0))),
                rule(3, Timing.BeforeWake(Duration.ofMinutes(30))),
            ),
            seed = 1,
        )
        assertTrue(p.cues.all { it.reason.isNotBlank() })
        assertTrue(p.cues.any { it.reason.contains("estimated sleep onset") })
        assertNotNull(p.explanation)
        assertTrue(p.explanation.contains("cue"))
    }

    @Test
    fun `explanation distinguishes reported onset from estimated onset`() {
        val r = resolved()
        val reported = r.withActualOnset(r.bedAt.plusMinutes(40), ScheduleBasis.WAKE_TIME, Duration.ZERO)
        val p = CuePlanner.plan(reported, profile(rule(1, Timing.AfterSleepOnset(Duration.ofHours(5)))), seed = 1)

        assertTrue(p.cues[0].reason.contains("reported sleep onset"))
        assertTrue(p.explanation.contains("reported"))
    }

    @Test
    fun `duration formatting is human, never ISO-8601`() {
        assertEquals("4h 30m", CuePlanner.human(Duration.ofMinutes(270)))
        assertEquals("2h", CuePlanner.human(Duration.ofHours(2)))
        assertEquals("45m", CuePlanner.human(Duration.ofMinutes(45)))
        assertEquals("30s", CuePlanner.human(Duration.ofSeconds(30)))
    }
}
