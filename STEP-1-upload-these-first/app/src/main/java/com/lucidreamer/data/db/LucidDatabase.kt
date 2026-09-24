// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.lucidreamer.data.db.dao.CueEventDao
import com.lucidreamer.data.db.dao.CueProfileDao
import com.lucidreamer.data.db.dao.DreamDao
import com.lucidreamer.data.db.dao.EventLogDao
import com.lucidreamer.data.db.dao.ExperimentDao
import com.lucidreamer.data.db.dao.HeartbeatDao
import com.lucidreamer.data.db.dao.NightOverrideDao
import com.lucidreamer.data.db.dao.ReminderDao
import com.lucidreamer.data.db.dao.ScheduleProfileDao
import com.lucidreamer.data.db.dao.SessionDao
import com.lucidreamer.data.db.dao.StageSampleDao
import com.lucidreamer.data.db.dao.TagDao
import com.lucidreamer.data.db.entity.CueEventEntity
import com.lucidreamer.data.db.entity.CueOutcome
import com.lucidreamer.data.db.entity.CueProfileEntity
import com.lucidreamer.data.db.entity.CueState
import com.lucidreamer.data.db.entity.DreamEntity
import com.lucidreamer.data.db.entity.DreamFts
import com.lucidreamer.data.db.entity.DreamTagCrossRef
import com.lucidreamer.data.db.entity.EventLogEntity
import com.lucidreamer.data.db.entity.ExperimentEntity
import com.lucidreamer.data.db.entity.ExperimentRunEntity
import com.lucidreamer.data.db.entity.HeartbeatEntity
import com.lucidreamer.data.db.entity.HeartbeatSource
import com.lucidreamer.data.db.entity.LogLevel
import com.lucidreamer.data.db.entity.Lucidity
import com.lucidreamer.data.db.entity.NightOutcome
import com.lucidreamer.data.db.entity.NightOverrideEntity
import com.lucidreamer.data.db.entity.RealityCheckLogEntity
import com.lucidreamer.data.db.entity.ReminderMode
import com.lucidreamer.data.db.entity.ReminderProfileEntity
import com.lucidreamer.data.db.entity.ReminderStyle
import com.lucidreamer.data.db.entity.ScheduleProfileEntity
import com.lucidreamer.data.db.entity.SessionEntity
import com.lucidreamer.data.db.entity.SessionState
import com.lucidreamer.data.db.entity.StageSampleEntity
import com.lucidreamer.data.db.entity.TagEntity
import com.lucidreamer.data.db.entity.TagKind

/**
 * Enums are stored as their names rather than ordinals.
 *
 * Ordinals silently corrupt every existing row the moment somebody inserts a
 * value in the middle of an enum, which is exactly the kind of bug that would
 * show up as "my cues are all marked FOCUS_DENIED now" months later.
 */
class Converters {
    @TypeConverter fun sessionStateOut(v: SessionState) = v.name
    @TypeConverter fun sessionStateIn(v: String) = SessionState.valueOf(v)

    @TypeConverter fun cueStateOut(v: CueState) = v.name
    @TypeConverter fun cueStateIn(v: String) = CueState.valueOf(v)

    @TypeConverter fun cueOutcomeOut(v: CueOutcome) = v.name
    @TypeConverter fun cueOutcomeIn(v: String) = CueOutcome.valueOf(v)

    @TypeConverter fun heartbeatSourceOut(v: HeartbeatSource) = v.name
    @TypeConverter fun heartbeatSourceIn(v: String) = HeartbeatSource.valueOf(v)

    @TypeConverter fun logLevelOut(v: LogLevel) = v.name
    @TypeConverter fun logLevelIn(v: String) = LogLevel.valueOf(v)

    @TypeConverter fun lucidityOut(v: Lucidity) = v.name
    @TypeConverter fun lucidityIn(v: String) = Lucidity.valueOf(v)

    @TypeConverter fun tagKindOut(v: TagKind) = v.name
    @TypeConverter fun tagKindIn(v: String) = TagKind.valueOf(v)

    @TypeConverter fun reminderModeOut(v: ReminderMode) = v.name
    @TypeConverter fun reminderModeIn(v: String) = ReminderMode.valueOf(v)

    @TypeConverter fun reminderStyleOut(v: ReminderStyle) = v.name
    @TypeConverter fun reminderStyleIn(v: String) = ReminderStyle.valueOf(v)

