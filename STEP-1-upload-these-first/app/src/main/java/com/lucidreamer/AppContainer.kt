// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer

import android.content.Context
import com.lucidreamer.audio.CueAssetStore
import com.lucidreamer.audio.CuePlayer
import com.lucidreamer.core.DeviceStatus
import com.lucidreamer.core.EventLog
import com.lucidreamer.data.SettingsStore
import com.lucidreamer.data.db.LucidDatabase
import com.lucidreamer.data.repo.JournalRepository
import com.lucidreamer.data.repo.Seeder
import com.lucidreamer.data.repo.SessionRepository
import com.lucidreamer.data.repo.StatsRepository
import com.lucidreamer.domain.experiment.ExperimentManager
import com.lucidreamer.sensing.SleepSensingController
import com.lucidreamer.service.AlarmScheduler
import com.lucidreamer.service.ReminderScheduler
import com.lucidreamer.service.SessionManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hand-rolled dependency container.
 *
 * A service locator rather than a DI framework: for a project this size it is
 * less machinery, no annotation processing, and one fewer thing that can break
 * a build. Everything is lazy so that opening the app does not construct
 * subsystems a given user never touches - somebody who only wants the dream
 * journal should never build the audio engine.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    /**
     * Long-lived scope for work that must outlive any particular screen, such
     * as writing a log entry or refreshing the session notification.
     *
     * [SupervisorJob] so one failure cannot take down the others, plus a
     * handler so a stray exception is recorded rather than crashing the app -
     * a background failure at 3am must never kill the process that is running
     * the night.
     */
    val applicationScope: CoroutineScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            CoroutineExceptionHandler { _, e ->
                runCatching { android.util.Log.e("AppContainer", "Unhandled background failure", e) }
            },
    )

    val db: LucidDatabase by lazy { LucidDatabase.get(appContext) }

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val eventLog: EventLog by lazy { EventLog(db.eventLogDao(), applicationScope) }

    val deviceStatus: DeviceStatus by lazy { DeviceStatus(appContext) }

    val cueAssets: CueAssetStore by lazy { CueAssetStore(appContext) }

    val cuePlayer: CuePlayer by lazy { CuePlayer(appContext) }

    val alarmScheduler: AlarmScheduler by lazy { AlarmScheduler(appContext, eventLog, deviceStatus) }

    val reminderScheduler: ReminderScheduler by lazy { ReminderScheduler(appContext, db, settings, eventLog) }

    val sessionManager: SessionManager by lazy {
        SessionManager(appContext, db, settings, alarmScheduler, cueAssets, eventLog, experimentManager)
    }

    /**
     * Shared so the playback service can read the current estimate that the
     * session service is producing, without either having to own the other.
     */
    val sensingController: SleepSensingController by lazy {
        SleepSensingController(appContext, eventLog, deviceStatus)
    }

    val experimentManager: ExperimentManager by lazy {
        ExperimentManager(db, settings, eventLog)
    }

    val sessionRepository: SessionRepository by lazy { SessionRepository(db, settings, deviceStatus) }

    val journalRepository: JournalRepository by lazy { JournalRepository(db) }

    val statsRepository: StatsRepository by lazy { StatsRepository(db, settings) }

    val seeder: Seeder by lazy { Seeder(db, settings) }
}

/**
 * Fire-and-forget launch that can never crash the process.
 *
 * Used from notification callbacks and receivers, where an unhandled exception
 * during the night would be far worse than a missed UI refresh.
 */
fun CoroutineScope.launchCatching(block: suspend () -> Unit): Job = launch {
    runCatching { block() }
        .onFailure { android.util.Log.e("AppContainer", "Background task failed", it) }
}
