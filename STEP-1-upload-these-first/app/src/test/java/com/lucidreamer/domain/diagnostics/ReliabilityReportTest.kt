// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class ReliabilityReportTest {

    private val start = 1_800_000_000_000L
    private val hour = Duration.ofHours(1).toMillis()
    private val end = start + 8 * hour

    /** Heartbeats every two minutes across the whole night, as a healthy run produces. */
    private fun healthyHeartbeats(): List<Long> =
        generateSequence(start) { it + Duration.ofMinutes(2).toMillis() }
            .takeWhile { it <= end }
            .toList()

    private fun input(
        heartbeats: List<Long> = healthyHeartbeats(),
        scheduled: Int = 4,
        played: Int = 4,
        missed: Int = 0,
        skipped: Int = 0,
        latencies: List<Long> = listOf(120L, 340L, 90L, 500L),
        dnd: Boolean = false,
        noRoute: Boolean = false,
        ignoringBattery: Boolean = true,
        exactAlarms: Boolean = true,
        bucket: Int = 10,
        samsung: Boolean = false,
    ) = ReliabilityReport.Input(
        sessionId = 1,
        sessionStartMillis = start,
        sessionEndMillis = end,
        heartbeatMillis = heartbeats,
        cuesScheduled = scheduled,
        cuesPlayed = played,
        cuesMissed = missed,
        cuesSkippedByUser = skipped,
        cueLatenciesMillis = latencies,
        anyCueSilencedByDnd = dnd,
        anyCueHadNoRoute = noRoute,
        ignoringBatteryOptimisations = ignoringBattery,
        canScheduleExactAlarms = exactAlarms,
        worstStandbyBucket = bucket,
        isSamsung = samsung,
    )

    // -----------------------------------------------------------------------
    // Freeze detection
    // -----------------------------------------------------------------------

    @Test
    fun `a healthy night reports no freezes and nothing wrong`() {
        val report = ReliabilityReport.build(input())

        assertTrue(report.freezes.isEmpty())
        assertEquals(Culprit.NOTHING_WRONG, report.culprit)
        assertTrue(report.wentWell)
        assertTrue(report.headline.contains("on time"))
    }

    @Test
    fun `a gap between heartbeats is detected as a freeze`() {
        val beats = healthyHeartbeats().filterNot { it > start + 2 * hour && it < start + 5 * hour }
        val report = ReliabilityReport.build(input(heartbeats = beats, played = 2, missed = 2))

        assertEquals(1, report.freezes.size)
        assertEquals(3, report.longestFreeze.toHours())
    }

    @Test
    fun `a freeze that runs to the end of the night is still detected`() {
        // The characteristic Samsung failure: suspended at 02:00 and never
        // heard from again. There is no gap *between* heartbeats here, only
        // silence after the last one, so treating the session end as an
        // implicit heartbeat is what makes this visible at all.
        val beats = healthyHeartbeats().filter { it <= start + 2 * hour }
        val report = ReliabilityReport.build(input(heartbeats = beats, played = 1, missed = 3))

        assertEquals(1, report.freezes.size)
        assertEquals(6, report.longestFreeze.toHours())
    }

    @Test
    fun `a freeze at the very start of the night is detected`() {
        val beats = healthyHeartbeats().filter { it >= start + 3 * hour }
        val report = ReliabilityReport.build(input(heartbeats = beats, played = 2, missed = 2))

        assertEquals(1, report.freezes.size)
        assertEquals(3, report.longestFreeze.toHours())
    }

    @Test
    fun `several freezes are summed and the longest reported separately`() {
        val beats = healthyHeartbeats().filterNot {
            (it > start + 1 * hour && it < start + 2 * hour) ||
                (it > start + 4 * hour && it < start + 7 * hour)
        }
        val report = ReliabilityReport.build(input(heartbeats = beats, played = 1, missed = 3))

        assertEquals(2, report.freezes.size)
        assertEquals(3, report.longestFreeze.toHours())
        assertEquals(4, report.totalFrozen.toHours())
    }

    @Test
    fun `an ordinary short Doze gap is not treated as a freeze`() {
        val beats = healthyHeartbeats().filterNot {
            it > start + 2 * hour && it < start + 2 * hour + Duration.ofMinutes(10).toMillis()
        }
        val report = ReliabilityReport.build(input(heartbeats = beats))
        assertTrue("10 minutes is below the threshold", report.freezes.isEmpty())
    }

    @Test
    fun `no heartbeats at all reads as one freeze spanning the night`() {
        val report = ReliabilityReport.build(input(heartbeats = emptyList(), played = 0, missed = 4))
        assertEquals(1, report.freezes.size)
        assertEquals(8, report.longestFreeze.toHours())
    }

    // -----------------------------------------------------------------------
    // Attribution
    // -----------------------------------------------------------------------

    @Test
    fun `a frozen Samsung without a battery exemption is blamed on One UI`() {
        val beats = healthyHeartbeats().filter { it <= start + 2 * hour }
        val report = ReliabilityReport.build(
            input(heartbeats = beats, played = 1, missed = 3, ignoringBattery = false, samsung = true),
        )

        assertEquals(Culprit.MANUFACTURER_BATTERY_MANAGER, report.culprit)
        assertTrue(report.headline.contains("Samsung"))
        assertTrue(report.detail.contains("Never sleeping apps"))
    }

    @Test
    fun `a frozen non-Samsung without a battery exemption is blamed on battery optimisation`() {
        val beats = healthyHeartbeats().filter { it <= start + 2 * hour }
        val report = ReliabilityReport.build(
            input(heartbeats = beats, played = 1, missed = 3, ignoringBattery = false, samsung = false),
        )
        assertEquals(Culprit.BATTERY_OPTIMISATION, report.culprit)
    }

    @Test
    fun `silenced alarms beat a freeze, because the fix is more specific`() {
        val beats = healthyHeartbeats().filter { it <= start + 2 * hour }
        val report = ReliabilityReport.build(
            input(heartbeats = beats, played = 0, missed = 4, dnd = true, ignoringBattery = false, samsung = true),
        )

        assertEquals(Culprit.DND_SILENCED_ALARMS, report.culprit)
        assertTrue(report.headline.contains("Do Not Disturb"))
    }

    @Test
    fun `a missing audio route is reported distinctly`() {
        val report = ReliabilityReport.build(input(played = 0, missed = 4, noRoute = true))
        assertEquals(Culprit.NO_AUDIO_ROUTE, report.culprit)
        assertTrue(report.headline.contains("headphones"))
    }

    @Test
    fun `missing exact alarm permission is reported distinctly`() {
        val report = ReliabilityReport.build(input(played = 2, missed = 2, exactAlarms = false))
        assertEquals(Culprit.EXACT_ALARM_PERMISSION, report.culprit)
        assertTrue(report.headline.contains("exact alarms"))
    }

    @Test
    fun `a restricted standby bucket is reported when nothing more specific applies`() {
        val report = ReliabilityReport.build(input(played = 2, missed = 2, bucket = 45))
        assertEquals(Culprit.STANDBY_BUCKET, report.culprit)
    }

    @Test
    fun `missed cues with no detectable cause are admitted as unknown rather than guessed`() {
        val report = ReliabilityReport.build(input(played = 3, missed = 1))
        assertEquals(Culprit.UNKNOWN, report.culprit)
        assertTrue(report.headline.contains("not clear"))
        assertTrue(report.detail.contains("event log"))
    }

    @Test
    fun `a night with no cues scheduled says so plainly`() {
        val report = ReliabilityReport.build(input(scheduled = 0, played = 0, latencies = emptyList()))
        assertEquals(Culprit.NOTHING_WRONG, report.culprit)
        assertTrue(report.headline.contains("No cues were scheduled"))
    }

    // -----------------------------------------------------------------------
    // Latency
    // -----------------------------------------------------------------------

    @Test
    fun `median and worst latency are reported`() {
        val report = ReliabilityReport.build(input(latencies = listOf(100L, 200L, 300L, 9000L)))
        assertEquals(300L, report.medianLatency?.toMillis())
        assertEquals(9000L, report.worstLatency?.toMillis())
    }

    @Test
    fun `no cues means no latency figures rather than a misleading zero`() {
        val report = ReliabilityReport.build(input(scheduled = 0, played = 0, latencies = emptyList()))
        assertEquals(null, report.medianLatency)
        assertEquals(null, report.worstLatency)
    }

    @Test
    fun `durations are formatted for humans`() {
        assertEquals("3h 42m", ReliabilityReport.humanDuration(Duration.ofMinutes(222)))
        assertEquals("2h", ReliabilityReport.humanDuration(Duration.ofHours(2)))
        assertEquals("45m", ReliabilityReport.humanDuration(Duration.ofMinutes(45)))
    }

    @Test
    fun `singular and plural cue counts read correctly`() {
        assertTrue(ReliabilityReport.build(input(scheduled = 1, played = 1)).headline.contains("1 cue played"))
        assertTrue(ReliabilityReport.build(input(scheduled = 3, played = 3)).headline.contains("3 cues played"))
    }
}