    @TypeConverter fun nightOutcomeOut(v: NightOutcome) = v.name
    @TypeConverter fun nightOutcomeIn(v: String) = NightOutcome.valueOf(v)
}

@Database(
    entities = [
        ScheduleProfileEntity::class,
        NightOverrideEntity::class,
        CueProfileEntity::class,
        SessionEntity::class,
        CueEventEntity::class,
        HeartbeatEntity::class,
        EventLogEntity::class,
        DreamEntity::class,
        DreamFts::class,
        TagEntity::class,
        DreamTagCrossRef::class,
        ReminderProfileEntity::class,
        RealityCheckLogEntity::class,
        ExperimentEntity::class,
        ExperimentRunEntity::class,
        StageSampleEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LucidDatabase : RoomDatabase() {

    abstract fun scheduleProfileDao(): ScheduleProfileDao
    abstract fun nightOverrideDao(): NightOverrideDao
    abstract fun cueProfileDao(): CueProfileDao
    abstract fun sessionDao(): SessionDao
    abstract fun cueEventDao(): CueEventDao
    abstract fun heartbeatDao(): HeartbeatDao
    abstract fun eventLogDao(): EventLogDao
    abstract fun dreamDao(): DreamDao
    abstract fun tagDao(): TagDao
    abstract fun reminderDao(): ReminderDao
    abstract fun experimentDao(): ExperimentDao
    abstract fun stageSampleDao(): StageSampleDao

    companion object {
        private const val NAME = "lucid-dreamer.db"

        /**
         * Adds the sensing and experiment tables.
         *
         * Purely additive - no existing table is touched, so an upgrade cannot
         * lose a dream. Written by hand rather than relying on a destructive
         * fallback, because the alternative to a correct migration here is
         * silently deleting someone's journal.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `experiments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `notes` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `active` INTEGER NOT NULL,
                        `armACueProfileId` INTEGER NOT NULL,
                        `armALabel` TEXT NOT NULL,
                        `armBCueProfileId` INTEGER NOT NULL,
                        `armBLabel` TEXT NOT NULL,
                        `alternateRandomly` INTEGER NOT NULL,
                        `assignmentSeed` INTEGER NOT NULL,
                        `endedAtMillis` INTEGER
                    )
                    """.trimIndent(),
                )

                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `experiment_runs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `experimentId` INTEGER NOT NULL,
                        `sessionId` INTEGER,
                        `nightEpochDay` INTEGER NOT NULL,
                        `armA` INTEGER NOT NULL,
                        `cueProfileId` INTEGER NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `cuesPlayed` INTEGER NOT NULL,
                        `recordedAtMillis` INTEGER
                    )
                    """.trimIndent(),
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_experiment_runs_experimentId` ON `experiment_runs` (`experimentId`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_experiment_runs_sessionId` ON `experiment_runs` (`sessionId`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_experiment_runs_nightEpochDay` ON `experiment_runs` (`nightEpochDay`)")

                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `stage_samples` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `atMillis` INTEGER NOT NULL,
                        `awake` REAL NOT NULL,
                        `light` REAL NOT NULL,
                        `deep` REAL NOT NULL,
                        `rem` REAL NOT NULL,
                        `confidence` REAL NOT NULL,
                        `basis` TEXT NOT NULL,
                        `breathsPerMinute` REAL
                    )
                    """.trimIndent(),
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_stage_samples_sessionId` ON `stage_samples` (`sessionId`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_stage_samples_atMillis` ON `stage_samples` (`atMillis`)")

                // Adaptive scheduling needs the gate and the original time on
                // the cue row itself. Defaults keep every existing row valid.
                connection.execSQL("ALTER TABLE `cue_events` ADD COLUMN `stageGate` TEXT NOT NULL DEFAULT 'ANY'")
                connection.execSQL("ALTER TABLE `cue_events` ADD COLUMN `originalScheduledAtMillis` INTEGER")
            }
        }

        @Volatile private var instance: LucidDatabase? = null

        fun get(context: Context): LucidDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): LucidDatabase =
            Room.databaseBuilder(context, LucidDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                // No fallbackToDestructiveMigration. A dream journal is
                // irreplaceable; a failed upgrade must be a loud bug report,
                // never a silently emptied database.
                .build()
    }
}
