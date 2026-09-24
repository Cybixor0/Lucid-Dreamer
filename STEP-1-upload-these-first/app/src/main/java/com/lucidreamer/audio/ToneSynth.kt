// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.audio

import com.lucidreamer.domain.cue.BuiltInTone
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates the built-in cue sounds on the device.
 *
 * The repository deliberately contains no audio files. Everything the app can
 * play by default is synthesised from the code in this file, which means every
 * byte of what the app will play into your ear at 4am is readable, reviewable
 * source rather than an opaque binary blob. It also keeps the APK small and
 * lets the user regenerate the set if they want to tweak it.
 *
 * The tones are designed to be *noticeable without being startling*: no sharp
 * attacks, no broadband transients, nothing that sounds like an alarm. A cue
 * that jolts you awake has failed at its job.
 */
object ToneSynth {

    const val SAMPLE_RATE = 44_100

    fun generate(tone: BuiltInTone): PcmBuffer = when (tone) {
        BuiltInTone.SOFT_BELL -> softBell()
        BuiltInTone.PURE_TONE -> pureTone()
        BuiltInTone.CHIME -> chime()
        BuiltInTone.LOW_DRONE -> lowDrone()
        BuiltInTone.NOISE_SWELL -> noiseSwell()
        BuiltInTone.DOUBLE_BEEP -> doubleBeep()
    }

    /**
     * A struck bell.
     *
     * Bells are inharmonic - their partials are not integer multiples of the
     * fundamental, which is exactly why they sound like bells and not like
     * organs. These ratios are the classic tubular-bell set. Higher partials
     * decay faster, as they do physically, which gives the strike its bright
     * onset and warm tail.
     */
    private fun softBell(baseHz: Double = 587.33, seconds: Double = 2.6): PcmBuffer {
        val partials = listOf(
            Partial(ratio = 1.00, amp = 1.00, decay = 1.6),
            Partial(ratio = 2.76, amp = 0.62, decay = 2.4),
            Partial(ratio = 5.40, amp = 0.34, decay = 3.4),
            Partial(ratio = 8.93, amp = 0.17, decay = 4.8),
            Partial(ratio = 13.34, amp = 0.08, decay = 6.5),
        )
        return render(seconds) { t ->
            // A short attack ramp avoids a click at sample zero.
            val attack = (t / 0.008).coerceAtMost(1.0)
            attack * partials.sumOf { p ->
                p.amp * exp(-p.decay * t) * sin(2 * PI * baseHz * p.ratio * t)
            } / 2.2
        }
    }

    /** A single sine under a raised-cosine envelope: about as gentle as sound gets. */
    private fun pureTone(hz: Double = 440.0, seconds: Double = 1.6): PcmBuffer =
        render(seconds) { t ->
            val env = PcmBuffer.raisedCosine(minOf(t / (seconds / 2), (seconds - t) / (seconds / 2)))
            env * sin(2 * PI * hz * t)
        }

    /** Three bells in a gentle descending figure. */
    private fun chime(): PcmBuffer {
        val notes = listOf(783.99 to 0.0, 659.25 to 0.28, 523.25 to 0.56) // G5, E5, C5
        val seconds = 3.2
        val partials = listOf(
            Partial(1.00, 1.00, 2.0),
            Partial(2.76, 0.45, 3.0),
            Partial(5.40, 0.20, 4.2),
        )
        return render(seconds) { t ->
            notes.sumOf { (hz, start) ->
                val local = t - start
                if (local < 0) 0.0 else {
                    val attack = (local / 0.008).coerceAtMost(1.0)
                    attack * partials.sumOf { p ->
                        p.amp * exp(-p.decay * local) * sin(2 * PI * hz * p.ratio * local)
                    }
                }
            } / 3.4
        }
    }

    /**
     * A low, slow swell.
     *
     * Two slightly detuned oscillators beat against each other, which makes the
     * tone feel alive rather than electronic. Deliberately low-frequency: easy
     * to hear without being sharp, and much less likely to wake a partner.
     */
    private fun lowDrone(hz: Double = 110.0, seconds: Double = 4.0): PcmBuffer =
        render(seconds) { t ->
            val env = PcmBuffer.raisedCosine(minOf(t / 1.2, (seconds - t) / 1.2))
            val a = sin(2 * PI * hz * t)
            val b = sin(2 * PI * (hz * 1.006) * t) // ~0.66 Hz beat
            val harmonic = 0.25 * sin(2 * PI * hz * 2 * t)
            env * (a + b + harmonic) / 2.3
        }

    /**
     * A breath of filtered noise.
     *
     * White noise run through a one-pole low-pass, which rolls off the harsh
     * high end and leaves something closer to distant surf. The fixed seed
     * keeps the sound identical between generations, so a user who calibrated
     * their volume against it is not surprised later.
     */
    private fun noiseSwell(seconds: Double = 3.0): PcmBuffer {
        val rnd = Random(0xB0A7)
        var lp = 0.0
        val alpha = 0.06
        return render(seconds) { t ->
            val white = rnd.nextDouble(-1.0, 1.0)
            lp += alpha * (white - lp)
            val env = PcmBuffer.raisedCosine(minOf(t / 1.0, (seconds - t) / 1.0))
            env * lp * 3.0
        }
    }

    /** Two short, soft pips. The most "notification-like" option, for people who want that. */
    private fun doubleBeep(hz: Double = 880.0, seconds: Double = 0.9): PcmBuffer {
        val beep = 0.12
        val gap = 0.18
        return render(seconds) { t ->
            val inFirst = t < beep
            val inSecond = t >= beep + gap && t < beep + gap + beep
            if (!inFirst && !inSecond) 0.0 else {
                val local = if (inFirst) t else t - beep - gap
                val env = PcmBuffer.raisedCosine(minOf(local / 0.02, (beep - local) / 0.02))
                env * sin(2 * PI * hz * local) * 0.8
            }
        }
    }

    private data class Partial(val ratio: Double, val amp: Double, val decay: Double)

    /**
     * Renders a mono buffer from a function of time in seconds.
     *
     * Output is normalised to a consistent peak so that every built-in tone is
     * roughly equally loud at the same configured gain. Without this, choosing
     * a different cue sound would silently change how loud your night is.
     */
    private fun render(seconds: Double, fn: (Double) -> Double): PcmBuffer {
        val n = (SAMPLE_RATE * seconds).toInt()
        val raw = DoubleArray(n) { fn(it.toDouble() / SAMPLE_RATE) }

        var peak = 0.0
        for (v in raw) { val a = kotlin.math.abs(v); if (a > peak) peak = a }
        val norm = if (peak > 1e-9) TARGET_PEAK / peak else 0.0

        val out = ShortArray(n)
        for (i in 0 until n) {
            out[i] = PcmBuffer.clamp16((raw[i] * norm * Short.MAX_VALUE).toInt())
        }
        return PcmBuffer(out, SAMPLE_RATE, channels = 1)
    }

    /** Leaves headroom so per-cue gain and fades cannot clip. */
    private const val TARGET_PEAK = 0.89
}
