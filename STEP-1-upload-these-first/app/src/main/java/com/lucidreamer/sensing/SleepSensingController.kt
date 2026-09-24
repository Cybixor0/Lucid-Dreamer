// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.sensing

import android.content.Context
import com.lucidreamer.core.DeviceStatus
import com.lucidreamer.core.EventLog
import com.lucidreamer.sensing.dsp.Actigraphy
import com.lucidreamer.sensing.dsp.BreathingAnalyser
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Runs the sensing loop for a night and turns it into stage estimates.
 *
 * ## What this does not do
 *
 * It does not schedule anything, and cue timing never waits on it. If this
 * whole class fails - no permission, no sensor, the loop suspended by Doze,
 * the process killed - the night's cues still fire exactly as planned. That is
 * a deliberate structural choice, not merely defensive coding: on modern
 * Android a background-started foreground service **cannot** acquire microphone
 * access at all, so sensing has to be an optional refinement layered over a
 * statically-armed schedule rather than a prerequisite for it.
 *
 * ## Epoch timing
 *
 * Actigraphy needs two epochs of lookahead (the Cole-Kripke weights extend
 * forwards as well as backwards), so scored output lags real time by about two
 * minutes. That is fine for placing a cue within a window; it would not be fine
 * for firing one, which is another reason cue timing lives elsewhere.
 */
