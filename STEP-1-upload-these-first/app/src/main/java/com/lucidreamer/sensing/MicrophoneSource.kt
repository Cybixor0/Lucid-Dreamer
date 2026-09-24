// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.sensing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.lucidreamer.sensing.dsp.AudioFeatureFrame
import com.lucidreamer.sensing.dsp.AudioFeatures
import com.lucidreamer.sensing.dsp.BreathingAnalyser
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Duty-cycled microphone analysis.
 *
 * ## The privacy design, stated precisely
 *
 * This class is the only thing in the app that opens the microphone during
 * sleep, and it is written so that raw audio provably cannot persist:
 *
 * - Samples are read into **one reusable buffer**, which is overwritten by the
 *   next read. There is no accumulation.
 * - Each window is reduced to a handful of floats - loudness, brightness,
 *   change - and then the buffer is reused. No copy is kept.
 * - The features that survive cannot reconstruct audio and cannot recover
 *   speech. They are summary statistics of a whole second.
 * - Nothing is written to disk. There is no file, no cache, no buffer that
 *   outlives the window.
 * - The app has no `INTERNET` permission, so nothing can be transmitted
 *   regardless.
 *
 * There is no debug mode that records raw audio, deliberately. It would be one
 * bad default away from turning a sleep app into a bedroom recorder, and the
 * feature value is not worth that risk.
 *
 * The microphone is open only [listenSeconds] out of every [epochSeconds],
 * which cuts both battery use and the amount of time it is live.
 */
class MicrophoneSource(
    private val context: Context,
    private val listenSeconds: Int = DEFAULT_LISTEN_SECONDS,
    private val epochSeconds: Int = DEFAULT_EPOCH_SECONDS,
) {
    /** Whether the microphone is open *right now*. Drives the UI indicator. */
    private val listening = AtomicBoolean(false)
    val isListening: Boolean get() = listening.get()

    @Volatile var lastError: String? = null
        private set

    /** Total windows analysed, for diagnostics. */
    @Volatile var windowsAnalysed: Long = 0L
        private set

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    data class Analysis(
        val frames: List<AudioFeatureFrame>,
        val breathing: BreathingAnalyser.Result,
        val meanLevelDbfs: Float,
        /** 0..1, driven by spectral flux - rustling, turning over, footsteps. */
        val movementIndex: Float,
    )

    /**
     * Opens the microphone, analyses [listenSeconds] of audio, closes it, and
     * returns the derived numbers.
     *
     * Suspends for the duration. Cancelling stops the recorder promptly, which
     * is how "disable microphone for tonight" takes effect immediately.
     */
    suspend fun listenOnce(): Analysis? {
        if (!hasPermission()) {
            lastError = "Microphone permission not granted"
            return null
        }

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            lastError = "This device reports no usable audio input configuration"
            return null
        }

        val bufferSize = maxOf(minBuffer, WINDOW_SAMPLES * 2 * 4)
        var recorder: AudioRecord? = null

        return try {
            recorder = AudioRecord(
                // UNPROCESSED where available would be ideal, but it is not
                // universally supported and silently falls back. MIC is the
                // dependable choice.
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferSize,
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                lastError = "Could not open the microphone (it may be in use by another app)"
                return null
            }

            recorder.startRecording()
            listening.set(true)
            lastError = null

            analyse(recorder)
        } catch (e: SecurityException) {
            lastError = "Microphone access denied: ${e.message}"
            null
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            null
        } finally {
            listening.set(false)
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
        }
    }

    private suspend fun analyse(recorder: AudioRecord): Analysis {
        // The single reusable buffer. Every read overwrites it; nothing else
        // ever holds raw samples.
        val shorts = ShortArray(WINDOW_SAMPLES)
        val floats = FloatArray(WINDOW_SAMPLES)

        val frames = mutableListOf<AudioFeatureFrame>()
        val envelope = mutableListOf<Float>()
        var previousSpectrum: FloatArray? = null

        val windows = (listenSeconds * SAMPLE_RATE) / WINDOW_SAMPLES
        var fluxSum = 0f
        var fluxCount = 0

        for (i in 0 until windows) {
            if (!coroutineContext.isActive) break

            val read = recorder.read(shorts, 0, WINDOW_SAMPLES)
            if (read <= 0) {
                // A transient read failure should not abort the whole epoch.
                delay(20)
                continue
            }

            for (s in 0 until read) {
                floats[s] = shorts[s] / 32768f
            }
            val window = if (read == WINDOW_SAMPLES) floats else floats.copyOf(read)

            val (frame, spectrum) = AudioFeatures.extract(
                window,
                SAMPLE_RATE,
                System.currentTimeMillis(),
                previousSpectrum,
            )
            previousSpectrum = spectrum
            windowsAnalysed++

            frames += frame
            envelope += frame.rms
            fluxSum += frame.spectralFlux
            fluxCount++

            // `shorts` and `floats` are reused on the next iteration. At no
            // point does a copy of the audio outlive this loop body.
        }

        val envelopeHz = SAMPLE_RATE.toFloat() / WINDOW_SAMPLES
        val breathing = BreathingAnalyser.analyse(envelope.toFloatArray(), envelopeHz)

        val meanLevel = if (frames.isEmpty()) {
            AudioFeatureFrame.SILENCE_DBFS
        } else {
            frames.map { it.levelDbfs }.average().toFloat()
        }

        val movement = if (fluxCount == 0) 0f else {
            (fluxSum / fluxCount / FLUX_MOVEMENT_SCALE).coerceIn(0f, 1f)
        }

        return Analysis(
            // Only the last few frames are retained, and only for the developer
            // screen. Keeping every frame for a whole night would be a large
            // amount of data about someone's bedroom for no real benefit.
            frames = frames.takeLast(4),
            breathing = breathing,
            meanLevelDbfs = meanLevel,
            movementIndex = movement,
        )
    }

    /** How long to wait after a listen window before the next one. */
    fun idleMillis(): Long = ((epochSeconds - listenSeconds).coerceAtLeast(0)) * 1000L

    companion object {
        /**
         * 16 kHz is plenty. Breathing and movement live far below 8 kHz, and a
         * lower rate means less CPU, less battery and a smaller FFT.
         */
        const val SAMPLE_RATE = 16_000

        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 1024 samples at 16 kHz = 64 ms, giving a ~15.6 Hz envelope. */
        const val WINDOW_SAMPLES = 1024

        /**
         * Listen 30s out of every 60s.
         *
         * Thirty seconds is enough envelope for the breathing autocorrelation
         * to have something to work with (it needs several cycles), while
         * halving both battery cost and microphone-open time.
         */
        const val DEFAULT_LISTEN_SECONDS = 30
        const val DEFAULT_EPOCH_SECONDS = 60

        const val FLUX_MOVEMENT_SCALE = 0.15f
    }
}
