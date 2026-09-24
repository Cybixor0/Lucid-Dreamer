// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.core

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Everything the app can find out about whether it will actually survive the
 * night, and what to do about each problem.
 *
 * Most of this cannot be fixed programmatically - it needs the user to change a
 * setting - so the job here is to detect honestly and explain clearly. An app
 * that fails silently overnight is indistinguishable from a broken one.
 */
class DeviceStatus(private val context: Context) {

    val isSamsung: Boolean get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    fun isIgnoringBatteryOptimisations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }

    /**
     * Whether exact alarms can be scheduled.
     *
     * The app declares `USE_EXACT_ALARM`, which is auto-granted and not
     * user-revocable on API 33+, so this should be true there. It is still
     * checked rather than assumed, because on API 31-32 the app relies on
     * `SCHEDULE_EXACT_ALARM`, which the user *can* revoke.
     */
    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * App Standby bucket. Lower is better; 10 is ACTIVE, 45 is RESTRICTED.
     *
     * RESTRICTED would cap the app at one alarm per day, which would be fatal.
     * Holding `USE_EXACT_ALARM` exempts the app from that bucket, so seeing 45
     * here would indicate something has gone badly wrong and is worth showing.
     *
     * @return the bucket, or -1 below API 28 where the concept does not exist.
     */
    fun standbyBucket(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return -1
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return -1
        // Querying our own bucket needs no PACKAGE_USAGE_STATS permission.
        return runCatching { usm.appStandbyBucket }.getOrDefault(-1)
    }

    fun standbyBucketLabel(): String = when (val b = standbyBucket()) {
        -1 -> "Not applicable"
        10 -> "Active"
        20 -> "Working set"
        30 -> "Frequent"
        40 -> "Rare"
        45 -> "Restricted"
        else -> "Unknown ($b)"
    }

    /** True if the user has set "Restrict background usage" for this app. */
    fun isBackgroundRestricted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return runCatching { am.isBackgroundRestricted }.getOrDefault(false)
    }

    fun interruptionFilter(): Int {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return runCatching { nm.currentInterruptionFilter }
            .getOrDefault(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    // -----------------------------------------------------------------------
    // Intents that take the user to the right settings page
    //
    // Every one of these is best-effort and resolve-guarded. Manufacturer deep
    // links in particular are undocumented, vary by firmware version and can
    // disappear entirely, so nothing may ever depend on one succeeding.
    // -----------------------------------------------------------------------

    fun batteryOptimisationIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    fun exactAlarmSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        } else {
            null
        }

    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun notificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /**
     * Samsung's "never sleeping apps" list.
     *
     * This is the one deep link Samsung actually documents. One UI puts unused
     * apps to sleep after roughly three days by default, and into "deep sleep"
     * after about sixteen - the latter is effectively a force-stop that the app
     * cannot recover from on its own. Adding the app to this list is the single
     * most effective fix for overnight reliability on a Galaxy.
     *
     * `activity_type = 2` is the documented value. Other values circulate in
     * forums; they are not shipped here.
     */
    fun samsungNeverSleepingAppsIntent(): Intent? {
        if (!isSamsung) return null
        val intent = Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")
            .setPackage("com.samsung.android.lool")
            .putExtra("activity_type", 2)
        return if (resolves(intent)) intent else null
    }

    fun resolves(intent: Intent): Boolean =
        runCatching { intent.resolveActivity(context.packageManager) != null }.getOrDefault(false)

    /**
     * Best available destination for fixing manufacturer battery restrictions,
     * falling back through progressively more generic pages so the button is
     * never dead.
     */
    fun bestBatterySettingsIntent(): Intent {
        samsungNeverSleepingAppsIntent()?.let { return it }
        val optimisation = batteryOptimisationIntent()
        if (resolves(optimisation)) return optimisation
        return appDetailsIntent()
    }
}
