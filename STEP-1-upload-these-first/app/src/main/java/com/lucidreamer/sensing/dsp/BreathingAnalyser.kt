// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.sensing.dsp

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Looks for a slow periodic rise and fall in loudness.
 *
 * ## What this is, and what it very much is not
 *
 * Breathing is roughly periodic at 0.1-0.4 Hz (6-24 breaths per minute). If a
 * phone is close enough and the room is quiet enough, the loudness envelope
 * sometimes carries that periodicity, and it can be recovered by
 * autocorrelating the envelope.
 *
 * That is the whole idea. It is a weak, indirect signal:
 *
 * - It fails completely with a fan, traffic, a partner, or a phone across the room.
 * - A periodic peak at 0.2 Hz is not proof of breathing - it could be anything
 *   slow and repetitive nearby.
 * - Respiration *does* become more irregular during REM, which is a real and
 *   documented physiological fact. But irregular breathing has many other
 *   causes, and regular breathing occurs in plenty of non-REM sleep. The
 *   correlation is real and weak.
 *
 * So this returns a *regularity index* and a confidence, and the estimator
 * treats it as one weak input among several. It is never presented to the user
 * as "breathing rate" with the authority that phrase implies, and it is never
 * allowed on its own to claim REM.
 */
object BreathingAnalyser {

    /** 6 breaths/minute. Slower than this during sleep is not plausible. */
    const val MIN_HZ = 0.1f

    /** 24 breaths/minute. */
    const val MAX_HZ = 0.4f

    data class Result(
        /** Estimated breaths per minute, or null when nothing periodic was found. */
        val breathsPerMinute: Float?,
        /**
         * How cleanly periodic the envelope was, 0..1.
         *
         * Higher means a sharper autocorrelation peak. Low values can mean
         * irregular breathing, or simply that there was nothing to hear.
         */
        val regularity: Float,
        /** How much to trust any of this, 0..1. Driven by peak strength and signal level. */
        val confidence: Float,
    ) {
        companion object {
            val NONE = Result(null, 0f, 0f)
        }
    }

    /**
     * @param envelope successive RMS values, evenly spaced
     * @param envelopeHz how many envelope samples per second (e.g. 10)
     */
    fun analyse(envelope: FloatArray, envelopeHz: Float): Result {
        // Needs at least a few breath cycles to say anything. At 0.1 Hz the
        // slowest plausible cycle is 10s, so demand ~30s of envelope.
        val minSamples = (30 * envelopeHz).toInt()
        if (envelope.size < minSamples || envelopeHz <= 0f) return Result.NONE

        val detrended = detrend(envelope)
        val energy = rms(detrended)
        // A flat envelope has no periodicity to find. This is the common case
        // in a genuinely silent room with the phone on a bedside table.
        if (energy < 1e-5f) return Result.NONE

        val minLag = (envelopeHz / MAX_HZ).toInt().coerceAtLeast(1)
        val maxLag = (envelopeHz / MIN_HZ).toInt().coerceAtMost(detrended.size / 2)
        if (maxLag <= minLag) return Result.NONE

        val correlation = autocorrelation(detrended, minLag, maxLag)

        // Smoothing across lags before peak-picking is what separates real
        // periodicity from noise. White noise produces narrow one-lag spikes in
        // the autocorrelation, and taking a plain maximum over a few hundred
        // lags will reliably find one several standard deviations up - so
        // unsmoothed peak-picking scores random noise as confident breathing.
        // Genuine periodicity produces a broad peak, which survives smoothing.
        val smoothed = movingAverage(correlation, smoothingWindow(envelopeHz))

        val bestIndex = smoothed.indices.maxByOrNull { smoothed[it] } ?: return Result.NONE
        val peak = smoothed[bestIndex]
        val bestLag = minLag + bestIndex

        // The normalised autocorrelation at the best lag *is* the standard
        // measure of how periodic a signal is: ~1 for a clean oscillation,
        // near 0 for noise. Using it directly gives an interpretable number
        // with real dynamic range, rather than a z-score that saturates.
        val regularity = peak.coerceIn(0f, 1f)
        val confidence = (regularity * levelWeight(energy)).coerceIn(0f, 1f)

        val periodSeconds = bestLag / envelopeHz
        val bpm = 60f / periodSeconds

        return if (peak < MIN_PEAK) {
            // Something was found, but it is indistinguishable from noise.
            // Report the weakness rather than emitting a number nobody should
            // rely on.
            Result(null, regularity, confidence)
        } else {
            Result(bpm, regularity, confidence)
        }
    }

    /**
     * Minimum normalised autocorrelation to call something periodic at all.
     *
     * For white noise the peak over a few hundred lags sits around 0.05-0.12
     * even after smoothing, so this is set well clear of that.
     */
    private const val MIN_PEAK = 0.25f

    private fun smoothingWindow(envelopeHz: Float): Int =
        (envelopeHz * 0.4f).toInt().coerceAtLeast(3)

    internal fun movingAverage(values: FloatArray, window: Int): FloatArray {
        if (window <= 1 || values.size <= window) return values.copyOf()
        val half = window / 2
        return FloatArray(values.size) { i ->
            var sum = 0.0
            var n = 0
            for (j in (i - half)..(i + half)) {
                if (j in values.indices) {
                    sum += values[j]
                    n++
                }
            }
            (sum / n).toFloat()
        }
    }

    /**
     * Removes slow drift so the autocorrelation sees the oscillation rather
     * than the trend. A moving-average subtraction, which is enough here.
     */
    internal fun detrend(values: FloatArray): FloatArray {
        val mean = values.average().toFloat()
        return FloatArray(values.size) { values[it] - mean }
    }

    internal fun autocorrelation(values: FloatArray, minLag: Int, maxLag: Int): FloatArray {
        val out = FloatArray(maxLag - minLag + 1)
        var zeroLag = 0.0
        for (v in values) zeroLag += v.toDouble() * v
        if (zeroLag <= 1e-12) return out

        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in 0 until values.size - lag) {
                sum += values[i].toDouble() * values[i + lag]
            }
            out[lag - minLag] = (sum / zeroLag).toFloat()
        }
        return out
    }

    private fun rms(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        var sum = 0.0
        for (v in values) sum += v.toDouble() * v
        return sqrt(sum / values.size).toFloat()
    }

    /** Scales confidence down when the envelope is barely above nothing. */
    private fun levelWeight(energy: Float): Float =
        (abs(energy) / 1e-3f).coerceIn(0f, 1f)
}
