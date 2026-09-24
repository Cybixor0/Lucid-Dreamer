// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.domain.schedule

import com.lucidreamer.core.serialization.LocalDateSerializer
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Which nights a [ScheduleProfile] applies to.
 *
 * Deliberately just a set of days rather than an enum of "weekday/weekend",
 * because those words mean different things to a shift worker, and plenty of
 * people have a different schedule on exactly one day of the week. The presets
 * are conveniences over the same underlying set.
 */
@Serializable
data class DaySelector(
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
) {
    fun matches(date: LocalDate): Boolean = date.dayOfWeek in days

    companion object {
        val EveryDay = DaySelector(DayOfWeek.entries.toSet())
        val Weeknights = DaySelector(
            setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
        )

        /**
         * Friday and Saturday nights.
         *
         * Note this is nights-you-go-to-bed, not calendar weekend: the lie-in
         * happens on Saturday and Sunday *morning*, which means the profile has
         * to attach to Friday and Saturday evening.
         */
        val WeekendNights = DaySelector(setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY))
    }
}

/**
 * A named sleep schedule that applies to some set of nights.
 *
 * Several may be active at once; the highest [priority] matching profile wins.
 * A user who wants a completely manual schedule can simply create seven
 * profiles, one per day, and the same resolution logic serves them.
 */
@Serializable
data class ScheduleProfile(
    val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val selector: DaySelector = DaySelector.EveryDay,
    val anchors: SleepAnchors = SleepAnchors(),
    /** Higher wins. The default profile sits at 0; specific overrides go above it. */
    val priority: Int = 0,
)

/**
 * A one-off change for a single night: "tonight only".
 *
 * Any field left null falls through to the profile that would otherwise apply,
 * so "tonight I'm going to bed two hours late but everything else is normal" is
 * expressible without duplicating a whole profile.
 */
@Serializable
data class NightOverride(
    @Serializable(with = LocalDateSerializer::class)
    val night: LocalDate,
    val anchors: SleepAnchors? = null,
    val cueProfileId: Long? = null,
    /** Set false to skip cueing entirely for one night without disabling anything. */
    val enabled: Boolean = true,
    val note: String = "",
)

/**
 * Picks the anchors that apply to a given night.
 *
 * Resolution order, highest priority first:
 *  1. a [NightOverride] for exactly this date
 *  2. the highest-priority enabled [ScheduleProfile] whose selector matches
 *  3. [fallback]
 *
 * Kept as a pure function so the precedence rules are directly testable without
 * a database, a clock or an Android runtime.
 */
object ScheduleResolver {

    fun resolveAnchors(
        night: LocalDate,
        profiles: List<ScheduleProfile>,
        overrides: List<NightOverride>,
        fallback: SleepAnchors = SleepAnchors(),
    ): AnchorResolution {
        val override = overrides.firstOrNull { it.night == night }
        if (override?.anchors != null) {
            return AnchorResolution(override.anchors, ResolutionSource.OVERRIDE, "One-off override for $night")
        }

        val profile = profiles
            .filter { it.enabled && it.selector.matches(night) }
            .maxByOrNull { it.priority }

        return if (profile != null) {
            AnchorResolution(
                profile.anchors,
                ResolutionSource.PROFILE,
                "Schedule \"${profile.name}\" (matches ${night.dayOfWeek.name.lowercase()})",
            )
        } else {
            AnchorResolution(fallback, ResolutionSource.FALLBACK, "Default schedule")
        }
    }
}

enum class ResolutionSource { OVERRIDE, PROFILE, FALLBACK }

/**
 * The chosen anchors plus a human-readable [explanation] of why they were
 * chosen. The explanation is shown in the UI ("why is my cue here?") and
 * recorded in the diagnostic log, so scheduling is never a black box.
 */
data class AnchorResolution(
    val anchors: SleepAnchors,
    val source: ResolutionSource,
    val explanation: String,
)
