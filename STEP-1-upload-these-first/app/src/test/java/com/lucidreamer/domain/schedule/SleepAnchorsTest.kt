// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.domain.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class SleepAnchorsTest {

    private val london = ZoneId.of("Europe/London")
    private val prague = ZoneId.of("Europe/Prague")

    @Test
    fun `sleep onset is bedtime plus latency, not bedtime`() {
        val a = SleepAnchors(
            bedTime = LocalTime.of(23, 0),
            onsetLatency = Duration.ofMinutes(25),
            wakeTime = LocalTime.of(7, 0),
        )
        val n = a.resolveFor(LocalDate.of(2026, 3, 10), prague)

        assertEquals(LocalTime.of(23, 0), n.bedAt.toLocalTime())
        assertEquals(LocalTime.of(23, 25), n.estimatedSleepAt.toLocalTime())
        assertTrue("onset must be flagged as estimated", n.isOnsetEstimated)
    }

    @Test
    fun `wake time on the following calendar day is resolved correctly`() {
        val a = SleepAnchors(
            bedTime = LocalTime.of(23, 30),
            onsetLatency = Duration.ofMinutes(15),
            wakeTime = LocalTime.of(6, 45),
        )
        val night = LocalDate.of(2026, 5, 4)
        val n = a.resolveFor(night, prague)

        assertEquals(night, n.bedAt.toLocalDate())
        assertEquals(night.plusDays(1), n.wakeAt.toLocalDate())
        assertEquals(LocalTime.of(6, 45), n.wakeAt.toLocalTime())
    }

    @Test
    fun `a day sleeper is handled with no special case`() {
        // Night shift worker: to bed at 09:00, wakes 16:30, same calendar day.
        val a = SleepAnchors(
            bedTime = LocalTime.of(9, 0),
            onsetLatency = Duration.ofMinutes(20),
            wakeTime = LocalTime.of(16, 30),
        )
        val night = LocalDate.of(2026, 5, 4)
        val n = a.resolveFor(night, prague)

        assertEquals(night, n.wakeAt.toLocalDate())
        assertEquals(Duration.ofHours(7).plusMinutes(10), n.sleepDuration)
    }

    @Test
    fun `duration-based schedule derives wake time from onset`() {
        val a = SleepAnchors(
            bedTime = LocalTime.of(1, 15),
            onsetLatency = Duration.ofMinutes(30),
            basis = ScheduleBasis.DURATION,
            sleepDuration = Duration.ofHours(6),
        )
        val n = a.resolveFor(LocalDate.of(2026, 5, 4), prague)

        assertEquals(LocalTime.of(1, 45), n.estimatedSleepAt.toLocalTime())
        assertEquals(LocalTime.of(7, 45), n.wakeAt.toLocalTime())
    }

    @Test
    fun `spring forward does not lose an hour of wall-clock wake time`() {
        // Europe/Prague springs forward 2026-03-29 at 02:00 -> 03:00.
        val a = SleepAnchors(
            bedTime = LocalTime.of(23, 0),
            onsetLatency = Duration.ofMinutes(20),
            wakeTime = LocalTime.of(7, 0),
        )
        val n = a.resolveFor(LocalDate.of(2026, 3, 28), prague)

        // The user still wakes at 07:00 by the clock on their wall...
        assertEquals(LocalTime.of(7, 0), n.wakeAt.toLocalTime())
        // ...but they got an hour less actual sleep, and the app knows it.
        assertEquals(Duration.ofHours(6).plusMinutes(40), n.sleepDuration)
    }

    @Test
    fun `fall back gives an extra hour of real sleep`() {
        // Europe/Prague falls back 2026-10-25 at 03:00 -> 02:00.
        val a = SleepAnchors(
            bedTime = LocalTime.of(23, 0),
            onsetLatency = Duration.ofMinutes(20),
            wakeTime = LocalTime.of(7, 0),
        )
        val n = a.resolveFor(LocalDate.of(2026, 10, 24), prague)

        assertEquals(LocalTime.of(7, 0), n.wakeAt.toLocalTime())
        assertEquals(Duration.ofHours(8).plusMinutes(40), n.sleepDuration)
    }

    @Test
    fun `bedtime inside a DST gap resolves forward rather than throwing`() {
        // Europe/London springs forward 2026-03-29 at 01:00 -> 02:00, so 01:30
        // does not exist on that date.
        val a = SleepAnchors(
            bedTime = LocalTime.of(1, 30),
            onsetLatency = Duration.ofMinutes(10),
            wakeTime = LocalTime.of(9, 0),
        )
        val n = a.resolveFor(LocalDate.of(2026, 3, 29), london)

        assertEquals(LocalTime.of(2, 30), n.bedAt.toLocalTime())
        assertTrue(n.wakeAt.isAfter(n.estimatedSleepAt))
    }

    @Test
    fun `reported onset overrides the estimate and clears the estimated flag`() {
        val a = SleepAnchors(
            bedTime = LocalTime.of(23, 0),
            onsetLatency = Duration.ofMinutes(20),
            wakeTime = LocalTime.of(7, 0),
        )
        val n = a.resolveFor(LocalDate.of(2026, 5, 4), prague)
        val actual = n.bedAt.plusMinutes(75)
        val withReal = n.withActualOnset(actual, a.basis, a.impliedDuration())

        assertEquals(actual, withReal.sleepAt)
        assertTrue(!withReal.isOnsetEstimated)
        // Wake-time based schedule: the alarm stays put, the night is shorter.
        assertEquals(n.wakeAt, withReal.wakeAt)
    }

    @Test
    fun `duration-based schedule shifts wake time when you go to bed late`() {
        val a = SleepAnchors(
            bedTime = LocalTime.of(23, 0),
            onsetLatency = Duration.ofMinutes(20),
            basis = ScheduleBasis.DURATION,
            sleepDuration = Duration.ofHours(8),
        )
        val n = a.resolveFor(LocalDate.of(2026, 5, 4), prague)
        val lateOnset = n.bedAt.plusHours(2)
        val shifted = n.withActualOnset(lateOnset, a.basis, a.sleepDuration)

        assertEquals(lateOnset.plusHours(8), shifted.wakeAt)
    }

    @Test
    fun `implied duration wraps midnight`() {
        val a = SleepAnchors(
            bedTime = LocalTime.of(22, 45),
            onsetLatency = Duration.ofMinutes(15),
            wakeTime = LocalTime.of(6, 30),
        )
        assertEquals(Duration.ofHours(7).plusMinutes(30), a.impliedDuration())
    }
}
