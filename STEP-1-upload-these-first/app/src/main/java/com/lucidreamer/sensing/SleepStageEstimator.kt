// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.sensing

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * The four states the app is willing to talk about.
 *
 * Note what is missing: there is no "N1/N2/N3". Those are defined by EEG
 * criteria a phone cannot approach, and inventing them here would be a lie
 * dressed up in clinical vocabulary.
 */
enum class SleepStage {
    AWAKE,
    LIGHT,
    /** Deliberately "possible". Depth is inferred from very little. */
    POSSIBLE_DEEP,
    /** Deliberately "possible". See [SleepStageEstimator] for why. */
    POSSIBLE_REM;

    val label: String
        get() = when (this) {
            AWAKE -> "Awake"
            LIGHT -> "Light sleep"
            POSSIBLE_DEEP -> "Possibly deeper sleep"
            POSSIBLE_REM -> "Possible REM"
        }
}

/**
 * A probability distribution over stages, plus how much to trust it.
 *
 * Always a distribution, never a single label. The UI shows the most likely
 * stage *with* its probability, so "possible REM, 41%" cannot be mistaken for
 * a measurement.
 */
data class StageEstimate(
    val atMillis: Long,
    val awake: Float,
    val light: Float,
    val deep: Float,
    val rem: Float,
    /**
     * 0..1. Driven by which sensors were available, how good the signal was,
     * and how sharp the resulting distribution is.
     *
     * Below [USABLE_CONFIDENCE] the app treats the estimate as unusable and
     * falls back to the fixed schedule.
     */
    val confidence: Float,
    /** Plain-language account of what fed this estimate. Shown in the UI. */
    val basis: String,
) {
    val mostLikely: SleepStage
        get() = listOf(
            SleepStage.AWAKE to awake,
            SleepStage.LIGHT to light,
            SleepStage.POSSIBLE_DEEP to deep,
            SleepStage.POSSIBLE_REM to rem,
        ).maxByOrNull { it.second }!!.first

    fun probabilityOf(stage: SleepStage): Float = when (stage) {
        SleepStage.AWAKE -> awake
        SleepStage.LIGHT -> light
        SleepStage.POSSIBLE_DEEP -> deep
        SleepStage.POSSIBLE_REM -> rem
    }

    val isUsable: Boolean get() = confidence >= USABLE_CONFIDENCE

    /** e.g. "Possible REM - 41% (low confidence)". */
    fun describe(): String {
        val stage = mostLikely
        val pct = (probabilityOf(stage) * 100).toInt()
        val qualifier = when {
            confidence < USABLE_CONFIDENCE -> " (too uncertain to act on)"
            confidence < 0.55f -> " (low confidence)"
            else -> ""
        }
        return "${stage.label} - $pct%$qualifier"
    }

    companion object {
        const val USABLE_CONFIDENCE = 0.35f

        fun unknown(atMillis: Long, reason: String) = StageEstimate(
            atMillis = atMillis,
            awake = 0.25f, light = 0.25f, deep = 0.25f, rem = 0.25f,
            confidence = 0f,
            basis = reason,
        )
    }
}

/** What the estimator has to work with at a given moment. */
data class StageInputs(
    val atMillis: Long,
    /** Time since estimated or reported sleep onset. */
    val minutesSinceOnset: Int,
    /** Total expected night length, for shaping the cycle prior. */
    val expectedNightMinutes: Int,
    /** From [com.lucidreamer.sensing.dsp.Actigraphy], if motion sensing is on. */
    val motionAsleep: Boolean? = null,
    val motionCertainty: Float = 0f,
    /** Recent movement level, 0..1, where 1 is a lot. */
    val movementIndex: Float? = null,
    /** From [com.lucidreamer.sensing.dsp.BreathingAnalyser], if mic sensing is on. */
    val breathingRegularity: Float? = null,
    val breathingConfidence: Float = 0f,
    /** Ambient sound level in dBFS, if mic sensing is on. */
    val levelDbfs: Float? = null,
)

