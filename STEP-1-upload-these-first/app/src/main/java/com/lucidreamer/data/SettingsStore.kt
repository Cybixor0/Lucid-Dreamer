// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lucidreamer.data.db.ColumnJson
import com.lucidreamer.domain.cue.SchedulingMode
import com.lucidreamer.domain.schedule.SleepAnchors
import com.lucidreamer.domain.wbtb.WbtbConfig
import com.lucidreamer.sensing.SensingMode
import com.lucidreamer.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Scalar settings.
 *
 * Structured configuration - schedules, cue profiles, reminder profiles - lives
 * in the database instead, because those are lists the user edits individually.
 * This holds the single values, and the defaults here are the app's opinion
 * about a reasonable starting point, never a constraint: every one of them is
 * reachable and changeable in the settings UI.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
        val onboardingStep = intPreferencesKey("onboarding_step")

        val defaultAnchors = stringPreferencesKey("default_anchors")
        val activeCueProfileId = longPreferencesKey("active_cue_profile_id")
        val requireManualStart = booleanPreferencesKey("require_manual_start")
        val differentAtWeekends = booleanPreferencesKey("different_at_weekends")

        val audioCuesEnabled = booleanPreferencesKey("audio_cues_enabled")
        val preciseAlarms = booleanPreferencesKey("precise_alarms")

        val wbtbEnabled = booleanPreferencesKey("wbtb_enabled")
        val wbtbConfig = stringPreferencesKey("wbtb_config")

        val realityChecksEnabled = booleanPreferencesKey("reality_checks_enabled")

        val sensorEstimationRequested = booleanPreferencesKey("sensor_estimation_requested")
        val sensingMode = stringPreferencesKey("sensing_mode")
        val sensingConsented = booleanPreferencesKey("sensing_consented")
        val schedulingMode = stringPreferencesKey("scheduling_mode")
        val activeExperimentId = longPreferencesKey("active_experiment_id")

        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColour = booleanPreferencesKey("dynamic_colour")
        val nightModeDuringSession = booleanPreferencesKey("night_mode_during_session")

        val statisticsEnabled = booleanPreferencesKey("statistics_enabled")
        val developerMode = booleanPreferencesKey("developer_mode")

        val gainSpeaker = floatPreferencesKey("gain_speaker")
        val gainWired = floatPreferencesKey("gain_wired")
        val gainBluetooth = floatPreferencesKey("gain_bluetooth")

        val lastReportedSessionId = longPreferencesKey("last_reported_session_id")
        val batteryPromptShown = booleanPreferencesKey("battery_prompt_shown")
        val armedReminderCodes = stringPreferencesKey("armed_reminder_codes")
    }

    private val prefs: Flow<Preferences> get() = context.dataStore.data

    // --- Onboarding ---------------------------------------------------------

    val onboardingComplete: Flow<Boolean> = prefs.map { it[Keys.onboardingComplete] ?: false }
    suspend fun setOnboardingComplete(v: Boolean) = put(Keys.onboardingComplete, v)

    /** Remembered so a half-finished, skippable setup can be resumed rather than restarted. */
    val onboardingStep: Flow<Int> = prefs.map { it[Keys.onboardingStep] ?: 0 }
    suspend fun setOnboardingStep(v: Int) = put(Keys.onboardingStep, v)

    // --- Schedule -----------------------------------------------------------

    val defaultAnchors: Flow<SleepAnchors> = prefs.map { p ->
        p[Keys.defaultAnchors]
            ?.let { runCatching { ColumnJson.decodeFromString<SleepAnchors>(it) }.getOrNull() }
            ?: SleepAnchors()
    }

    suspend fun setDefaultAnchors(a: SleepAnchors) = put(Keys.defaultAnchors, ColumnJson.encodeToString(a))

    /**
     * When true, no session ever arms itself - the user says "I'm going to
     * sleep now" each night. Some people want the app to be entirely passive
     * until asked; this is that switch.
     */
    val requireManualStart: Flow<Boolean> = prefs.map { it[Keys.requireManualStart] ?: false }
    suspend fun setRequireManualStart(v: Boolean) = put(Keys.requireManualStart, v)

    val differentAtWeekends: Flow<Boolean> = prefs.map { it[Keys.differentAtWeekends] ?: false }
    suspend fun setDifferentAtWeekends(v: Boolean) = put(Keys.differentAtWeekends, v)

    val activeCueProfileId: Flow<Long> = prefs.map { it[Keys.activeCueProfileId] ?: 0L }
    suspend fun setActiveCueProfileId(v: Long) = put(Keys.activeCueProfileId, v)

    // --- Cues ---------------------------------------------------------------

    val audioCuesEnabled: Flow<Boolean> = prefs.map { it[Keys.audioCuesEnabled] ?: true }
    suspend fun setAudioCuesEnabled(v: Boolean) = put(Keys.audioCuesEnabled, v)

    /**
     * Precise mode uses alarm-clock alarms, which are never throttled or
     * delayed by Doze. The trade-off is that the system shows the next cue in
     * its "next alarm" slot on the lock screen, replacing the user's morning
     * alarm there. Defaulted on, because a cue that does not fire is worse than
     * a cosmetic surprise, but offered honestly.
     */
    val preciseAlarms: Flow<Boolean> = prefs.map { it[Keys.preciseAlarms] ?: true }
    suspend fun setPreciseAlarms(v: Boolean) = put(Keys.preciseAlarms, v)

    // --- WBTB ---------------------------------------------------------------

    val wbtbEnabled: Flow<Boolean> = prefs.map { it[Keys.wbtbEnabled] ?: false }
    suspend fun setWbtbEnabled(v: Boolean) = put(Keys.wbtbEnabled, v)

    val wbtbConfig: Flow<WbtbConfig> = prefs.map { p ->
        p[Keys.wbtbConfig]
            ?.let { runCatching { ColumnJson.decodeFromString<WbtbConfig>(it) }.getOrNull() }
            ?: WbtbConfig()
    }

    suspend fun setWbtbConfig(c: WbtbConfig) = put(Keys.wbtbConfig, ColumnJson.encodeToString(c))

    // --- Reality checks -----------------------------------------------------

    val realityChecksEnabled: Flow<Boolean> = prefs.map { it[Keys.realityChecksEnabled] ?: false }
    suspend fun setRealityChecksEnabled(v: Boolean) = put(Keys.realityChecksEnabled, v)

    // --- Sleep sensing (experimental) ---------------------------------------

    /** Set during onboarding, before the user has seen the sensing screen. */
    val sensorEstimationRequested: Flow<Boolean> = prefs.map { it[Keys.sensorEstimationRequested] ?: false }
    suspend fun setSensorEstimationRequested(v: Boolean) = put(Keys.sensorEstimationRequested, v)

    /**
     * Off by default, and stays off until the user has read the explanation.
     *
     * Nothing here is reliable enough to switch on for somebody.
     */
    val sensingMode: Flow<SensingMode> = prefs.map { p ->
        runCatching { SensingMode.valueOf(p[Keys.sensingMode] ?: SensingMode.OFF.name) }
            .getOrDefault(SensingMode.OFF)
    }

    suspend fun setSensingMode(v: SensingMode) = put(Keys.sensingMode, v.name)

    /**
     * Whether the user has seen and accepted the microphone explanation.
     *
     * Separate from the Android permission on purpose: the system prompt says
     * "allow access to record audio", which tells you nothing about what this
     * app does with it, for how long, or what it keeps. This flag records that
     * they were told the actual answer.
     */
    val sensingConsented: Flow<Boolean> = prefs.map { it[Keys.sensingConsented] ?: false }
    suspend fun setSensingConsented(v: Boolean) = put(Keys.sensingConsented, v)

    /** Whether estimates may nudge cue times. Meaningless without sensing. */
    val schedulingMode: Flow<SchedulingMode> = prefs.map { p ->
        runCatching { SchedulingMode.valueOf(p[Keys.schedulingMode] ?: SchedulingMode.FIXED.name) }
            .getOrDefault(SchedulingMode.FIXED)
    }

    suspend fun setSchedulingMode(v: SchedulingMode) = put(Keys.schedulingMode, v.name)

    // --- Experiments --------------------------------------------------------

    val activeExperimentId: Flow<Long> = prefs.map { it[Keys.activeExperimentId] ?: -1L }
    suspend fun setActiveExperimentId(v: Long) = put(Keys.activeExperimentId, v)

    // --- Appearance ---------------------------------------------------------

    val themeMode: Flow<ThemeMode> = prefs.map { p ->
        runCatching { ThemeMode.valueOf(p[Keys.themeMode] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    suspend fun setThemeMode(v: ThemeMode) = put(Keys.themeMode, v.name)

    val dynamicColour: Flow<Boolean> = prefs.map { it[Keys.dynamicColour] ?: false }
    suspend fun setDynamicColour(v: Boolean) = put(Keys.dynamicColour, v)

    /** Red-shifted, near-black palette on the session screen. On by default. */
    val nightModeDuringSession: Flow<Boolean> = prefs.map { it[Keys.nightModeDuringSession] ?: true }
    suspend fun setNightModeDuringSession(v: Boolean) = put(Keys.nightModeDuringSession, v)

    // --- Privacy ------------------------------------------------------------

    val statisticsEnabled: Flow<Boolean> = prefs.map { it[Keys.statisticsEnabled] ?: true }
    suspend fun setStatisticsEnabled(v: Boolean) = put(Keys.statisticsEnabled, v)

    val developerMode: Flow<Boolean> = prefs.map { it[Keys.developerMode] ?: false }
    suspend fun setDeveloperMode(v: Boolean) = put(Keys.developerMode, v)

    // --- Per-route gain -----------------------------------------------------

    /**
     * Calibrated separately per output, because a gain that is barely audible
     * through a phone speaker is uncomfortably loud in earbuds.
     */
    val gainSpeaker: Flow<Float> = prefs.map { it[Keys.gainSpeaker] ?: 0.35f }
    val gainWired: Flow<Float> = prefs.map { it[Keys.gainWired] ?: 0.12f }
    val gainBluetooth: Flow<Float> = prefs.map { it[Keys.gainBluetooth] ?: 0.12f }

    suspend fun setGainSpeaker(v: Float) = put(Keys.gainSpeaker, v)
    suspend fun setGainWired(v: Float) = put(Keys.gainWired, v)
    suspend fun setGainBluetooth(v: Float) = put(Keys.gainBluetooth, v)

    // --- Housekeeping -------------------------------------------------------

    val lastReportedSessionId: Flow<Long> = prefs.map { it[Keys.lastReportedSessionId] ?: -1L }
    suspend fun setLastReportedSessionId(v: Long) = put(Keys.lastReportedSessionId, v)

    val batteryPromptShown: Flow<Boolean> = prefs.map { it[Keys.batteryPromptShown] ?: false }
    suspend fun setBatteryPromptShown(v: Boolean) = put(Keys.batteryPromptShown, v)

    /**
     * Alarm request codes currently armed for reality-check reminders.
     *
     * Android offers no way to enumerate an app's pending intents, so the codes
     * that were armed have to be remembered in order to cancel exactly those.
     * The alternative - sweeping every code the scheme could possibly produce -
     * costs thousands of binder calls to cancel a handful of alarms.
     */
    val armedReminderCodes: Flow<List<Int>> = prefs.map { p ->
        p[Keys.armedReminderCodes]
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()
    }

    suspend fun setArmedReminderCodes(codes: List<Int>) =
        put(Keys.armedReminderCodes, codes.joinToString(","))

    /** Used by Settings -> Privacy -> Erase everything. */
    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}