class SleepSensingController(
    private val context: Context,
    private val log: EventLog,
    private val deviceStatus: DeviceStatus,
) {
    private val motion = MotionSource(context)
    private var microphone: MicrophoneSource? = null
    private val estimator = SleepStageEstimator()

    private val _state = MutableStateFlow(SensingState())
    val state: StateFlow<SensingState> = _state.asStateFlow()

    /** Rolling activity counts, most recent last. */
    private val activityCounts = ArrayDeque<Float>()

    data class SensingState(
        val mode: SensingMode = SensingMode.OFF,
        val running: Boolean = false,
        val microphoneOpen: Boolean = false,
        val motionAvailable: Boolean = false,
        val epochsCompleted: Int = 0,
        val latest: StageEstimate? = null,
        val lastBreathing: BreathingAnalyser.Result? = null,
        /** Set when a mode had to be reduced, with the reason. */
        val degradedReason: String? = null,
    )

    fun motionAvailable(): Boolean = motion.available

    /**
     * Runs until the calling coroutine is cancelled.
     *
     * @param allowMicrophone false when the session was started from the
     *   background. Android refuses microphone access to a foreground service
     *   that was itself started from the background, so rather than crashing
     *   with a SecurityException the caller tells us, and we degrade to motion
     *   and say so.
     * @param onEstimate called once per epoch with the newest estimate
     */
    suspend fun run(
        mode: SensingMode,
        sessionStartMillis: Long,
        expectedNightMinutes: Int,
        allowMicrophone: Boolean,
        onEstimate: suspend (StageEstimate) -> Unit,
    ) {
        if (mode == SensingMode.OFF) return

        var effectiveMode = mode
        var degraded: String? = null

        if (mode.usesMicrophone && !allowMicrophone) {
            effectiveMode = if (mode == SensingMode.COMBINED) SensingMode.MOTION else SensingMode.OFF
            degraded = "The microphone cannot be used because tonight's session started " +
                "automatically rather than from the app. Android only grants microphone access to " +
                "a service started while the app is open."
            log.w(EventLog.TAG_SENSING, degraded)
        }

        if (effectiveMode.usesMicrophone) {
            val mic = MicrophoneSource(context)
            if (!mic.hasPermission()) {
                effectiveMode = if (effectiveMode == SensingMode.COMBINED) SensingMode.MOTION else SensingMode.OFF
                degraded = "Microphone permission has not been granted, so audio sensing is off."
                log.w(EventLog.TAG_SENSING, degraded)
            } else {
                microphone = mic
            }
        }

        if (effectiveMode.usesMotion && !motion.available) {
            effectiveMode = if (effectiveMode == SensingMode.COMBINED) SensingMode.MICROPHONE else SensingMode.OFF
            degraded = "This device has no usable accelerometer."
            log.w(EventLog.TAG_SENSING, degraded ?: "")
        }

        if (effectiveMode == SensingMode.OFF) {
            _state.value = SensingState(mode = SensingMode.OFF, degradedReason = degraded)
            log.w(EventLog.TAG_SENSING, "Sensing disabled for tonight: ${degraded ?: "no usable sensors"}")
            return
        }

        if (!deviceStatus.isIgnoringBatteryOptimisations()) {
            // Without the exemption the platform ignores our wake lock in deep
            // Doze and this loop simply stops ticking for long stretches. Worth
            // recording, because it explains a sparse night in the morning.
            log.w(
                EventLog.TAG_SENSING,
                "Battery optimisation is active - sensing will have gaps whenever the device " +
                    "suspends, and estimates will be correspondingly patchy",
            )
        }

        estimator.reset()
        activityCounts.clear()

        if (effectiveMode.usesMotion && !motion.start()) {
            log.w(EventLog.TAG_SENSING, "Could not register the accelerometer")
        }

        _state.value = SensingState(
            mode = effectiveMode,
            running = true,
            motionAvailable = motion.available,
            degradedReason = degraded,
        )
        log.i(EventLog.TAG_SENSING, "Sensing started in ${effectiveMode.label} mode")

        try {
            var epoch = 0
            while (coroutineContext.isActive) {
                val epochStart = System.currentTimeMillis()

                val micAnalysis = if (effectiveMode.usesMicrophone) {
                    _state.value = _state.value.copy(microphoneOpen = true)
                    val result = microphone?.listenOnce()
                    _state.value = _state.value.copy(microphoneOpen = false)
                    result
                } else {
                    null
                }

                val activityCount = if (effectiveMode.usesMotion) motion.takeEpochActivityCount() else null
                activityCount?.let {
                    activityCounts.addLast(it)
                    while (activityCounts.size > MAX_EPOCH_HISTORY) activityCounts.removeFirst()
                }

                val scored = scoreCurrentEpoch()
                val minutesSinceOnset = ((epochStart - sessionStartMillis) / 60_000L).toInt()

                val estimate = estimator.estimate(
                    StageInputs(
                        atMillis = epochStart,
                        minutesSinceOnset = minutesSinceOnset,
                        expectedNightMinutes = expectedNightMinutes,
                        motionAsleep = scored?.asleep,
                        motionCertainty = scored?.certainty ?: 0f,
                        movementIndex = micAnalysis?.movementIndex ?: motion.peekMovementIndex(),
                        breathingRegularity = micAnalysis?.breathing?.regularity,
                        breathingConfidence = micAnalysis?.breathing?.confidence ?: 0f,
                        levelDbfs = micAnalysis?.meanLevelDbfs,
                    ),
                )

                epoch++
                _state.value = _state.value.copy(
                    epochsCompleted = epoch,
                    latest = estimate,
                    lastBreathing = micAnalysis?.breathing,
                )

                onEstimate(estimate)

                // Sleep out the rest of the epoch. In deep Doze without a
                // battery exemption this delay will overrun badly - which is
                // expected and is exactly why the gap shows up in the morning
                // report rather than being hidden.
                val elapsed = System.currentTimeMillis() - epochStart
                val remaining = EPOCH_MILLIS - elapsed
                if (remaining > 0) delay(remaining)
            }
        } finally {
            motion.stop()
            microphone = null
            _state.value = _state.value.copy(running = false, microphoneOpen = false)
            log.i(EventLog.TAG_SENSING, "Sensing stopped after ${_state.value.epochsCompleted} epochs")
        }
    }

    /**
     * Scores the epoch that now has enough lookahead behind it.
     *
     * Cole-Kripke needs two epochs after the one being scored, so this returns
     * a verdict about roughly two minutes ago, not about now.
     */
    private fun scoreCurrentEpoch(): Actigraphy.EpochScore? {
        if (activityCounts.size < Actigraphy.LOOKAHEAD + 1) return null
        val counts = activityCounts.toFloatArray()
        val index = counts.size - 1 - Actigraphy.LOOKAHEAD
        return Actigraphy.scoreEpoch(counts, index)
    }

    /** Current estimate, or an explicit unknown. Never a fabricated value. */
    fun currentEstimate(): StageEstimate =
        _state.value.latest ?: StageEstimate.unknown(
            System.currentTimeMillis(),
            _state.value.degradedReason ?: "Sleep sensing is not running",
        )

    private companion object {
        const val EPOCH_MILLIS = 60_000L

        /** Two hours of context is far more than Cole-Kripke needs. */
        const val MAX_EPOCH_HISTORY = 120
    }
}