/**
 * Combines a population cycle model with whatever sensors are available.
 *
 * ## What this can honestly claim
 *
 * Almost nothing on its own. It is a weighted combination of:
 *
 * - **An ultradian cycle prior.** Roughly 90-minute cycles, REM periods
 *   lengthening towards morning, deep sleep concentrated early. This is a
 *   population average applied to one person on one night. It is arithmetic,
 *   not observation, and on its own it knows nothing about *you*.
 * - **Actigraphy.** Genuinely informative about sleep versus wake. Carries no
 *   information whatsoever about REM versus non-REM.
 * - **Breathing regularity.** Respiration is more irregular in REM. Real, and
 *   weak, and easily destroyed by a fan or a partner.
 *
 * None of these observes REM. Their combination does not observe REM either -
 * it produces a slightly better-informed guess than the cycle model alone. That
 * is the honest ceiling, and it is why every output is a probability with a
 * confidence attached, why the strings say "possible REM", and why low
 * confidence falls back to the user's fixed schedule rather than acting on
 * noise.
 *
 * A phone cannot measure sleep stages. This produces an estimate that is
 * hopefully better than nothing, and it says so.
 */
class SleepStageEstimator {

    private var previous: StageEstimate? = null

    fun reset() {
        previous = null
    }

    fun estimate(inputs: StageInputs): StageEstimate {
        val prior = cyclePrior(inputs.minutesSinceOnset, inputs.expectedNightMinutes)

        var awake = prior[0]
        var light = prior[1]
        var deep = prior[2]
        var rem = prior[3]

        val contributions = mutableListOf("sleep-cycle model")
        var evidenceWeight = 0f

        // --- Movement -------------------------------------------------------
        // The strongest real signal available, and only about sleep vs wake.
        inputs.motionAsleep?.let { asleep ->
            val weight = inputs.motionCertainty.coerceIn(0f, 1f)
            if (asleep) {
                awake *= lerp(1f, 0.15f, weight)
            } else {
                awake *= lerp(1f, 6f, weight)
                // Being awake says nothing about which sleep stage would
                // otherwise apply, so the rest are scaled uniformly and the
                // normalisation below sorts it out.
                light *= lerp(1f, 0.4f, weight)
                deep *= lerp(1f, 0.2f, weight)
                rem *= lerp(1f, 0.4f, weight)
            }
            evidenceWeight = max(evidenceWeight, weight)
            contributions += "movement"
        }

        // Brief movements without full waking are more typical of light sleep
        // and REM transitions than of deep sleep.
        inputs.movementIndex?.let { movement ->
            val m = movement.coerceIn(0f, 1f)
            deep *= lerp(1f, 0.35f, m)
            light *= lerp(1f, 1.3f, m)
        }

        // --- Breathing ------------------------------------------------------
        // The weakest input, weighted accordingly, and capped so it can never
        // dominate the outcome on its own.
        inputs.breathingRegularity?.let { regularity ->
            val weight = (inputs.breathingConfidence * BREATHING_MAX_WEIGHT).coerceIn(0f, 1f)
            if (weight > 0.01f) {
                // Irregular -> slightly more REM-like. Regular -> slightly more
                // deep-like. "Slightly" is doing real work in that sentence.
                val irregularity = 1f - regularity.coerceIn(0f, 1f)
                rem *= lerp(1f, 1f + 1.2f * irregularity, weight)
                deep *= lerp(1f, 1f + 0.8f * regularity, weight)
                evidenceWeight = max(evidenceWeight, weight * 0.6f)
                contributions += "breathing regularity"
            }
        }

        // --- Ambient level --------------------------------------------------
        // A loud room usually means somebody is up and about.
        inputs.levelDbfs?.let { level ->
            if (level > LOUD_DBFS) {
                awake *= 2.2f
                contributions += "room noise"
            }
        }

        var posterior = normalise(floatArrayOf(awake, light, deep, rem))

        // --- Temporal smoothing ---------------------------------------------
        // Sleep stages do not flicker second to second. Blending with the
        // previous estimate suppresses implausible jumps without needing a
        // full hidden Markov model.
        previous?.let { last ->
            val prev = floatArrayOf(last.awake, last.light, last.deep, last.rem)
            posterior = normalise(
                FloatArray(4) { i -> SMOOTHING * prev[i] + (1f - SMOOTHING) * posterior[i] },
            )
        }

        val confidence = confidenceFrom(posterior, evidenceWeight, inputs)

        val estimate = StageEstimate(
            atMillis = inputs.atMillis,
            awake = posterior[0],
            light = posterior[1],
            deep = posterior[2],
            rem = posterior[3],
            confidence = confidence,
            basis = contributions.joinToString(", "),
        )
        previous = estimate
        return estimate
    }

