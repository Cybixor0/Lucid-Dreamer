// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucidreamer.AppContainer
import com.lucidreamer.data.backup.BackupCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

class PrivacyViewModel(private val container: AppContainer) : ViewModel() {

    /**
     * Writes a backup into the app's shared-files directory.
     *
     * Kept deliberately simple: a plain, readable JSON file the user can copy,
     * sync or edit however they like. The app does not move it anywhere.
     */
    fun export(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val json = BackupCodec(container).export()
                    val dir = File(container.appContext.getExternalFilesDir(null), "exports")
                    dir.mkdirs()
                    val file = File(dir, "lucid-dreamer-${LocalDate.now()}.json")
                    file.writeText(json)
                    file.absolutePath
                }
            }
            onResult(
                result.fold(
                    onSuccess = { "Saved to $it" },
                    onFailure = { "Export failed: ${it.message}" },
                ),
            )
        }
    }

    fun eraseEverything() {
        viewModelScope.launch {
            runCatching {
                container.sessionManager.activeSessionOrNull()?.let {
                    container.sessionManager.finish(it.id, com.lucidreamer.data.db.entity.SessionState.STOPPED)
                }
                container.alarmScheduler.cancelWatchdog()
                container.alarmScheduler.cancelWbtb()
                container.alarmScheduler.cancelSessionAutoStart()
                container.reminderScheduler.cancelAll()

                container.db.clearAllTables()
                container.settings.clearAll()
            }
        }
    }
}
