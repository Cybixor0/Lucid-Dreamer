// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.sensing.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * In-place radix-2 FFT.
 *
 * Written out rather than pulled in as a dependency: it is about eighty lines,
 * it is the only transform this app needs, and keeping it here means the whole
 * signal-processing path is readable source in the repository rather than an
 * opaque jar. It also runs on a phone in the middle of the night, so being able
 * to see exactly what it costs matters.
 */
object Fft {

    /**
     * Transforms [real] and [imag] in place. Length must be a power of two.
     *
     * Iterative Cooley-Tukey: bit-reversal permutation first, then log2(n)
     * butterfly passes. No allocation beyond the caller's arrays.
     */
    fun transform(real: FloatArray, imag: FloatArray) {
        val n = real.size
        require(n == imag.size) { "real and imaginary parts must be the same length" }
        require(n > 0 && n and (n - 1) == 0) { "length must be a power of two, was $n" }
        if (n == 1) return

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                real[i] = real[j].also { real[j] = real[i] }
                imag[i] = imag[j].also { imag[j] = imag[i] }
            }
        }

        // Butterfly passes.
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wReal = cos(angle).toFloat()
            val wImag = sin(angle).toFloat()

            var i = 0
            while (i < n) {
                var curReal = 1f
                var curImag = 0f
                for (k in 0 until len / 2) {
                    val uReal = real[i + k]
                    val uImag = imag[i + k]
                    val vReal = real[i + k + len / 2] * curReal - imag[i + k + len / 2] * curImag
                    val vImag = real[i + k + len / 2] * curImag + imag[i + k + len / 2] * curReal

                    real[i + k] = uReal + vReal
                    imag[i + k] = uImag + vImag
                    real[i + k + len / 2] = uReal - vReal
                    imag[i + k + len / 2] = uImag - vImag

                    val nextReal = curReal * wReal - curImag * wImag
                    curImag = curReal * wImag + curImag * wReal
                    curReal = nextReal
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Magnitude spectrum of a real signal, up to Nyquist.
     *
     * The input is copied, windowed and zero-padded to the next power of two,
     * so the caller's buffer is untouched and any frame length works.
     */
    fun magnitudeSpectrum(samples: FloatArray, window: Window = Window.HANN): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val n = nextPowerOfTwo(samples.size)
        val real = FloatArray(n)
        val imag = FloatArray(n)

        for (i in samples.indices) {
            real[i] = samples[i] * window.coefficient(i, samples.size)
        }

        transform(real, imag)

        val bins = n / 2
        return FloatArray(bins) { sqrt(real[it] * real[it] + imag[it] * imag[it]) }
    }

    /** Frequency in Hz at the centre of spectrum bin [index]. */
    fun binToHz(index: Int, binCount: Int, sampleRate: Int): Float =
        index * sampleRate / (2f * binCount)

    fun nextPowerOfTwo(v: Int): Int {
        var n = 1
        while (n < v) n = n shl 1
        return n
    }

    /**
     * Analysis windows.
     *
     * A rectangular window leaks badly across bins, which matters here because
     * the breathing analysis is looking for a weak peak at a very low frequency
     * next to a large DC component. Hann is the sensible default.
     */
    enum class Window {
        RECTANGULAR,
        HANN;

        fun coefficient(i: Int, length: Int): Float = when (this) {
            RECTANGULAR -> 1f
            HANN -> if (length <= 1) 1f else {
                (0.5 - 0.5 * cos(2.0 * PI * i / (length - 1))).toFloat()
            }
        }
    }
}