    /**
     * Population-average distribution over stages at a point in the night.
     *
     * Deep sleep is front-loaded and decays; REM is back-loaded and grows; both
     * oscillate on a ~90-minute cycle. This is textbook sleep architecture
     * turned into arithmetic - not a claim about the person using it.
     */
    internal fun cyclePrior(minutesSinceOnset: Int, expectedNightMinutes: Int): FloatArray {
        if (minutesSinceOnset < 0) return floatArrayOf(0.9f, 0.08f, 0.01f, 0.01f)

        val night = expectedNightMinutes.coerceAtLeast(60).toFloat()
        val progress = (minutesSinceOnset / night).coerceIn(0f, 1.2f)

        // Where in the current ~90 minute cycle we are, 0..1.
        val cyclePhase = (minutesSinceOnset % CYCLE_MINUTES) / CYCLE_MINUTES.toFloat()

        // REM sits late in each cycle, and cycles later in the night contain
        // much more of it.
        val remPhase = 0.5f + 0.5f * cos(2 * PI * (cyclePhase - 0.8f)).toFloat()
        val remGrowth = 0.15f + 1.35f * progress.coerceAtMost(1f)
        val rem = (remPhase * remGrowth).coerceAtLeast(0.02f)

        // Deep sleep peaks early in each cycle and fades across the night.
        val deepPhase = 0.5f + 0.5f * cos(2 * PI * (cyclePhase - 0.25f)).toFloat()
        val deepDecay = exp(-2.2f * progress)
        val deep = (deepPhase * deepDecay).coerceAtLeast(0.02f)

        // Light sleep is the bulk of a normal night.
        val light = 0.9f

        // Brief awakenings become more common towards morning.
        val awake = 0.04f + 0.12f * progress

        return normalise(floatArrayOf(awake, light, deep, rem))
    }

    /**
     * How much to trust the result.
     *
     * Two things matter, and both must be present: real sensor evidence, and a
     * distribution that actually says something. A confident-looking peak
     * derived purely from the cycle model is not confidence - it is arithmetic
     * restating its own assumptions, and it is capped hard for exactly that
     * reason.
     */
    internal fun confidenceFrom(
        posterior: FloatArray,
        evidenceWeight: Float,
        inputs: StageInputs,
    ): Float {
        // Normalised entropy: 0 when one stage is certain, 1 when uniform.
        var entropy = 0.0
        for (p in posterior) {
            if (p > 1e-6f) entropy -= p * ln(p.toDouble())
        }
        val sharpness = (1.0 - entropy / ln(4.0)).toFloat().coerceIn(0f, 1f)

        val hasMotion = inputs.motionAsleep != null
        val hasAudio = inputs.breathingConfidence > 0.1f || inputs.levelDbfs != null

        val sensorFloor = when {
            hasMotion && hasAudio -> 0.75f
            hasMotion -> 0.6f
            hasAudio -> 0.4f
            // Cycle model only. Hard ceiling: this is a population average, and
            // no amount of arithmetic sharpness makes it knowledge about tonight.
            else -> MODEL_ONLY_CEILING
        }

        return (sharpness * 0.5f + evidenceWeight * 0.5f).coerceIn(0f, 1f) * sensorFloor
    }

    private fun normalise(values: FloatArray): FloatArray {
        var total = 0f
        for (v in values) total += max(v, 0f)
        if (total <= 1e-9f) return floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f)
        return FloatArray(values.size) { max(values[it], 0f) / total }
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t.coerceIn(0f, 1f)

    companion object {
        const val CYCLE_MINUTES = 90

        /**
         * The most confidence the cycle model alone may ever report.
         *
         * Deliberately just under [StageEstimate.USABLE_CONFIDENCE], so a
         * model-only estimate can never drive adaptive cue placement. Without
         * sensors the app falls back to the user's fixed schedule, which is the
         * correct behaviour: a population average is not evidence about tonight.
         */
        const val MODEL_ONLY_CEILING = 0.34f

        /** Cap on how far breathing regularity can move the result. */
        const val BREATHING_MAX_WEIGHT = 0.45f

        const val SMOOTHING = 0.6f
        const val LOUD_DBFS = -35f
    }
}
