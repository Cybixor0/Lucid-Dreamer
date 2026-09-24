// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.sensing.dsp

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Sleep/wake estimation from movement.
 *
 * ## Why this is the most trustworthy signal here, and still limited
 *
 * Actigraphy is the one method in this app with a genuine research literature
 * behind it. Wrist-worn actigraphs have been validated against polysomnography
 * for decades, and the Cole-Kripke algorithm below is a published, widely-cited
 * scoring rule.
 *
 * Three caveats that must not be lost:
 *
 * 1. **It separates sleep from wake, not sleep stages.** Movement carries no
 *    information that distinguishes REM from non-REM. Anyone claiming REM
 *    detection from an accelerometer is guessing.
 * 2. **Its specificity for wake is poor.** Actigraphy reliably calls sleep
 *    "sleep" (~90% sensitivity) but frequently calls quiet wakefulness "sleep"
 *    too. Lying still and awake usually scores as asleep.
 * 3. **A phone on a mattress is not a wrist actigraph.** The algorithm was
 *    validated on a device strapped to a limb, with its own filtering and its
 *    own count units. A phone picks up mattress transmission from a whole body,
 *    including a partner. The activity counts below are a *proxy*, scaled to be
 *    roughly comparable, and the mapping is approximate.
 *
 * Which is why the output feeds a probabilistic estimator alongside other
 * signals rather than being presented as a verdict.
 *
 * Reference: Cole RJ, Kripke DF, Gruen W, Mullaney DJ, Gillin JC. "Automatic
 * sleep/wake identification from wrist activity." Sleep. 1992;15(5):461-9.
 */
object Actigraphy {

    /**
     * Cole-Kripke weights for one-minute epochs, covering the four preceding
     * epochs, the current one, and the two following.
     *
     * The current epoch dominates, but context matters in both directions -
     * which is why scoring lags real time by two minutes.
     */
    private val WEIGHTS = floatArrayOf(404f, 598f, 326f, 441f, 1408f, 508f, 350f)
    private const val SCALE = 0.00001f

    /** Index of the current epoch within [WEIGHTS]. */
    private const val CENTRE = 4

    /** Epochs after the current one that the algorithm needs. */
    const val LOOKAHEAD = 2

    /** Epochs before the current one that the algorithm needs. */
    const val LOOKBEHIND = 4

    /**
     * Converts raw accelerometer magnitudes into an activity count for one epoch.
     *
     * Real actigraphs band-pass filter and count threshold crossings. This
     * approximates that by summing how far each sample deviates from the
     * running gravity estimate, which rejects the constant 9.81 m/s^2 without
     * needing orientation.
     *
     * @param magnitudes accelerometer vector magnitudes in m/s^2
     */
    fun activityCount(magnitudes: FloatArray): Float {
        if (magnitudes.size < 2) return 0f

        // Gravity follows slowly; subtracting a low-passed version leaves motion.
        var gravity = magnitudes[0]
        val alpha = 0.05f
        var sum = 0.0
        for (m in magnitudes) {
            gravity += alpha * (m - gravity)
            val deviation = abs(m - gravity)
            // Below this, it is sensor noise rather than movement.
            if (deviation > NOISE_FLOOR) sum += deviation.toDouble()
        }

        // Scaled so a typical roll-over lands in the hundreds, which is the
        // range Cole-Kripke's weights expect.
        return (sum * COUNT_SCALE).toFloat()
    }

    private const val NOISE_FLOOR = 0.02f
    private const val COUNT_SCALE = 8.0

    /**
     * Scores a sequence of per-minute activity counts as sleep or wake.
     *
     * @return one verdict per input epoch. Epochs without enough neighbours on
     *   either side are scored with the available context, which makes the very
     *   start and end of a night slightly less reliable - stated here rather
     *   than hidden.
     */
    fun scoreEpochs(counts: FloatArray): List<EpochScore> =
        counts.indices.map { i -> scoreEpoch(counts, i) }

    fun scoreEpoch(counts: FloatArray, index: Int): EpochScore {
        var d = 0f
        for (offset in -LOOKBEHIND..LOOKAHEAD) {
            val i = index + offset
            if (i < 0 || i >= counts.size) continue
            d += WEIGHTS[CENTRE + offset] * counts[i]
        }
        d *= SCALE

        // The published rule is simply: D < 1 means sleep.
        val asleep = d < 1f

        // Distance from the threshold, squashed into a usable 0..1. Values far
        // from 1 are unambiguous; values near it genuinely are not, and the
        // estimator should know the difference.
        val certainty = (abs(d - 1f) / 2f).coerceIn(0f, 1f)

        return EpochScore(index = index, score = d, asleep = asleep, certainty = certainty)
    }

    data class EpochScore(
        val index: Int,
        /** The Cole-Kripke D statistic. Below 1 means sleep. */
        val score: Float,
        val asleep: Boolean,
        val certainty: Float,
    )

    /**
     * Finds the first sustained run of sleep epochs.
     *
     * Requiring a run rather than a single epoch avoids calling "lay still for
     * a minute while reading" the start of the night.
     *
     * @return index of the first epoch of the run, or null if never
     */
    fun detectSleepOnset(scores: List<EpochScore>, requiredConsecutive: Int = 10): Int? {
        var run = 0
        for (score in scores) {
            if (score.asleep) {
                run++
                if (run >= requiredConsecutive) return score.index - requiredConsecutive + 1
            } else {
                run = 0
            }
        }
        return null
    }

    /** Share of epochs scored as sleep. */
    fun sleepEfficiency(scores: List<EpochScore>): Float =
        if (scores.isEmpty()) 0f else scores.count { it.asleep }.toFloat() / scores.size

    /** Magnitude of a three-axis accelerometer reading. */
    fun magnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)
}
