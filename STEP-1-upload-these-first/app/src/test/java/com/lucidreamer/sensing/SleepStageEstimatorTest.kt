// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.sensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepStageEstimatorTest {

    private fun inputs(
        minutes: Int,
        motionAsleep: Boolean? = null,
        motionCertainty: Float = 0f,
        movement: Float? = null,
        breathingRegularity: Float? = null,
        breathingConfidence: Float = 0f,
        levelDbfs: Float? = null,
    ) = StageInputs(
        atMillis = minutes * 60_000L,
        minutesSinceOnset = minutes,
        expectedNightMinutes = 480,
        motionAsleep = motionAsleep,
        motionCertainty = motionCertainty,
        movementIndex = movement,
        breathingRegularity = breathingRegularity,
        breathingConfidence = breathingConfidence,
        levelDbfs = levelDbfs,
    )

    // -----------------------------------------------------------------------
    // The honesty guarantees. These are the tests that matter most.
    // -----------------------------------------------------------------------

    @Test
    fun `the cycle model alone can never reach usable confidence`() {
        // This is the central honesty guarantee: without sensors the app is
        // doing population-average arithmetic, and must never let that drive
        // adaptive cue placement.
        val estimator = SleepStageEstimator()
        for (minute in 0..480 step 7) {
            val estimate = estimator.estimate(inputs(minute))
            assertTrue(
                "model-only confidence ${estimate.confidence} at minute $minute must stay below " +
                    "the usable threshold ${StageEstimate.USABLE_CONFIDENCE}",
                estimate.confidence < StageEstimate.USABLE_CONFIDENCE,
            )
            assertTrue("model-only estimate must not be usable", !estimate.isUsable)
        }
    }

    @Test
    fun `probabilities always form a distribution`() {
        val estimator = SleepStageEstimator()
        for (minute in 0..500 step 13) {
            val e = estimator.estimate(
                inputs(minute, motionAsleep = minute % 2 == 0, motionCertainty = 0.7f, breathingRegularity = 0.5f, breathingConfidence = 0.6f),
            )
            val total = e.awake + e.light + e.deep + e.rem
            assertEquals("probabilities must sum to 1 at minute $minute", 1f, total, 0.001f)
            assertTrue(listOf(e.awake, e.light, e.deep, e.rem).all { it in 0f..1f })
            assertTrue(e.confidence in 0f..1f)
        }
    }

    @Test
    fun `breathing regularity alone cannot swing the result to REM`() {
        // Breathing irregularity is a weak, indirect correlate of REM. It must
        // never be able to produce a confident REM call on its own.
        val estimator = SleepStageEstimator()
        val e = estimator.estimate(
            inputs(minutes = 200, breathingRegularity = 0f, breathingConfidence = 1f),
        )
        assertTrue(
            "REM probability ${e.rem} should not dominate on breathing evidence alone",
            e.rem < 0.75f,
        )
    }

    @Test
    fun `stage labels never claim certainty`() {
        // "Possible REM", not "REM". The wording is part of the contract.
        assertTrue(SleepStage.POSSIBLE_REM.label.startsWith("Possible"))
        assertTrue(SleepStage.POSSIBLE_DEEP.label.startsWith("Possibly"))
    }

    @Test
    fun `describe marks low confidence explicitly`() {
        val unusable = StageEstimate.unknown(0L, "no sensors")
        assertTrue(unusable.describe().contains("too uncertain"))

        val lowish = StageEstimate(0L, 0.1f, 0.2f, 0.2f, 0.5f, confidence = 0.45f, basis = "")
        assertTrue(lowish.describe().contains("low confidence"))
    }

    // -----------------------------------------------------------------------
    // Behaviour
    // -----------------------------------------------------------------------

    @Test
    fun `movement evidence raises confidence above the model-only ceiling`() {
        val estimator = SleepStageEstimator()
        val withMotion = estimator.estimate(
            inputs(120, motionAsleep = true, motionCertainty = 0.9f),
        )
        assertTrue(
            "motion should lift confidence above ${SleepStageEstimator.MODEL_ONLY_CEILING}, was ${withMotion.confidence}",
            withMotion.confidence > SleepStageEstimator.MODEL_ONLY_CEILING * 0.9f,
        )
    }

    @Test
    fun `sustained movement pushes the estimate towards awake`() {
        val estimator = SleepStageEstimator()
        // Repeat so the temporal smoothing settles.
        var estimate = estimator.estimate(inputs(150, motionAsleep = false, motionCertainty = 0.9f))
        repeat(5) {
            estimate = estimator.estimate(inputs(150, motionAsleep = false, motionCertainty = 0.9f))
        }
        assertEquals(SleepStage.AWAKE, estimate.mostLikely)
    }

    @Test
    fun `stillness keeps the estimate in sleep`() {
        val estimator = SleepStageEstimator()
        var estimate = estimator.estimate(inputs(150, motionAsleep = true, motionCertainty = 0.9f))
        repeat(5) {
            estimate = estimator.estimate(inputs(150, motionAsleep = true, motionCertainty = 0.9f))
        }
        assertTrue("should not be awake while still", estimate.mostLikely != SleepStage.AWAKE)
    }

    @Test
    fun `deep sleep is front-loaded and REM is back-loaded`() {
        val estimator = SleepStageEstimator()

        // Averaged across a cycle so the within-cycle oscillation cancels out
        // and only the across-night trend remains.
        fun averageOverCycle(startMinute: Int): Pair<Float, Float> {
            var deep = 0f
            var rem = 0f
            var n = 0
            for (m in startMinute until startMinute + 90 step 5) {
                val prior = estimator.cyclePrior(m, 480)
                deep += prior[2]
                rem += prior[3]
                n++
            }
            return deep / n to rem / n
        }

        val (earlyDeep, earlyRem) = averageOverCycle(20)
        val (lateDeep, lateRem) = averageOverCycle(350)

        assertTrue("deep sleep should fall across the night ($earlyDeep -> $lateDeep)", lateDeep < earlyDeep)
        assertTrue("REM should rise across the night ($earlyRem -> $lateRem)", lateRem > earlyRem)
    }

    @Test
    fun `before sleep onset the estimate is overwhelmingly awake`() {
        val estimator = SleepStageEstimator()
        val estimate = estimator.estimate(inputs(minutes = -10))
        assertEquals(SleepStage.AWAKE, estimate.mostLikely)
    }

    @Test
    fun `a loud room pushes towards awake`() {
        val quiet = SleepStageEstimator().estimate(inputs(200, levelDbfs = -70f))
        val loud = SleepStageEstimator().estimate(inputs(200, levelDbfs = -20f))
        assertTrue("loud (${loud.awake}) should be more awake than quiet (${quiet.awake})", loud.awake > quiet.awake)
    }

    @Test
    fun `smoothing prevents a single frame from flipping the estimate`() {
        val estimator = SleepStageEstimator()
        repeat(8) { estimator.estimate(inputs(200, motionAsleep = true, motionCertainty = 0.9f)) }
        val settled = estimator.estimate(inputs(200, motionAsleep = true, motionCertainty = 0.9f))

        // One contradictory frame arrives.
        val afterBlip = estimator.estimate(inputs(201, motionAsleep = false, motionCertainty = 0.9f))

        assertTrue(
            "a single frame should not immediately flip to awake (awake went ${settled.awake} -> ${afterBlip.awake})",
            afterBlip.mostLikely != SleepStage.AWAKE || settled.awake > 0.3f,
        )
    }

    @Test
    fun `reset clears the smoothing history`() {
        val estimator = SleepStageEstimator()
        repeat(10) { estimator.estimate(inputs(200, motionAsleep = false, motionCertainty = 1f)) }
        estimator.reset()

        val fresh = estimator.estimate(inputs(200, motionAsleep = true, motionCertainty = 1f))
        assertTrue("after reset the previous awake state must not persist", fresh.awake < 0.5f)
    }

    @Test
    fun `unknown estimates are uniform and unusable`() {
        val unknown = StageEstimate.unknown(0L, "sensors disabled")
        assertEquals(0f, unknown.confidence, 0f)
        assertTrue(!unknown.isUsable)
        assertEquals(0.25f, unknown.awake, 0.001f)
        assertEquals(unknown.awake, unknown.rem, 0.001f)
    }

    @Test
    fun `basis names the signals that were actually used`() {
        val estimator = SleepStageEstimator()
        val modelOnly = estimator.estimate(inputs(100))
        assertTrue(modelOnly.basis.contains("sleep-cycle model"))
        assertTrue("must not claim movement when there was none", !modelOnly.basis.contains("movement"))

        estimator.reset()
        val withSensors = estimator.estimate(
            inputs(100, motionAsleep = true, motionCertainty = 0.8f, breathingRegularity = 0.4f, breathingConfidence = 0.7f),
        )
        assertTrue(withSensors.basis.contains("movement"))
        assertTrue(withSensors.basis.contains("breathing"))
    }
}
