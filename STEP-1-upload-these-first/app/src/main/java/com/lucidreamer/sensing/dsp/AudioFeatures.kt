// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.sensing.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Numbers extracted from one short window of audio.
 *
 * This is the *only* thing that survives from the microphone. The raw samples
 * are overwritten by the next read and never written to disk, so what the app
 * retains about your night is a handful of floats per window - loudness,
 * brightness, and how much the sound changed. None of it can be turned back
 * into audio, and none of it can be used to work out what was said.
 */
data class AudioFeatureFrame(
    val atMillis: Long,
    /** Loudness in dBFS. Silence floors at [SILENCE_DBFS]. */
    val levelDbfs: Float,
    /** Root mean square amplitude, 0..1. */
    val rms: Float,
    /** Zero-crossing rate, 0..1. High for hiss and rustling, low for voiced sound. */
    val zeroCrossingRate: Float,
    /** Spectral centre of mass in Hz - a rough "brightness". */
    val spectralCentroidHz: Float,
    /**
     * Geometric over arithmetic mean of the spectrum, 0..1.
     *
     * Near 1 for noise-like sound, near 0 for tonal sound. Useful for telling a
     * fan or hiss apart from speech or a creak.
     */
    val spectralFlatness: Float,
    /** How much the spectrum changed since the previous frame. Movement shows up here. */
    val spectralFlux: Float,
    /** Energy share below 250 Hz, where breathing and rumble live. */
    val lowBandRatio: Float,
) {
    companion object {
        const val SILENCE_DBFS = -100f
    }
}

object AudioFeatures {

    /**
     * Extracts features from one window of mono samples in -1..1.
     *
     * @param previousSpectrum the previous window's magnitude spectrum, for
     *   flux. Pass null for the first frame.
     * @return the features, plus this window's spectrum to feed forward.
     */
    fun extract(
        samples: FloatArray,
        sampleRate: Int,
        atMillis: Long,
        previousSpectrum: FloatArray?,
    ): Pair<AudioFeatureFrame, FloatArray> {
        if (samples.isEmpty()) {
            return AudioFeatureFrame(
                atMillis = atMillis,
                levelDbfs = AudioFeatureFrame.SILENCE_DBFS,
                rms = 0f,
                zeroCrossingRate = 0f,
                spectralCentroidHz = 0f,
                spectralFlatness = 0f,
                spectralFlux = 0f,
                lowBandRatio = 0f,
            ) to FloatArray(0)
        }

        val rms = rms(samples)
        val spectrum = Fft.magnitudeSpectrum(samples)

        val frame = AudioFeatureFrame(
            atMillis = atMillis,
            levelDbfs = toDbfs(rms),
            rms = rms,
            zeroCrossingRate = zeroCrossingRate(samples),
            spectralCentroidHz = spectralCentroid(spectrum, sampleRate),
            spectralFlatness = spectralFlatness(spectrum),
            spectralFlux = spectralFlux(spectrum, previousSpectrum),
            lowBandRatio = bandRatio(spectrum, sampleRate, 0f, 250f),
        )
        return frame to spectrum
    }

    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return sqrt(sum / samples.size).toFloat()
    }

    fun toDbfs(rms: Float): Float =
        if (rms <= 1e-7f) AudioFeatureFrame.SILENCE_DBFS
        else max(AudioFeatureFrame.SILENCE_DBFS, 20f * log10(rms))

    fun zeroCrossingRate(samples: FloatArray): Float {
        if (samples.size < 2) return 0f
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0f) != (samples[i - 1] >= 0f)) crossings++
        }
        return crossings.toFloat() / (samples.size - 1)
    }

    fun spectralCentroid(spectrum: FloatArray, sampleRate: Int): Float {
        if (spectrum.isEmpty()) return 0f
        var weighted = 0.0
        var total = 0.0
        for (i in spectrum.indices) {
            val magnitude = spectrum[i].toDouble()
            weighted += magnitude * Fft.binToHz(i, spectrum.size, sampleRate)
            total += magnitude
        }
        return if (total <= 1e-9) 0f else (weighted / total).toFloat()
    }

    /**
     * Geometric mean over arithmetic mean.
     *
     * Computed in the log domain: the geometric mean of a few thousand small
     * magnitudes underflows to zero in float arithmetic otherwise, which would
     * silently report every frame as perfectly tonal.
     */
    fun spectralFlatness(spectrum: FloatArray): Float {
        if (spectrum.isEmpty()) return 0f
        var logSum = 0.0
        var sum = 0.0
        val epsilon = 1e-10
        for (m in spectrum) {
            val v = m.toDouble() + epsilon
            logSum += ln(v)
            sum += v
        }
        val geometric = exp(logSum / spectrum.size)
        val arithmetic = sum / spectrum.size
        return if (arithmetic <= 1e-12) 0f else (geometric / arithmetic).toFloat().coerceIn(0f, 1f)
    }

    /** L2 distance between successive normalised spectra. Rises on movement and transients. */
    fun spectralFlux(spectrum: FloatArray, previous: FloatArray?): Float {
        if (previous == null || previous.size != spectrum.size || spectrum.isEmpty()) return 0f
        val a = normalise(spectrum)
        val b = normalise(previous)
        var sum = 0.0
        for (i in a.indices) {
            val d = (a[i] - b[i]).toDouble()
            sum += d * d
        }
        return sqrt(sum).toFloat()
    }

    /** Share of total spectral energy falling between [fromHz] and [toHz]. */
    fun bandRatio(spectrum: FloatArray, sampleRate: Int, fromHz: Float, toHz: Float): Float {
        if (spectrum.isEmpty()) return 0f
        var band = 0.0
        var total = 0.0
        for (i in spectrum.indices) {
            val hz = Fft.binToHz(i, spectrum.size, sampleRate)
            val magnitude = spectrum[i].toDouble()
            total += magnitude
            if (hz in fromHz..toHz) band += magnitude
        }
        return if (total <= 1e-9) 0f else (band / total).toFloat()
    }

    private fun normalise(spectrum: FloatArray): FloatArray {
        var total = 0.0
        for (m in spectrum) total += abs(m.toDouble())
        if (total <= 1e-9) return FloatArray(spectrum.size)
        return FloatArray(spectrum.size) { (spectrum[it] / total).toFloat() }
    }
}
