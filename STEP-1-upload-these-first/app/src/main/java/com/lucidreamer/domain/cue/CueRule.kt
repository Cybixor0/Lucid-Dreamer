// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.domain.cue

import com.lucidreamer.core.serialization.DurationSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration

/**
 * Where a cue's audio comes from.
 *
 * Built-in sounds are synthesised on the device at first run rather than shipped
 * as audio files, which keeps the repository entirely source-readable - there
 * are no opaque binaries anywhere in this project.
 */
@Serializable
sealed interface CueSound {

    @Serializable
    @SerialName("builtin")
    data class BuiltIn(val tone: BuiltInTone) : CueSound

    /** A file the user picked, copied into app storage so it cannot vanish at 4am. */
    @Serializable
    @SerialName("file")
    data class File(val relativePath: String, val displayName: String) : CueSound

    /** Something the user recorded in the app, e.g. their own voice. */
    @Serializable
    @SerialName("recording")
    data class Recording(val relativePath: String, val displayName: String) : CueSound

    /**
     * Spoken text.
     *
     * Rendered to an audio file when the session arms, never spoken live: a
     * text-to-speech engine cold-starting at 04:37 is a well-known way to get
     * silence instead of a cue.
     */
    @Serializable
    @SerialName("speech")
    data class Speech(val text: String, val voiceId: String? = null) : CueSound

    /** No audio at all - vibration only, for people who sleep with a partner. */
    @Serializable
    @SerialName("silent")
    data object Silent : CueSound
}

@Serializable
enum class BuiltInTone {
    SOFT_BELL,
    PURE_TONE,
    CHIME,
    LOW_DRONE,
    NOISE_SWELL,
    DOUBLE_BEEP,
}

/**
 * What the app does when headphones are gone at cue time.
 *
 * There is no correct default for everybody: a cue blasting out of a nightstand
 * speaker because earbuds died will wake a partner, and for some users that is
 * much worse than missing the cue.
 */
@Serializable
enum class RouteFallback {
    /** Play through whatever the phone routes to. */
    SPEAKER,

    /** Play, but at a separately calibrated (usually much lower) speaker gain. */
    SPEAKER_REDUCED,

    /** Don't play. Record it as skipped. */
    SKIP,
}

/**
 * Condition on the estimated sleep stage for a cue to play.
 *
 * Only meaningful when sleep sensing is switched on. With sensing off, or
 * whenever the estimate is not confident enough to act on, every gate behaves
 * as [ANY] and the cue plays at its scheduled time - a guess must never be
 * allowed to silently swallow a cue.
 */
@Serializable
enum class StageGate {
    ANY,
    ONLY_LIKELY_REM,
    REM_OR_LIGHT,
    NOT_LIKELY_DEEP,
    AFTER_MIN_SLEEP;

    val label: String
        get() = when (this) {
            ANY -> "Any time"
            ONLY_LIKELY_REM -> "Only when REM looks likely"
            REM_OR_LIGHT -> "Possible REM or light sleep"
            NOT_LIKELY_DEEP -> "Not during likely deep sleep"
            AFTER_MIN_SLEEP -> "Only after the minimum sleep time"
        }

    val description: String
        get() = when (this) {
            ANY ->
                "Plays whenever it is scheduled. No sensing required."

            ONLY_LIKELY_REM ->
                "The strictest option. Cues will be held back and may be skipped, because a " +
                    "phone's idea of \"REM looks likely\" is a weak guess. Expect fewer cues."

            REM_OR_LIGHT ->
                "Avoids deeper sleep but is not fussy beyond that. A reasonable middle ground."

            NOT_LIKELY_DEEP ->
                "Holds the cue back if you seem to be in deeper sleep, then plays it. The " +
                    "gentlest option that still uses sensing."

            AFTER_MIN_SLEEP ->
                "Already enforced when the night is planned, so this behaves like \"any time\"."
        }
}

/**
 * How a single cue should sound.
 *
 * Volume is a per-player gain, not a system volume. Cues always play on the
 * alarm stream (see CuePlaybackService for why that is effectively forced by
 * modern Android), so subtlety has to come from attenuating the cue itself
 * rather than from turning the stream down - and turning the stream down would
 * also quietly sabotage the user's real morning alarm.
 */
@Serializable
data class CuePlayback(
    /** 0.0-1.0 gain applied to the sample. Subtle cues live around 0.02-0.15. */
    val volume: Float = 0.25f,
    @Serializable(with = DurationSerializer::class) val fadeIn: Duration = Duration.ofMillis(600),
    @Serializable(with = DurationSerializer::class) val fadeOut: Duration = Duration.ofMillis(800),
    /** Null plays the sample to its natural end. */
    @Serializable(with = DurationSerializer::class) val maxDuration: Duration? = null,
    val repeatCount: Int = 1,
    @Serializable(with = DurationSerializer::class) val repeatGap: Duration = Duration.ofSeconds(20),
    val vibrate: Boolean = false,
    /** Only play when headphones/earbuds are connected. */
    val requireHeadphones: Boolean = false,
    val routeFallback: RouteFallback = RouteFallback.SPEAKER_REDUCED,
    /**
     * Ask for audio focus before playing. Off means the cue layers over a
     * white-noise app without interrupting it; on means it ducks it briefly.
     */
    val requestAudioFocus: Boolean = true,
)

/**
 * One rule that produces one or more cues per night.
 */
@Serializable
data class CueRule(
    val id: Long = 0,
    val label: String = "",
    val enabled: Boolean = true,
    val timing: Timing,
    val sound: CueSound,
    val playback: CuePlayback = CuePlayback(),
    val stageGate: StageGate = StageGate.ANY,
)

/**
 * Limits applied across every rule after expansion.
 *
 * These exist because rules compose: three reasonable-looking repeating rules
 * can easily produce forty cues, and the user should be able to put a ceiling
 * on the night without unpicking each rule.
 */
@Serializable
data class CueConstraints(
    /** No cues until at least this much sleep has happened. Protects early deep sleep. */
    @Serializable(with = DurationSerializer::class)
    val minimumSleepBeforeFirstCue: Duration = Duration.ofHours(4),

    /** Enforced across all rules, not per rule. */
    @Serializable(with = DurationSerializer::class)
    val minGapBetweenCues: Duration = Duration.ofMinutes(15),

    /** Null means unlimited. */
    val maxCuesPerNight: Int? = 8,

    /** Leave the last stretch before the alarm alone. */
    @Serializable(with = DurationSerializer::class)
    val stopBeforeWake: Duration = Duration.ofMinutes(20),

    /**
     * Habitual night wakings, as offsets from sleep onset. Cues landing inside
     * one are dropped, on the grounds that cueing someone who is already awake
     * is at best wasted and at worst the thing that stops them getting back to
     * sleep.
     */
    val quietPeriods: List<QuietPeriod> = emptyList(),
)

@Serializable
data class QuietPeriod(
    @Serializable(with = DurationSerializer::class) val fromOnset: Duration,
    @Serializable(with = DurationSerializer::class) val length: Duration,
    val label: String = "",
)

/**
 * A named, swappable set of rules - "gentle MILD", "aggressive WILD",
 * "journal only". Swapping profiles is how a user changes technique without
 * rebuilding their whole configuration, and is what the experiment system
 * (a later phase) will A/B.
 */
@Serializable
data class CueProfile(
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val rules: List<CueRule> = emptyList(),
    val constraints: CueConstraints = CueConstraints(),
)
