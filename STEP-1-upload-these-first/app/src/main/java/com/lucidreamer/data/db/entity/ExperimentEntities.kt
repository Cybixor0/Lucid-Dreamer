// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * How a night turned out, in the user's own judgement.
 *
 * Deliberately not just "lucid or not". False awakenings and "woke up" are
 * outcomes worth distinguishing: a configuration that reliably wakes you is a
 * failure even if it occasionally produces a lucid dream, and knowing that
 * requires recording it separately.
 */
enum class NightOutcome {
    NOT_RECORDED,
    NO_RECALL,
    NORMAL_DREAM,
    LUCID_DREAM,
    FALSE_AWAKENING,
    WOKE_UP,
    OTHER;

    val label: String
        get() = when (this) {
            NOT_RECORDED -> "Not recorded"
            NO_RECALL -> "No dream recalled"
            NORMAL_DREAM -> "Normal dream"
            LUCID_DREAM -> "Lucid dream"
            FALSE_AWAKENING -> "False awakening"
            WOKE_UP -> "Woke up"
            OTHER -> "Other"
        }

    /** Whether this counts as a success when comparing arms. */
    val isSuccess: Boolean get() = this == LUCID_DREAM

    /** Whether the night cost sleep, which is worth weighing against success. */
    val disturbedSleep: Boolean get() = this == WOKE_UP
}

/**
 * A personal A/B comparison between two cue configurations.
 *
 * The honest framing: this is a self-experiment with a sample size of one, no
 * blinding, no control for expectation, and an outcome the participant scores
 * themselves. It cannot establish that one configuration is better than
 * another. What it *can* do is stop you fooling yourself about what you
 * actually tried and what actually happened, which is genuinely useful and is
 * the most any app in this space can offer.
 */
@Entity(tableName = "experiments")
data class ExperimentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val notes: String = "",
    val createdAtMillis: Long,
    val active: Boolean = true,

    val armACueProfileId: Long,
    val armALabel: String = "A",
    val armBCueProfileId: Long,
    val armBLabel: String = "B",

    /**
     * How arms are chosen each night.
     *
     * Alternating is predictable and easy to reason about; random avoids
     * accidentally aligning an arm with, say, every weekend.
     */
    val alternateRandomly: Boolean = true,
    /** Fixed once, so assignment is reproducible after a reinstall from backup. */
    val assignmentSeed: Long,

    val endedAtMillis: Long? = null,
)

/** One night belonging to an experiment. */
@Entity(
    tableName = "experiment_runs",
    indices = [Index("experimentId"), Index("sessionId"), Index("nightEpochDay")],
)
data class ExperimentRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val experimentId: Long,
    val sessionId: Long?,
    val nightEpochDay: Long,
    /** true = arm A, false = arm B. */
    val armA: Boolean,
    val cueProfileId: Long,
    val outcome: NightOutcome = NightOutcome.NOT_RECORDED,
    val note: String = "",
    val cuesPlayed: Int = 0,
    val recordedAtMillis: Long? = null,
)

/**
 * A stored sleep-stage estimate.
 *
 * Kept per epoch so the night can be redrawn in the morning. Every row is an
 * estimate with its own confidence - there is no column here that claims to be
 * a measurement, because there isn't one.
 */
@Entity(tableName = "stage_samples", indices = [Index("sessionId"), Index("atMillis")])
data class StageSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val atMillis: Long,
    val awake: Float,
    val light: Float,
    val deep: Float,
    val rem: Float,
    val confidence: Float,
    /** Which signals fed this estimate, e.g. "sleep-cycle model, movement". */
    val basis: String,
    /** Breaths per minute, when the audio analysis found something. Usually null. */
    val breathsPerMinute: Float? = null,
)
