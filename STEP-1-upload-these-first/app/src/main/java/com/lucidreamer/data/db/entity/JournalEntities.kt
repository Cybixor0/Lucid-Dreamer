// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.data.db.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Journal data is normalised properly, unlike config, because it genuinely is
 * queried: filtered by tag, searched by text, counted by dream sign, and joined
 * against sessions to work out which cue preceded a lucid dream.
 */

enum class Lucidity {
    /** Not lucid. */
    NONE,

    /** Brief or partial awareness. */
    PARTIAL,

    /** Full lucidity. */
    FULL,
}

@Entity(tableName = "dreams", indices = [Index("dreamAtMillis"), Index("sessionId"), Index("favourite")])
data class DreamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val body: String = "",

    /**
     * When the dream happened, which is not when it was written down - people
     * write up a 04:00 dream at 09:00. Statistics use this one.
     */
    val dreamAtMillis: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,

    val lucidity: Lucidity = Lucidity.NONE,
    /** 1-5, or null if the user does not care to rate dreams. */
    val rating: Int? = null,
    /** -2..2, or null. */
    val mood: Int? = null,
    val favourite: Boolean = false,

    /**
     * Link back to the night and the cue that preceded the dream, when the
     * dream was logged during or just after a session. This is what makes
     * "which cue sound actually works for me" answerable rather than a guess.
     */
    val sessionId: Long? = null,
    val precedingCueEventId: Long? = null,

    /** Local audio memo, if the user dictated rather than typed. Never uploaded. */
    val audioNotePath: String? = null,
)

/** Full-text search index over the journal. Local, no network, no indexing service. */
@Fts4(contentEntity = DreamEntity::class)
@Entity(tableName = "dreams_fts")
data class DreamFts(
    val title: String,
    val body: String,
)

enum class TagKind {
    /** A free-form label: "flying", "childhood home". */
    TAG,

    /**
     * A recurring personal dream sign. Tracked separately so the app can surface
     * "your most common dream signs" and offer them as reality-check text,
     * closing the loop between the journal and daytime practice.
     */
    DREAM_SIGN,
}

@Entity(tableName = "tags", indices = [Index(value = ["name", "kind"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: TagKind,
)

@Entity(
    tableName = "dream_tags",
    primaryKeys = ["dreamId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(
            entity = DreamEntity::class,
            parentColumns = ["id"],
            childColumns = ["dreamId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DreamTagCrossRef(
    val dreamId: Long,
    val tagId: Long,
)
