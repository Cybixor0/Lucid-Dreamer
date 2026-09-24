// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.audio

import kotlin.math.cos
import kotlin.math.min

/**
 * Decoded 16-bit PCM held in memory.
 *
 * Cues are decoded once when the session arms and kept as plain shorts for the
 * rest of the night. A two-second mono cue at 44.1kHz is about 170KB, so a
 * whole night's worth is trivially resident, and at 04:37 playback touches no
 * disk, no codec and no long-lived native object - the three things most likely
 * to fail after eight hours idle.
 */
data class PcmBuffer(
    val samples: ShortArray,
    val sampleRate: Int,
    val channels: Int,
) {
    val frameCount: Int get() = samples.size / channels
    val durationMillis: Long get() = frameCount * 1000L / sampleRate

    /**
     * Prepends digital silence.
     *
     * Bluetooth earbuds take a few hundred milliseconds to come out of low-power
     * sniff mode, and the audio path itself needs warming. Without a run-up the
     * front of a short cue is simply missing - which for a one-second bell can
     * mean the whole thing. Costs nothing and fixes a class of "it didn't play"
     * reports that are otherwise impossible to diagnose.
     */
    fun withLeadIn(millis: Int): PcmBuffer {
        if (millis <= 0) return this
        val leadFrames = sampleRate * millis / 1000
        val out = ShortArray(leadFrames * channels + samples.size)
        samples.copyInto(out, destinationOffset = leadFrames * channels)
        return copy(samples = out)
    }

    /** Truncates to at most [millis], keeping whole frames. */
    fun limitTo(millis: Long): PcmBuffer {
        if (millis <= 0) return this
        val maxFrames = (sampleRate * millis / 1000).toInt()
        if (maxFrames >= frameCount) return this
        return copy(samples = samples.copyOfRange(0, maxFrames * channels))
    }

    /**
     * Applies fade in/out with an equal-power (cosine) curve.
     *
     * A linear ramp on amplitude sounds like a jump, because loudness is not
     * linear in amplitude. For a cue meant to surface gently into a dream
     * without waking the sleeper, the shape of the fade matters more than its
     * length.
     */
    fun withFades(fadeInMillis: Int, fadeOutMillis: Int): PcmBuffer {
        val n = frameCount
        if (n == 0) return this
        val inFrames = min(sampleRate * fadeInMillis / 1000, n)
        val outFrames = min(sampleRate * fadeOutMillis / 1000, n - min(inFrames, n))
        if (inFrames <= 0 && outFrames <= 0) return this

        val out = samples.copyOf()
        for (f in 0 until inFrames) {
            val g = raisedCosine(f.toDouble() / inFrames)
            scaleFrame(out, f, g)
        }
        for (f in 0 until outFrames) {
            val g = raisedCosine(f.toDouble() / outFrames)
            scaleFrame(out, n - 1 - f, g)
        }
        return copy(samples = out)
    }

    /** Applies a constant gain, saturating rather than wrapping on overflow. */
    fun withGain(gain: Float): PcmBuffer {
        if (gain == 1f) return this
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            out[i] = clamp16((samples[i] * gain).toInt())
        }
        return copy(samples = out)
    }

    private fun scaleFrame(buf: ShortArray, frame: Int, gain: Double) {
        val base = frame * channels
        for (c in 0 until channels) {
            val i = base + c
            if (i < buf.size) buf[i] = clamp16((buf[i] * gain).toInt())
        }
    }

    // ShortArray gives identity equals/hashCode by default, which would make
    // two identical buffers compare unequal. Worth overriding since these are
    // data-class values that get compared in tests.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcmBuffer) return false
        return sampleRate == other.sampleRate &&
            channels == other.channels &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int =
        (samples.contentHashCode() * 31 + sampleRate) * 31 + channels

    companion object {
        /** Rises 0 -> 1 with zero slope at both ends. */
        internal fun raisedCosine(x: Double): Double = 0.5 - 0.5 * cos(Math.PI * x.coerceIn(0.0, 1.0))

        internal fun clamp16(v: Int): Short = when {
            v > Short.MAX_VALUE -> Short.MAX_VALUE
            v < Short.MIN_VALUE -> Short.MIN_VALUE
            else -> v.toShort()
        }
    }
}
