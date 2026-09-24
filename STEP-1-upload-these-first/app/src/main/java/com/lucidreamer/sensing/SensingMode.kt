// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.sensing

import kotlinx.serialization.Serializable

/**
 * How much the app tries to observe while you sleep.
 *
 * Every mode above [OFF] is experimental and is labelled that way in the UI.
 * None of them measures sleep stages; see docs/SLEEP_SCIENCE.md.
 */
@Serializable
enum class SensingMode {
    /**
     * No sensors at all. Cues fire on the schedule you set.
     *
     * The default, and a perfectly good way to use the app - every technique
     * this app supports predates any of the sensing below.
     */
    OFF,

    /**
     * Accelerometer only.
     *
     * Estimates sleep versus wake from movement, which is the one thing here
     * with real research behind it. Carries no information about REM. Needs no
     * permission and uses very little battery.
     */
    MOTION,

    /**
     * Microphone only.
     *
     * Listens for movement sounds and slow periodicity that may correspond to
     * breathing. Weak and easily defeated by a fan, traffic or a partner.
     * Requires microphone permission and uses noticeably more battery.
     */
    MICROPHONE,

    /**
     * Accelerometer and microphone together.
     *
     * The most information available, which is still not very much. Highest
     * battery cost.
     */
    COMBINED;

    val usesMotion: Boolean get() = this == MOTION || this == COMBINED
    val usesMicrophone: Boolean get() = this == MICROPHONE || this == COMBINED

    val label: String
        get() = when (this) {
            OFF -> "Off"
            MOTION -> "Movement only"
            MICROPHONE -> "Microphone only"
            COMBINED -> "Movement and microphone"
        }

    /**
     * What this mode can and cannot do, in plain language.
     *
     * Shown wherever the mode is selectable. Every one of these leads with a
     * limitation rather than a capability, on purpose.
     */
    val honestDescription: String
        get() = when (this) {
            OFF ->
                "Nothing is measured. Cues play at the times you configured. This is the default " +
                    "and works perfectly well."

            MOTION ->
                "Estimates whether you are asleep or awake from how much you move. This is the " +
                    "only method here with real research behind it, and even so it cannot tell " +
                    "REM from other sleep - nothing about movement carries that information. " +
                    "Lying still while awake usually reads as sleep. No permission needed, " +
                    "negligible battery cost."

            MICROPHONE ->
                "Listens for movement and for slow rhythms that may be your breathing. Breathing " +
                    "does become more irregular during REM, so this is a genuine but weak clue - " +
                    "and it fails entirely with a fan, traffic, or someone else in the room. " +
                    "Audio is analysed in short windows and discarded immediately; nothing is " +
                    "recorded or stored. Uses more battery."

            COMBINED ->
                "Both of the above. Movement is the more reliable signal; the microphone adds a " +
                    "weak hint about REM. Together they produce a better guess than the calendar " +
                    "alone - still a guess, with a confidence value attached. Highest battery cost."
        }

    val requiresMicrophonePermission: Boolean get() = usesMicrophone
}
