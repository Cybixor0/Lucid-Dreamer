// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.domain.experiment

import com.lucidreamer.core.EventLog
import com.lucidreamer.data.SettingsStore
import com.lucidreamer.data.db.LucidDatabase
import com.lucidreamer.data.db.entity.ExperimentEntity
import com.lucidreamer.data.db.entity.ExperimentRunEntity
import com.lucidreamer.data.db.entity.NightOutcome
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import kotlin.random.Random

/**
 * Assigns nights to experiment arms and summarises the results.
 *
 * ## What a result here means
 *
 * A sample size of one, no blinding, no control for expectation, and an outcome
 * the participant scores themselves the next morning. Knowing you are on the
 * "good" arm is enough to change the result, and there is no way around that in
 * a self-experiment.
 *
 * So the comparison below reports counts and a difference, and explicitly
 * refuses to call anything a winner until there is enough data for the number
 * to mean anything at all - and even then frames it as "what happened", not
 * "what works". The honest value of this feature is that it stops you
 * misremembering which configuration you were actually running, which is a real
 * and common failure.
 */
class ExperimentManager(
    private val db: LucidDatabase,
    private val settings: SettingsStore,
    private val log: EventLog,
) {
    /**
     * Picks the cue profile for tonight, if an experiment is running.
     *
     * Assignment is derived from the experiment's stored seed and the date, so
     * it is reproducible: reinstalling from a backup gives the same assignment
     * for the same night rather than silently re-randomising history.
     *
     * @return the cue profile id to use, or null if no experiment is active
     */
    suspend fun cueProfileForNight(night: LocalDate, sessionId: Long?): Long? {
        val experiment = db.experimentDao().activeExperiment() ?: return null

        db.experimentDao().runForNight(experiment.id, night.toEpochDay())?.let { existing ->
            // Already assigned - never reassign, or a restart mid-night would
            // switch arms and invalidate the night.
            if (existing.sessionId == null && sessionId != null) {
                db.experimentDao().updateRun(existing.copy(sessionId = sessionId))
            }
            return existing.cueProfileId
        }

        val armA = assignArm(experiment, night)
        val profileId = if (armA) experiment.armACueProfileId else experiment.armBCueProfileId

        db.experimentDao().insertRun(
            ExperimentRunEntity(
                experimentId = experiment.id,
                sessionId = sessionId,
                nightEpochDay = night.toEpochDay(),
                armA = armA,
                cueProfileId = profileId,
            ),
        )

        val label = if (armA) experiment.armALabel else experiment.armBLabel
        log.i(
            EventLog.TAG_EXPERIMENT,
            "\"${experiment.name}\": tonight is arm $label",
            sessionId,
        )
        return profileId
    }

    internal fun assignArm(experiment: ExperimentEntity, night: LocalDate): Boolean =
        if (experiment.alternateRandomly) {
            Random(experiment.assignmentSeed * 31 + night.toEpochDay()).nextBoolean()
        } else {
            // Strict alternation, anchored to the start so it stays stable.
            val dayIndex = night.toEpochDay() - (experiment.createdAtMillis / 86_400_000L)
            dayIndex % 2 == 0L
        }

    suspend fun recordOutcome(runId: Long, outcome: NightOutcome, note: String = "") {
        db.experimentDao().recordOutcome(runId, outcome, note, System.currentTimeMillis())
        log.i(EventLog.TAG_EXPERIMENT, "Night outcome recorded: ${outcome.label}")
    }

    /** Runs that finished without an outcome, so the app can ask about them. */
    suspend fun runsAwaitingOutcome(): List<ExperimentRunEntity> =
        db.experimentDao().awaitingOutcome(LocalDate.now().toEpochDay())

    suspend fun summarise(experimentId: Long): ExperimentSummary? {
        val experiment = db.experimentDao().byId(experimentId) ?: return null
        val runs = db.experimentDao().runsFor(experimentId)
            .filter { it.outcome != NightOutcome.NOT_RECORDED }

        val (armA, armB) = runs.partition { it.armA }

        return ExperimentSummary(
            experiment = experiment,
            armA = ArmResult(experiment.armALabel, armA),
            armB = ArmResult(experiment.armBLabel, armB),
            nightsRecorded = runs.size,
            nightsPending = db.experimentDao().runsFor(experimentId).size - runs.size,
        )
    }

    suspend fun activeExperimentId(): Long? = db.experimentDao().activeExperiment()?.id

    suspend fun setActive(experimentId: Long, active: Boolean) {
        val experiment = db.experimentDao().byId(experimentId) ?: return
        db.experimentDao().update(
            experiment.copy(
                active = active,
                endedAtMillis = if (!active) System.currentTimeMillis() else null,
            ),
        )
        settings.setActiveExperimentId(if (active) experimentId else -1L)
    }
}

data class ArmResult(
    val label: String,
    val runs: List<ExperimentRunEntity>,
) {
    val nights: Int get() = runs.size
    val lucid: Int get() = runs.count { it.outcome.isSuccess }
    val disturbed: Int get() = runs.count { it.outcome.disturbedSleep }
    val noRecall: Int get() = runs.count { it.outcome == NightOutcome.NO_RECALL }

    /** Share of nights that produced a lucid dream, or null with no data. */
    val successRate: Float? get() = if (nights == 0) null else lucid.toFloat() / nights

    fun countOf(outcome: NightOutcome): Int = runs.count { it.outcome == outcome }
}

data class ExperimentSummary(
    val experiment: ExperimentEntity,
    val armA: ArmResult,
    val armB: ArmResult,
    val nightsRecorded: Int,
    val nightsPending: Int,
) {
    /**
     * Nights per arm before the app will show a comparison at all.
     *
     * Ten is still far too few to be evidence. It is set here as the point
     * below which a difference is *so* meaningless that displaying it would
     * actively mislead - not as a threshold at which the result becomes
     * trustworthy. It does not become trustworthy.
     */
    val minimumNightsPerArm = 10

    val hasEnoughData: Boolean
        get() = armA.nights >= minimumNightsPerArm && armB.nights >= minimumNightsPerArm

    /** Percentage-point difference in lucid rate, A minus B. Null without data. */
    val difference: Float?
        get() {
            val a = armA.successRate ?: return null
            val b = armB.successRate ?: return null
            return a - b
        }

    /**
     * What the app is willing to say about the result.
     *
     * Every branch is phrased as a description of what happened rather than a
     * conclusion about what works, because a self-experiment with no blinding
     * cannot support the latter.
     */
    fun verdict(): String {
        if (nightsRecorded == 0) {
            return "No nights recorded yet. Record how each night went and the comparison will fill in."
        }

        if (!hasEnoughData) {
            val needed = minimumNightsPerArm
            return "Too early to compare. You have ${armA.nights} night(s) on ${armA.label} and " +
                "${armB.nights} on ${armB.label}; the app waits for $needed of each before showing " +
                "a difference, because anything less is noise."
        }

        val diff = difference ?: return "Not enough data."
        val points = (diff * 100).toInt()
        val leader = if (diff > 0) armA else armB

        return when {
            kotlin.math.abs(points) < 10 ->
                "Almost no difference so far: ${armA.label} ${armA.lucid}/${armA.nights}, " +
                    "${armB.label} ${armB.lucid}/${armB.nights}."

            else ->
                "${leader.label} has had more lucid nights so far (${armA.label} ${armA.lucid}/${armA.nights}, " +
                    "${armB.label} ${armB.lucid}/${armB.nights}). With this few nights that could easily " +
                    "be chance - it is a hint about what to keep trying, not a result."
        }
    }
}
