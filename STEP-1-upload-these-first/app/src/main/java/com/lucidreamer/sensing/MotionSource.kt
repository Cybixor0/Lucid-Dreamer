// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.sensing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.lucidreamer.sensing.dsp.Actigraphy
import java.util.concurrent.atomic.AtomicReference

/**
 * Accelerometer sampling for actigraphy.
 *
 * ## Batching, and why it matters here
 *
 * The accelerometer is registered with a large `maxReportLatencyUs`, which asks
 * the sensor hardware to accumulate readings in its own FIFO and deliver them
 * in bursts. That lets the application processor stay asleep between bursts
 * instead of being woken for every sample, which is the difference between a
 * few percent of battery overnight and a flat phone by morning.
 *
 * The trade-off is that readings arrive late and in clumps. That is fine for
 * actigraphy, which scores whole minutes, and it is precisely why cue *timing*
 * never depends on this class.
 *
 * Note the accelerometer here is deliberately a non-wake-up sensor: it must not
 * wake the device on its own. If the FIFO overflows during a long suspend, old
 * samples are dropped, and the epoch simply has fewer readings behind it.
 */
class MotionSource(private val context: Context) : SensorEventListener {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val available: Boolean get() = accelerometer != null

    /**
     * Magnitudes accumulated since the last epoch boundary.
     *
     * Held in an [AtomicReference] because sensor callbacks arrive on the
     * sensor thread while epochs are consumed from a coroutine.
     */
    private val buffer = AtomicReference(ArrayList<Float>())

    @Volatile private var running = false

    /** Total samples seen, for diagnostics. */
    @Volatile var samplesSeen: Long = 0L
        private set

    fun start(): Boolean {
        val manager = sensorManager ?: return false
        val sensor = accelerometer ?: return false
        if (running) return true

        val ok = manager.registerListener(
            this,
            sensor,
            SAMPLING_PERIOD_US,
            MAX_REPORT_LATENCY_US,
        )
        running = ok
        return ok
    }

    fun stop() {
        if (!running) return
        sensorManager?.unregisterListener(this)
        running = false
        buffer.set(ArrayList())
    }

    /**
     * Takes everything accumulated since the last call and scores it as one epoch.
     *
     * @return the activity count, or null if no readings arrived - which is
     *   normal when the device was suspended and the FIFO was empty.
     */
    fun takeEpochActivityCount(): Float? {
        val samples = buffer.getAndSet(ArrayList())
        if (samples.size < MIN_SAMPLES_PER_EPOCH) return null
        return Actigraphy.activityCount(samples.toFloatArray())
    }

    /**
     * A 0..1 view of recent movement, without consuming the buffer.
     *
     * Used as a soft input to the stage estimator, separately from the
     * minute-scored actigraphy.
     */
    fun peekMovementIndex(): Float? {
        val samples = buffer.get()
        if (samples.size < MIN_SAMPLES_PER_EPOCH) return null
        val count = Actigraphy.activityCount(samples.toFloatArray())
        return (count / MOVEMENT_INDEX_SCALE).coerceIn(0f, 1f)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val magnitude = Actigraphy.magnitude(event.values[0], event.values[1], event.values[2])
        samplesSeen++

        val current = buffer.get()
        // Bounded: a long batched burst after a suspend could otherwise deliver
        // an enormous backlog, and an unbounded list in a background service is
        // how you get an overnight OutOfMemoryError.
        if (current.size < MAX_BUFFERED_SAMPLES) current.add(magnitude)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** ~5 Hz. Actigraphy scores minutes; anything faster is wasted power. */
        const val SAMPLING_PERIOD_US = 200_000

        /**
         * Let the hardware buffer up to 60 seconds before waking the CPU.
         * Matches the epoch length, so typically one wake-up per epoch.
         */
        const val MAX_REPORT_LATENCY_US = 60_000_000

        const val MIN_SAMPLES_PER_EPOCH = 10
        const val MAX_BUFFERED_SAMPLES = 20_000

        /** Scales a typical roll-over to roughly 1.0. */
        const val MOVEMENT_INDEX_SCALE = 400f
    }
}
