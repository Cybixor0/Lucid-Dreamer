// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.domain.cue

import java.time.Duration
import java.time.LocalTime

/**
 * The lucid-dreaming technique a user says they are interested in.
 *
 * Used only to pick a sensible starting cue profile. No technique is privileged
 * and none is required - the evidence base for all of them is thin and highly
 * individual, so the app's job is to make experimenting easy, not to pick a
 * winner. Every preset is fully editable, and a user can ignore all of them.
 */
enum class Technique(val displayName: String, val blurb: String) {
    MILD(
        "MILD",
        "Mnemonic induction: rehearse the intention to notice you are dreaming, usually " +
            "combined with waking briefly in the early morning.",
    ),
    WBTB(
        "WBTB",
        "Wake Back To Bed: get up after several hours of sleep, stay awake briefly, then " +
            "return to sleep. Often combined with another technique.",
    ),
    WILD(
        "WILD",
        "Wake-initiated: stay aware while falling asleep. Usually attempted after a " +
            "wake-up, since it rarely works at the start of the night.",
    ),
    SSILD(
        "SSILD",
        "Cycles of attention through senses before sleep, generally after a brief wake-up.",
    ),
    DILD(
        "DILD",
        "Dream-initiated: rely on daytime habits, such as reality checks, carrying into " +
            "the dream. Needs no night-time audio at all.",
    ),
    JOURNAL_ONLY(
        "Journal only",
        "No cues. Dream recall first - which is the foundation everything else depends on.",
    ),
    UNDECIDED(
        "Not sure yet",
        "Start with something gentle and change it once you know what you like.",
    ),
}

/**
 * Starting cue profiles.
 *
 * All conservative by default: few cues, late in the night, quiet. It is much
 * easier to recover from a night of cues that were too subtle to notice than
 * from a night of cues that kept waking you up - the second costs you sleep and
 * tends to end the experiment.
 */
object CuePresets {

    private val gentleBell = CueSound.BuiltIn(BuiltInTone.SOFT_BELL)
    private val softTone = CueSound.BuiltIn(BuiltInTone.PURE_TONE)

    /** Quiet enough to blend into a dream rather than end it. */
    private val subtle = CuePlayback(
        volume = 0.12f,
        fadeIn = Duration.ofMillis(800),
        fadeOut = Duration.ofSeconds(1),
        repeatCount = 1,
    )

    private val noticeable = CuePlayback(
        volume = 0.3f,
        fadeIn = Duration.ofMillis(600),
        fadeOut = Duration.ofMillis(900),
        repeatCount = 2,
        repeatGap = Duration.ofSeconds(25),
    )

    fun forTechnique(technique: Technique): List<CueProfile> = when (technique) {
        Technique.JOURNAL_ONLY, Technique.DILD -> listOf(journalOnly(), gentleEarlyMorning())
        Technique.WBTB -> listOf(afterWbtb(), gentleEarlyMorning())
        Technique.WILD -> listOf(afterWbtb(), sparse())
        Technique.SSILD -> listOf(gentleEarlyMorning(), sparse())
        Technique.MILD, Technique.UNDECIDED -> listOf(gentleEarlyMorning(), sparse(), afterWbtb())
    }

    /** Everything, for the cue library. The first is the default selection. */
    fun all(): List<CueProfile> = listOf(
        gentleEarlyMorning(),
        sparse(),
        afterWbtb(),
        fixedTimes(),
        journalOnly(),
    )

    /**
     * The default. Repeats gently through the last stretch of the night, when
     * REM periods are longest and waking briefly is least costly.
     */
    fun gentleEarlyMorning() = CueProfile(
        name = "Gentle early morning",
        description = "A soft bell every 30 minutes from 5 hours after you fall asleep until " +
            "20 minutes before your alarm. Quiet, and capped at 5 cues.",
        rules = listOf(
            CueRule(
                id = 1,
                label = "Soft bell",
                timing = Timing.Repeating(
                    window = TimeWindow.UntilWake(
                        from = Duration.ofHours(5),
                        stopBefore = Duration.ofMinutes(20),
                    ),
                    interval = Duration.ofMinutes(30),
                    jitter = Duration.ofMinutes(4),
                    maxCount = 8,
                ),
                sound = gentleBell,
                playback = subtle,
            ),
        ),
        constraints = CueConstraints(
            minimumSleepBeforeFirstCue = Duration.ofHours(4).plusMinutes(30),
            minGapBetweenCues = Duration.ofMinutes(20),
            maxCuesPerNight = 5,
            stopBeforeWake = Duration.ofMinutes(20),
        ),
    )

