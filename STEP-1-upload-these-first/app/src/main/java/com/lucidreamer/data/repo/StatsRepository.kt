// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.data.repo

import com.lucidreamer.data.SettingsStore
import com.lucidreamer.data.db.ColumnJson
import com.lucidreamer.data.db.LucidDatabase
import com.lucidreamer.data.db.entity.CueOutcome
import com.lucidreamer.data.db.entity.Lucidity
import com.lucidreamer.domain.cue.BuiltInTone
import com.lucidreamer.domain.cue.CueSound
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Local statistics.
 *
 * Every figure here is a count of things the user recorded, not a measurement
 * of their sleep. That framing is carried through to the UI, which labels these
 * as self-reported logs - an app that presents "lucid dream rate: 18%" with the
 * confidence of a laboratory result is lying about what it knows.
 *
 * Statistics can be switched off entirely, in which case nothing is computed.
 */
class StatsRepository(
    private val db: LucidDatabase,
    private val settings: SettingsStore,
) {
    data class Summary(
        val nightsRecorded: Int,
        val cuesPlayed: Int,
        val dreamsRecorded: Int,
        val lucidDreams: Int,
        /** Lucid dreams as a share of recorded dreams, or null when there are none. */
        val lucidRate: Float?,
        val dreamsLast30Days: Int,
        val lucidLast30Days: Int,
        val realityChecksLast30Days: Int,
        val averageSleepDuration: Duration?,
        val averageBedTimeMinute: Int?,
        val averageWakeTimeMinute: Int?,
        val cueEffectiveness: List<CueEffectiveness>,
    )

    /**
     * How often a lucid dream was recorded after a given cue sound.
     *
     * A correlation over self-reported outcomes with, realistically, a very
     * small sample. Surfaced because personal experimentation is the point of
     * the app, and suppressed below a minimum sample size so it does not
     * present noise as a finding.
     */
    data class CueEffectiveness(
        val soundLabel: String,
        val timesPlayed: Int,
        val lucidAfter: Int,
    ) {
        val rate: Float get() = if (timesPlayed == 0) 0f else lucidAfter.toFloat() / timesPlayed

        /** Below this, the number means nothing and the UI should say so. */
        val hasEnoughData: Boolean get() = timesPlayed >= MIN_SAMPLE

        companion object {
            const val MIN_SAMPLE = 5
        }
    }

    suspend fun enabled(): Boolean = settings.statisticsEnabled.first()

    suspend fun summary(zone: ZoneId = ZoneId.systemDefault()): Summary? {
        if (!enabled()) return null

        val thirtyDaysAgo = System.currentTimeMillis() - Duration.ofDays(30).toMillis()
        val sessions = db.sessionDao().let { dao ->
            dao.observeRecent(365).first()
        }.filter { it.endedAtMillis != null }

        val durations = sessions.mapNotNull { s ->
            val onset = s.actualSleepAtMillis ?: s.estimatedSleepAtMillis
            val end = minOf(s.wakeAtMillis, s.endedAtMillis ?: s.wakeAtMillis)
            (end - onset).takeIf { it > 0 }
        }

        val bedMinutes = sessions.map { minuteOfDay(it.bedAtMillis, zone) }
        val wakeMinutes = sessions.map { minuteOfDay(it.wakeAtMillis, zone) }

        val dreamCount = db.dreamDao().observeCount().first()
        val lucidCount = db.dreamDao().observeLucidCount().first()

        return Summary(
            nightsRecorded = sessions.size,
            cuesPlayed = db.cueEventDao().totalPlayed(),
            dreamsRecorded = dreamCount,
            lucidDreams = lucidCount,
            lucidRate = if (dreamCount == 0) null else lucidCount.toFloat() / dreamCount,
            dreamsLast30Days = db.dreamDao().dreamCountSince(thirtyDaysAgo),
            lucidLast30Days = db.dreamDao().lucidCountSince(thirtyDaysAgo),
            realityChecksLast30Days = db.reminderDao().performedSince(thirtyDaysAgo),
            averageSleepDuration = durations.takeIf { it.isNotEmpty() }
                ?.let { Duration.ofMillis(it.average().toLong()) },
            averageBedTimeMinute = bedMinutes.takeIf { it.isNotEmpty() }?.let { circularMean(it) },
            averageWakeTimeMinute = wakeMinutes.takeIf { it.isNotEmpty() }?.let { circularMean(it) },
            cueEffectiveness = cueEffectiveness(),
        )
    }

    private suspend fun cueEffectiveness(): List<CueEffectiveness> =
        db.dreamDao().cueOutcomeStats()
            .mapNotNull { stat ->
                val sound = runCatching { ColumnJson.decodeFromString<CueSound>(stat.soundJson) }.getOrNull()
                    ?: return@mapNotNull null
                CueEffectiveness(
                    soundLabel = describe(sound),
                    timesPlayed = stat.total,
                    lucidAfter = stat.lucidCount,
                )
            }
            // Merge sounds that describe identically, e.g. two rules using the
            // same built-in tone with different volumes.
            .groupBy { it.soundLabel }
            .map { (label, group) ->
                CueEffectiveness(label, group.sumOf { it.timesPlayed }, group.sumOf { it.lucidAfter })
            }
            .sortedByDescending { it.timesPlayed }

    private fun describe(sound: CueSound): String = when (sound) {
        is CueSound.BuiltIn -> when (sound.tone) {
            BuiltInTone.SOFT_BELL -> "Soft bell"
            BuiltInTone.PURE_TONE -> "Pure tone"
            BuiltInTone.CHIME -> "Chime"
            BuiltInTone.LOW_DRONE -> "Low drone"
            BuiltInTone.NOISE_SWELL -> "Noise swell"
            BuiltInTone.DOUBLE_BEEP -> "Double beep"
        }

        is CueSound.File -> sound.displayName
        is CueSound.Recording -> "Recording: ${sound.displayName}"
        is CueSound.Speech -> "Spoken: \"${sound.text.take(24)}\""
        is CueSound.Silent -> "Silent (vibration)"
    }

    private fun minuteOfDay(millis: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

    /** Erases derived statistics without touching the journal. */
    suspend fun clearStatistics() {
        db.reminderDao().clearLog()
        db.heartbeatDao().pruneBefore(System.currentTimeMillis())
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        val CUE_OUTCOME_SUCCESS = CueOutcome.PLAYED
        val LUCID_STATES = setOf(Lucidity.PARTIAL, Lucidity.FULL)

        /**
         * Mean of times-of-day, computed on a circle.
         *
         * A plain arithmetic mean of 23:50 and 00:10 gives 12:00 - not merely
         * wrong but the exact opposite of right, and the kind of error that
         * would sit unnoticed on the dashboard as "average bedtime: midday".
         * Averaging the unit vectors gives midnight, as it should.
         *
         * In the companion rather than the instance because it depends on
         * nothing else, which keeps it directly testable.
         */
        fun circularMean(minutes: List<Int>): Int {
            if (minutes.isEmpty()) return 0
            var x = 0.0
            var y = 0.0
            for (m in minutes) {
                val angle = 2 * Math.PI * m / MINUTES_PER_DAY
                x += kotlin.math.cos(angle)
                y += kotlin.math.sin(angle)
            }
            val mean = kotlin.math.atan2(y / minutes.size, x / minutes.size)
            val normalised = if (mean < 0) mean + 2 * Math.PI else mean
            return ((normalised / (2 * Math.PI)) * MINUTES_PER_DAY).toInt() % MINUTES_PER_DAY
        }
    }
}
