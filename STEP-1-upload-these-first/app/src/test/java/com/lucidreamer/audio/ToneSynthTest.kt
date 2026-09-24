// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.audio

import com.lucidreamer.domain.cue.BuiltInTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ToneSynthTest {

    @Test
    fun `every built-in tone renders non-silent audio of a sensible length`() {
        for (tone in BuiltInTone.entries) {
            val pcm = ToneSynth.generate(tone)
            assertEquals("$tone must be mono", 1, pcm.channels)
            assertEquals("$tone sample rate", ToneSynth.SAMPLE_RATE, pcm.sampleRate)
            assertTrue("$tone must not be silent", pcm.samples.any { it.toInt() != 0 })
            assertTrue("$tone should be between 0.5s and 6s, was ${pcm.durationMillis}ms",
                pcm.durationMillis in 500..6000)
        }
    }

    @Test
    fun `tones are normalised to a consistent peak so swapping sounds does not change loudness`() {
        val peaks = BuiltInTone.entries.map { tone ->
            ToneSynth.generate(tone).samples.maxOf { abs(it.toInt()) }
        }
        val target = (0.89 * Short.MAX_VALUE).toInt()
        for (p in peaks) {
            assertTrue("peak $p should be close to $target", abs(p - target) < 400)
        }
    }

    @Test
    fun `tones leave headroom so gain and fades cannot clip`() {
        for (tone in BuiltInTone.entries) {
            val pcm = ToneSynth.generate(tone)
            assertTrue("$tone must not reach full scale", pcm.samples.none { abs(it.toInt()) >= Short.MAX_VALUE.toInt() })
        }
    }

    @Test
    fun `generation is deterministic, including the noise-based tone`() {
        for (tone in BuiltInTone.entries) {
            val a = ToneSynth.generate(tone)
            val b = ToneSynth.generate(tone)
            assertEquals("$tone must regenerate identically", a, b)
        }
    }

    @Test
    fun `tones start near silence so there is no click on playback`() {
        for (tone in BuiltInTone.entries) {
            val pcm = ToneSynth.generate(tone)
            val first = abs(pcm.samples[0].toInt())
            assertTrue("$tone starts at $first, should be near zero", first < 1500)
        }
    }
}

class PcmBufferTest {

    private fun tone(seconds: Double = 1.0, rate: Int = 1000): PcmBuffer =
        PcmBuffer(ShortArray((rate * seconds).toInt()) { 10_000 }, rate, 1)

    @Test
    fun `lead-in prepends exactly the requested silence`() {
        val p = tone(1.0).withLeadIn(500)
        assertEquals(1500, p.frameCount)
        assertTrue("lead-in must be silent", p.samples.take(500).all { it.toInt() == 0 })
        assertTrue("original audio must follow", p.samples.drop(500).all { it.toInt() == 10_000 })
    }

    @Test
    fun `lead-in of zero is a no-op`() {
        val p = tone(1.0)
        assertEquals(p, p.withLeadIn(0))
    }

    @Test
    fun `fades reach near-silence at the edges and full level in the middle`() {
        val p = tone(1.0).withFades(200, 200)
        assertTrue("start must fade in", abs(p.samples.first().toInt()) < 200)
        assertTrue("end must fade out", abs(p.samples.last().toInt()) < 200)
        assertEquals("middle must be untouched", 10_000, p.samples[500].toInt())
    }

    @Test
    fun `fade curve is monotonic`() {
        val p = tone(1.0).withFades(300, 0)
        val ramp = p.samples.take(300).map { it.toInt() }
        assertTrue("fade-in must rise monotonically", ramp.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `fades longer than the buffer do not overflow or corrupt`() {
        val p = tone(0.1).withFades(5000, 5000)
        assertEquals(100, p.frameCount)
        assertTrue(p.samples.all { abs(it.toInt()) <= 10_000 })
    }

    @Test
    fun `gain saturates instead of wrapping around`() {
        val loud = PcmBuffer(shortArrayOf(30_000, -30_000), 1000, 1).withGain(4f)
        assertEquals(Short.MAX_VALUE, loud.samples[0])
        assertEquals(Short.MIN_VALUE, loud.samples[1])
    }

    @Test
    fun `gain of one is a no-op`() {
        val p = tone()
        assertEquals(p, p.withGain(1f))
    }

    @Test
    fun `limitTo truncates and is a no-op when already shorter`() {
        val p = tone(2.0)
        assertEquals(500, p.limitTo(500).frameCount)
        assertEquals(2000, p.limitTo(9000).frameCount)
    }

    @Test
    fun `duration is reported correctly for stereo`() {
        val stereo = PcmBuffer(ShortArray(2000), 1000, 2)
        assertEquals(1000, stereo.frameCount)
        assertEquals(1000L, stereo.durationMillis)
    }

    @Test
    fun `stereo fades scale both channels together`() {
        val stereo = PcmBuffer(ShortArray(2000) { 10_000 }, 1000, 2).withFades(100, 0)
        // Left and right of the same frame must get the same gain.
        for (f in 0 until 100) {
            assertEquals(stereo.samples[f * 2], stereo.samples[f * 2 + 1])
        }
    }

    @Test
    fun `equality compares contents, not array identity`() {
        val a = PcmBuffer(shortArrayOf(1, 2, 3), 8000, 1)
        val b = PcmBuffer(shortArrayOf(1, 2, 3), 8000, 1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