    /** For people who find repeated cues disruptive. */
    fun sparse() = CueProfile(
        name = "Two cues only",
        description = "One cue 5 hours after you fall asleep, another 45 minutes before your " +
            "alarm. The least disruptive option that still does something.",
        rules = listOf(
            CueRule(
                id = 1,
                label = "Early",
                timing = Timing.AfterSleepOnset(Duration.ofHours(5)),
                sound = gentleBell,
                playback = subtle,
            ),
            CueRule(
                id = 2,
                label = "Late",
                timing = Timing.BeforeWake(Duration.ofMinutes(45)),
                sound = softTone,
                playback = subtle,
            ),
        ),
        constraints = CueConstraints(
            minimumSleepBeforeFirstCue = Duration.ofHours(4),
            minGapBetweenCues = Duration.ofMinutes(30),
            maxCuesPerNight = 2,
            stopBeforeWake = Duration.ofMinutes(15),
        ),
    )

    /** Anchored to going back to bed, so it follows the wake-up rather than the clock. */
    fun afterWbtb() = CueProfile(
        name = "After WBTB",
        description = "Cues begin 20 minutes after you go back to bed, then every 20 minutes. " +
            "Pairs with Wake Back To Bed; does nothing on its own.",
        rules = listOf(
            CueRule(
                id = 1,
                label = "Settling in",
                timing = Timing.AfterWbtbReturn(Duration.ofMinutes(20)),
                sound = gentleBell,
                playback = subtle,
            ),
            CueRule(
                id = 2,
                label = "Follow-ups",
                timing = Timing.Repeating(
                    window = TimeWindow.UntilWake(
                        from = Duration.ofHours(6),
                        stopBefore = Duration.ofMinutes(15),
                    ),
                    interval = Duration.ofMinutes(20),
                    jitter = Duration.ofMinutes(3),
                    maxCount = 6,
                ),
                sound = softTone,
                playback = subtle,
            ),
        ),
        constraints = CueConstraints(
            minimumSleepBeforeFirstCue = Duration.ofHours(4),
            minGapBetweenCues = Duration.ofMinutes(15),
            maxCuesPerNight = 6,
            stopBeforeWake = Duration.ofMinutes(15),
        ),
    )

    /** For people who would rather think in clock times than in offsets. */
    fun fixedTimes() = CueProfile(
        name = "Fixed clock times",
        description = "04:30, 05:15 and 06:00 regardless of when you fell asleep. Predictable, " +
            "but does not follow a late night.",
        rules = listOf(
            CueRule(id = 1, label = "04:30", timing = Timing.AbsoluteTime(LocalTime.of(4, 30)), sound = gentleBell, playback = noticeable),
            CueRule(id = 2, label = "05:15", timing = Timing.AbsoluteTime(LocalTime.of(5, 15)), sound = gentleBell, playback = noticeable),
            CueRule(id = 3, label = "06:00", timing = Timing.AbsoluteTime(LocalTime.of(6, 0)), sound = softTone, playback = noticeable),
        ),
        constraints = CueConstraints(
            minimumSleepBeforeFirstCue = Duration.ofHours(3),
            minGapBetweenCues = Duration.ofMinutes(30),
            maxCuesPerNight = 3,
            stopBeforeWake = Duration.ofMinutes(10),
        ),
    )

    /**
     * A real option, not a placeholder.
     *
     * Dream recall is the foundation every induction technique depends on, and
     * plenty of people are better served by a few weeks of journalling than by
     * any amount of night-time audio.
     */
    fun journalOnly() = CueProfile(
        name = "No cues",
        description = "Nothing is played. Use the dream journal and reality checks on their own.",
        rules = emptyList(),
        constraints = CueConstraints(maxCuesPerNight = 0),
    )
}
