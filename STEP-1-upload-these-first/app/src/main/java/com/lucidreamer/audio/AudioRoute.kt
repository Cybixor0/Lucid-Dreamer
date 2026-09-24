// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.audio

import android.app.NotificationManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Where the cue is going to come out.
 *
 * Gain is stored per route type, because the same number that is a whisper
 * through a phone speaker is a shout in in-ear headphones. Getting this wrong
 * once - earbuds die overnight, the cue falls back to the speaker at earbud
 * gain, and a sleeping partner is blasted awake at 4am - is the kind of failure
 * that gets an app uninstalled, so it is treated as a first-class concern.
 */
enum class RouteType {
    SPEAKER,
    WIRED,
    BLUETOOTH,
    USB,
    OTHER;

    val isHeadphoneLike: Boolean get() = this == WIRED || this == BLUETOOTH || this == USB

    val label: String
        get() = when (this) {
            SPEAKER -> "Phone speaker"
            WIRED -> "Wired headphones"
            BLUETOOTH -> "Bluetooth"
            USB -> "USB audio"
            OTHER -> "Other output"
        }
}

data class AudioRouteStatus(
    val route: RouteType,
    val deviceName: String,
    /** DND filter, from [NotificationManager.getCurrentInterruptionFilter]. */
    val interruptionFilter: Int,
    val alarmVolume: Int,
    val maxAlarmVolume: Int,
) {
    /**
     * True when Do Not Disturb is configured in a way that will silence even an
     * alarm-stream cue.
     *
     * Worth surfacing at bedtime rather than discovering at 4am: the failure is
     * completely silent otherwise, and the user has no way to tell the
     * difference between "the app is broken" and "I left DND on total silence".
     */
    val dndMayBlockAlarms: Boolean
        get() = interruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE

    val alarmVolumeIsZero: Boolean get() = alarmVolume == 0
}

object AudioRoutes {

    fun current(context: Context): AudioRouteStatus {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val devices = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrDefault(emptyArray())

        // Pick the most "connected on purpose" device rather than the first one
        // listed: the speaker is always present, so order alone is misleading.
        val best = devices
            .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY }
            .maxByOrNull { priority(it.type) }

        val route = best?.let { typeOf(it.type) } ?: RouteType.SPEAKER
        val name = best?.productName?.toString()?.takeIf { it.isNotBlank() } ?: route.label

        return AudioRouteStatus(
            route = route,
            deviceName = name,
            interruptionFilter = runCatching { nm.currentInterruptionFilter }
                .getOrDefault(NotificationManager.INTERRUPTION_FILTER_ALL),
            alarmVolume = runCatching { am.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrDefault(0),
            maxAlarmVolume = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_ALARM) }.getOrDefault(1),
        )
    }

    private fun priority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        -> 40

        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        -> 30

        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        -> 20

        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1
        else -> 5
    }

    private fun typeOf(type: Int): RouteType = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        -> RouteType.BLUETOOTH

        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        -> RouteType.WIRED

        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        -> RouteType.USB

        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> RouteType.SPEAKER
        else -> RouteType.OTHER
    }
}
