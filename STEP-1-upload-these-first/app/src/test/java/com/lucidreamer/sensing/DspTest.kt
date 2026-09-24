// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.sensing

import com.lucidreamer.sensing.dsp.Actigraphy
import com.lucidreamer.sensing.dsp.AudioFeatures
import com.lucidreamer.sensing.dsp.BreathingAnalyser
import com.lucidreamer.sensing.dsp.Fft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class FftTest {

    @Test
    fun `a pure sine produces a peak at its own frequency`() {
        val sampleRate = 1024
        val toneHz = 128f
        val samples = FloatArray(1024) { sin(2 * PI * toneHz * it / sampleRate).toFloat() }

        val spectrum = Fft.magnitudeSpectrum(samples, Fft.Window.RECTANGULAR)
        val peak = spectrum.indices.maxByOrNull { spectrum[it] }!!
        val peakHz = Fft.binToHz(peak, spectrum.size, sampleRate)

        assertEquals(toneHz, peakHz, 2f)
    }

    @Test
    fun `two tones produce two peaks`() {
        val sampleRate = 2048
        val samples = FloatArray(2048) {
            (sin(2 * PI * 200 * it / sampleRate) + 0.8 * sin(2 * PI * 600 * it / sampleRate)).toFloat()
        }
        val spectrum = Fft.magnitudeSpectrum(samples, Fft.Window.RECTANGULAR)

        fun magnitudeAt(hz: Float): Float {
            val bin = (hz * spectrum.size * 2 / sampleRate).toInt()
            return (bin - 2..bin + 2).mapNotNull { spectrum.getOrNull(it) }.max()
        }

        val at200 = magnitudeAt(200f)
        val at600 = magnitudeAt(600f)
        val at1000 = magnitudeAt(1000f)

        assertTrue("200 Hz peak should dominate 1000 Hz", at200 > at1000 * 10)
        assertTrue("600 Hz peak should dominate 1000 Hz", at600 > at1000 * 10)
    }

    @Test
    fun `silence produces an empty spectrum`() {
        val spectrum = Fft.magnitudeSpectrum(FloatArray(512))
        assertTrue(spectrum.all { it < 1e-5f })
    }

    @Test
    fun `non-power-of-two input is zero-padded rather than rejected`() {
        val spectrum = Fft.magnitudeSpectrum(FloatArray(700) { sin(it * 0.1).toFloat() })
        assertEquals(512, spectrum.size) // 1024 padded, halved to Nyquist
    }

    @Test
    fun `transform rejects a non-power-of-two length`() {
        val real = FloatArray(300)
        val imag = FloatArray(300)
        var threw = false
        try {
            Fft.transform(real, imag)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("should reject length 300", threw)
    }

    @Test
    fun `a constant signal puts all energy in the DC bin`() {
        val spectrum = Fft.magnitudeSpectrum(FloatArray(256) { 1f }, Fft.Window.RECTANGULAR)
        assertTrue("DC should dominate", spectrum[0] > spectrum.drop(1).max() * 100)
    }
}

class AudioFeaturesTest {

    private val sampleRate = 16_000

    private fun tone(hz: Float, n: Int = 2048, amplitude: Float = 0.5f) =
        FloatArray(n) { (amplitude * sin(2 * PI * hz * it / sampleRate)).toFloat() }

    private fun noise(n: Int = 2048, amplitude: Float = 0.5f, seed: Int = 1): FloatArray {
        val rnd = Random(seed)
        return FloatArray(n) { (rnd.nextFloat() * 2 - 1) * amplitude }
    }

    @Test
    fun `rms and dBFS behave as expected`() {
        assertEquals(0f, AudioFeatures.rms(FloatArray(100)), 1e-6f)
        // A sine of amplitude a has RMS a/sqrt(2).
        assertEquals(0.5f / 1.414f, AudioFeatures.rms(tone(440f)), 0.01f)
        assertEquals(AudioFeatureFrameSilence, AudioFeatures.toDbfs(0f), 0.1f)
        assertEquals(0f, AudioFeatures.toDbfs(1f), 0.1f)
        assertTrue(AudioFeatures.toDbfs(0.1f) < AudioFeatures.toDbfs(0.5f))
    }

    private val AudioFeatureFrameSilence = -100f

    @Test
    fun `zero crossing rate is higher for noise than for a low tone`() {
        val lowTone = AudioFeatures.zeroCrossingRate(tone(100f))
        val hiss = AudioFeatures.zeroCrossingRate(noise())
        assertTrue("noise $hiss should cross more than a 100Hz tone $lowTone", hiss > lowTone * 5)
    }

    @Test
    fun `spectral centroid rises with frequency`() {
        val low = AudioFeatures.spectralCentroid(Fft.magnitudeSpectrum(tone(200f)), sampleRate)
        val high = AudioFeatures.spectralCentroid(Fft.magnitudeSpectrum(tone(3000f)), sampleRate)
        assertTrue("centroid should rise: $low then $high", high > low * 3)
    }

    @Test
    fun `spectral flatness separates noise from a tone`() {
        val tonal = AudioFeatures.spectralFlatness(Fft.magnitudeSpectrum(tone(440f)))
        val noisy = AudioFeatures.spectralFlatness(Fft.magnitudeSpectrum(noise()))
        assertTrue("noise ($noisy) should be flatter than a tone ($tonal)", noisy > tonal)
    }

    @Test
    fun `spectral flatness does not underflow to zero on quiet input`() {
        // Computed naively in the linear domain, the geometric mean of a few
        // thousand tiny magnitudes underflows and every frame reads as tonal.
        val quiet = AudioFeatures.spectralFlatness(Fft.magnitudeSpectrum(noise(amplitude = 0.0005f)))
        assertTrue("flatness should still be meaningful when quiet, was $quiet", quiet > 0.01f)
    }

    @Test
    fun `spectral flux is zero for an unchanged spectrum and positive when it changes`() {
        val a = Fft.magnitudeSpectrum(tone(440f))
        val b = Fft.magnitudeSpectrum(tone(2000f))

        assertEquals(0f, AudioFeatures.spectralFlux(a, a), 1e-5f)
        assertTrue(AudioFeatures.spectralFlux(b, a) > 0.01f)
        assertEquals("no previous frame means no flux", 0f, AudioFeatures.spectralFlux(a, null), 0f)
    }

    @Test
    fun `band ratio finds energy where it was put`() {
        val lowSpectrum = Fft.magnitudeSpectrum(tone(120f))
        val highSpectrum = Fft.magnitudeSpectrum(tone(5000f))

        assertTrue(AudioFeatures.bandRatio(lowSpectrum, sampleRate, 0f, 250f) > 0.5f)
        assertTrue(AudioFeatures.bandRatio(highSpectrum, sampleRate, 0f, 250f) < 0.1f)
    }

    @Test
    fun `extract returns a complete frame and an empty one for empty input`() {
        val (frame, spectrum) = AudioFeatures.extract(tone(440f), sampleRate, 1000L, null)
        assertEquals(1000L, frame.atMillis)
        assertTrue(frame.rms > 0f)
        assertTrue(spectrum.isNotEmpty())

        val (empty, emptySpectrum) = AudioFeatures.extract(FloatArray(0), sampleRate, 5L, null)
        assertEquals(-100f, empty.levelDbfs, 0.1f)
        assertTrue(emptySpectrum.isEmpty())
    }
}

class BreathingAnalyserTest {

    private val envelopeHz = 10f

    /** A synthetic loudness envelope oscillating at [bpm] breaths per minute. */
    private fun breathingEnvelope(
        bpm: Float,
        seconds: Int = 60,
        amplitude: Float = 0.01f,
        jitter: Float = 0f,
        seed: Int = 7,
    ): FloatArray {
        val rnd = Random(seed)
        val hz = bpm / 60f
        val n = (seconds * envelopeHz).toInt()
        return FloatArray(n) { i ->
            val t = i / envelopeHz
            val phaseNoise = if (jitter > 0f) (rnd.nextFloat() - 0.5f) * jitter else 0f
            (0.02f + amplitude * sin(2 * PI * hz * (t + phaseNoise)).toFloat())
        }
    }

    @Test
    fun `a clean periodic envelope recovers roughly the right rate`() {
        val result = BreathingAnalyser.analyse(breathingEnvelope(bpm = 12f), envelopeHz)

        assertNotNull("should find a rate in a clean signal", result.breathsPerMinute)
        assertEquals(12f, result.breathsPerMinute!!, 2.5f)
        assertTrue("should be confident about a clean signal", result.confidence > 0.3f)
    }

    @Test
    fun `a faster rate is also recovered`() {
        val result = BreathingAnalyser.analyse(breathingEnvelope(bpm = 18f), envelopeHz)
        assertNotNull(result.breathsPerMinute)
        assertEquals(18f, result.breathsPerMinute!!, 3f)
    }

    @Test
    fun `an irregular envelope scores lower regularity than a regular one`() {
        val regular = BreathingAnalyser.analyse(breathingEnvelope(bpm = 14f, jitter = 0f), envelopeHz)
        val irregular = BreathingAnalyser.analyse(breathingEnvelope(bpm = 14f, jitter = 1.5f), envelopeHz)

        assertTrue(
            "irregular (${irregular.regularity}) should score below regular (${regular.regularity})",
            irregular.regularity < regular.regularity,
        )
    }

    @Test
    fun `a flat silent envelope reports nothing rather than inventing a rate`() {
        val result = BreathingAnalyser.analyse(FloatArray(600) { 0.00001f }, envelopeHz)
        assertNull(result.breathsPerMinute)
        assertEquals(0f, result.confidence, 0.001f)
    }

    @Test
    fun `random noise does not produce a confident breathing rate`() {
        val rnd = Random(3)
        val noise = FloatArray(600) { rnd.nextFloat() * 0.02f }
        val result = BreathingAnalyser.analyse(noise, envelopeHz)

        assertTrue(
            "noise should not read as confident breathing (was ${result.confidence})",
            result.confidence < 0.5f,
        )
    }

    @Test
    fun `too short an envelope is refused rather than guessed at`() {
        val result = BreathingAnalyser.analyse(breathingEnvelope(bpm = 12f, seconds = 5), envelopeHz)
        assertNull(result.breathsPerMinute)
        assertEquals(0f, result.confidence, 0.001f)
    }

    @Test
    fun `rates outside the plausible range are not reported`() {
        // 2 breaths/minute is below the 0.1 Hz floor and must not be returned.
        val result = BreathingAnalyser.analyse(breathingEnvelope(bpm = 2f, seconds = 120), envelopeHz)
        result.breathsPerMinute?.let {
            assertTrue("reported $it bpm, outside 6-24", it in 5f..25f)
        }
    }
}

class ActigraphyTest {

    @Test
    fun `stillness scores as sleep and movement scores as wake`() {
        val still = FloatArray(30) { 0f }
        val scores = Actigraphy.scoreEpochs(still)
        assertTrue("all still epochs should score as sleep", scores.all { it.asleep })

        val active = FloatArray(30) { 200f }
        assertTrue("sustained movement should score as wake", Actigraphy.scoreEpochs(active).all { !it.asleep })
    }

    @Test
    fun `a single brief movement does not flip a night to wake`() {
        val counts = FloatArray(40) { 0f }
        counts[20] = 30f
        val scores = Actigraphy.scoreEpochs(counts)
        val wakeEpochs = scores.count { !it.asleep }
        assertTrue("a small isolated movement should not wake the whole night, got $wakeEpochs", wakeEpochs <= 3)
    }

    @Test
    fun `certainty is lower near the decision threshold`() {
        // Construct a count that lands the D statistic near 1.
        val borderline = FloatArray(20) { 1f / (0.00001f * 4035f) / 20f }
        val clear = FloatArray(20) { 0f }

        val borderlineCertainty = Actigraphy.scoreEpochs(borderline)[10].certainty
        val clearCertainty = Actigraphy.scoreEpochs(clear)[10].certainty
        assertTrue(
            "borderline ($borderlineCertainty) should be less certain than clear ($clearCertainty)",
            borderlineCertainty < clearCertainty,
        )
    }

    @Test
    fun `sleep onset needs a sustained run, not one quiet minute`() {
        val counts = FloatArray(60) { 500f }
        // One quiet minute in the middle of restlessness.
        counts[10] = 0f
        assertNull("a single quiet epoch is not sleep onset", Actigraphy.detectSleepOnset(Actigraphy.scoreEpochs(counts)))

        // Now a genuine sustained run.
        for (i in 20 until 60) counts[i] = 0f
        val onset = Actigraphy.detectSleepOnset(Actigraphy.scoreEpochs(counts))
        assertNotNull("a sustained still period is sleep onset", onset)
        assertTrue("onset should be around epoch 20, was $onset", onset!! in 18..30)
    }

    @Test
    fun `activity count rejects gravity and reacts to movement`() {
        // A phone lying still still reads ~9.81 m/s^2 from gravity alone.
        val still = FloatArray(200) { 9.81f }
        val moving = FloatArray(200) { 9.81f + sin(it * 0.5).toFloat() * 2f }

        val stillCount = Actigraphy.activityCount(still)
        val movingCount = Actigraphy.activityCount(moving)

        assertTrue("gravity alone must not register as activity, got $stillCount", stillCount < 1f)
        assertTrue("movement should register, got $movingCount", movingCount > stillCount * 50)
    }

    @Test
    fun `magnitude is the euclidean norm`() {
        assertEquals(5f, Actigraphy.magnitude(3f, 4f, 0f), 1e-4f)
        assertEquals(9.81f, Actigraphy.magnitude(0f, 9.81f, 0f), 1e-4f)
    }

    @Test
    fun `sleep efficiency counts the sleeping share`() {
        val counts = FloatArray(20) { if (it < 10) 0f else 400f }
        val efficiency = Actigraphy.sleepEfficiency(Actigraphy.scoreEpochs(counts))
        assertTrue("roughly half should be sleep, got $efficiency", abs(efficiency - 0.5f) < 0.25f)
    }
}
