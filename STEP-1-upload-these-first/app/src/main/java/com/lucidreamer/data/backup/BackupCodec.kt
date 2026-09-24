// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.data.backup

import com.lucidreamer.AppContainer
import com.lucidreamer.data.db.ConfigJson
import com.lucidreamer.data.db.entity.CueProfileEntity
import com.lucidreamer.data.db.entity.Lucidity
import com.lucidreamer.domain.cue.CueProfile
import com.lucidreamer.domain.schedule.ScheduleProfile
import com.lucidreamer.domain.schedule.SleepAnchors
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Manual, local, file-based backup.
 *
 * Plain readable JSON rather than a database dump, because the point is that
 * the user owns this data: they should be able to open the file, read their own
 * dreams in it, edit it, and move it wherever they like. An opaque binary blob
 * would be smaller and would quietly make the data the app's rather than theirs.
 *
 * There is no cloud component and nothing is uploaded. The app writes a file
 * and stops.
 */
class BackupCodec(private val container: AppContainer) {

    @Serializable
    data class Backup(
        val formatVersion: Int = FORMAT_VERSION,
        val exportedAtMillis: Long,
        val appVersion: String,
        val defaultAnchors: SleepAnchors,
        val scheduleProfiles: List<ScheduleProfile>,
        val cueProfiles: List<CueProfile>,
        val dreams: List<DreamBackup>,
    )

    @Serializable
    data class DreamBackup(
        val title: String,
        val body: String,
        val dreamAtMillis: Long,
        val lucidity: String,
        val rating: Int?,
        val mood: Int?,
        val favourite: Boolean,
        val tags: List<String>,
        val dreamSigns: List<String>,
    )

    suspend fun export(): String {
        val db = container.db
        val dreams = db.dreamDao().allForExport().map { dream ->
            val tags = db.tagDao().tagsFor(dream.id)
            DreamBackup(
                title = dream.title,
                body = dream.body,
                dreamAtMillis = dream.dreamAtMillis,
                lucidity = dream.lucidity.name,
                rating = dream.rating,
                mood = dream.mood,
                favourite = dream.favourite,
                tags = tags.filter { it.kind == com.lucidreamer.data.db.entity.TagKind.TAG }.map { it.name },
                dreamSigns = tags.filter { it.kind == com.lucidreamer.data.db.entity.TagKind.DREAM_SIGN }.map { it.name },
            )
        }

        return ConfigJson.encodeToString(
            Backup(
                exportedAtMillis = System.currentTimeMillis(),
                appVersion = com.lucidreamer.BuildConfig.VERSION_NAME,
                defaultAnchors = container.settings.defaultAnchors.first(),
                scheduleProfiles = db.scheduleProfileDao().all().map { it.toDomain() },
                cueProfiles = db.cueProfileDao().all().map { it.toDomain() },
                dreams = dreams,
            ),
        )
    }

    /**
     * Restores a backup, adding to what is already there rather than replacing it.
     *
     * Merging rather than overwriting is the safer default: importing on a
     * phone that already has a journal should never silently destroy it. The
     * user can erase first if they want a clean restore.
     */
    suspend fun import(json: String): Result<ImportSummary> = runCatching {
        val backup = ConfigJson.decodeFromString<Backup>(json)
        val db = container.db

        container.settings.setDefaultAnchors(backup.defaultAnchors)

        backup.scheduleProfiles.forEach {
            db.scheduleProfileDao().upsert(
                com.lucidreamer.data.db.entity.ScheduleProfileEntity.from(it.copy(id = 0)),
            )
        }

        backup.cueProfiles.forEach {
            db.cueProfileDao().upsert(CueProfileEntity.from(it.copy(id = 0), isPreset = false))
        }

        var importedDreams = 0
        backup.dreams.forEach { d ->
            container.journalRepository.save(
                dream = com.lucidreamer.data.db.entity.DreamEntity(
                    title = d.title,
                    body = d.body,
                    dreamAtMillis = d.dreamAtMillis,
                    createdAtMillis = d.dreamAtMillis,
                    updatedAtMillis = System.currentTimeMillis(),
                    lucidity = runCatching { Lucidity.valueOf(d.lucidity) }.getOrDefault(Lucidity.NONE),
                    rating = d.rating,
                    mood = d.mood,
                    favourite = d.favourite,
                ),
                tagNames = d.tags,
                dreamSigns = d.dreamSigns,
            )
            importedDreams++
        }

        ImportSummary(
            dreams = importedDreams,
            cueProfiles = backup.cueProfiles.size,
            scheduleProfiles = backup.scheduleProfiles.size,
        )
    }

    data class ImportSummary(val dreams: Int, val cueProfiles: Int, val scheduleProfiles: Int)

    companion object {
        const val FORMAT_VERSION = 1
    }
}
