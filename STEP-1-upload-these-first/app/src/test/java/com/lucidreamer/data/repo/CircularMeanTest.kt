// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * The circular mean is the one piece of statistics arithmetic here that is easy
 * to get silently wrong, so it is tested directly. A plain average of 23:50 and
 * 00:10 gives midday - the exact opposite of the right answer - and nobody
 * would notice until "average bedtime: 12:00" appeared on the dashboard.
 */
class CircularMeanTest {

    private fun mean(vararg hhmm: Pair<Int, Int>): Int =
        StatsRepository.circularMean(hhmm.map { (h, m) -> h * 60 + m })

    /** Minutes apart on a 24-hour circle. */
    private fun circularDistance(a: Int, b: Int): Int {
        val d = abs(a - b)
        return min(d, StatsRepository.MINUTES_PER_DAY - d)
    }

    private fun assertNear(expectedMinute: Int, actual: Int, tolerance: Int = 1) {
        assertTrue(
            "expected ~${expectedMinute / 60}:${"%02d".format(expectedMinute % 60)}, " +
                "got ${actual / 60}:${"%02d".format(actual % 60)}",
            circularDistance(actual, expectedMinute) <= tolerance,
        )
    }

    @Test
    fun `times either side of midnight average to midnight, not midday`() {
        assertNear(0, mean(23 to 50, 0 to 10))
    }

    @Test
    fun `ordinary daytime times average normally`() {
        assertNear(10 * 60, mean(9 to 0, 11 to 0))
    }

    @Test
    fun `a realistic spread of late bedtimes averages sensibly`() {
        val result = mean(23 to 15, 23 to 45, 0 to 30, 23 to 30, 0 to 15)
        assertNear(23 * 60 + 51, result, tolerance = 3)
    }

    @Test
    fun `a single time is its own mean`() {
        assertNear(7 * 60 + 30, mean(7 to 30))
    }

    @Test
    fun `an empty list does not throw`() {
        assertEquals(0, StatsRepository.circularMean(emptyList()))
    }

    @Test
    fun `the result is always a valid minute of day`() {
        val samples = listOf(
            listOf(0, 1439),
            listOf(720, 0),
            listOf(1439, 1439, 1439),
            listOf(100, 200, 300, 1400),
        )
        for (s in samples) {
            val r = StatsRepository.circularMean(s)
            assertTrue("out of range for $s: $r", r in 0 until StatsRepository.MINUTES_PER_DAY)
        }
    }
}
