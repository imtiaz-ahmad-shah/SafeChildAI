package com.safechild.ai.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

class IsolationForestTest {
    @Test
    fun forestFlagsSpatiallyAndTemporallyIsolatedObservation() {
        val random = Random(19)
        val baseline = (0 until 90).map { index ->
            val angle = 2.0 * Math.PI * (index % 20) / 20.0
            doubleArrayOf(random.nextGaussian() * 8, random.nextGaussian() * 8, sin(angle), cos(angle))
        }
        val forest = IsolationForest.fit(baseline, seed = 7L)
        assertNotNull(forest)
        val usual = doubleArrayOf(0.0, 0.0, sin(2.0 * Math.PI * 5 / 20.0), cos(2.0 * Math.PI * 5 / 20.0))
        val unusual = doubleArrayOf(2_000.0, -2_000.0, 0.99, -0.01)
        assertTrue(forest!!.score(unusual) > forest.score(usual))
        assertTrue(forest.isAnomaly(unusual))
    }

    @Test
    fun forestDoesNotFitBeforeMinimumHistory() {
        val tooLittleData = List(IsolationForest.MIN_TRAINING_SAMPLES - 1) { index ->
            doubleArrayOf(index.toDouble(), index.toDouble(), 0.0, 1.0)
        }
        assertFalse(IsolationForest.fit(tooLittleData) != null)
    }

    @Test
    fun childSamplesRequireAccurateReadingsAcrossThreeDifferentDays() {
        val calendar = Calendar.getInstance()
        val points = buildList {
            repeat(3) { day ->
                repeat(12) { slot ->
                    calendar.set(2026, Calendar.OCTOBER, 1 + day, 8, slot * 10, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    add(ChildLocationFeatures.Observation(35.84 + slot * 0.000001, 71.78, calendar.timeInMillis, 15.0))
                }
            }
        }
        val ready = ChildLocationFeatures.readiness(points)
        assertTrue(ready.ready)
        assertTrue(ready.usableSamples >= 36)
        assertTrue(ready.distinctDays >= 3)
        assertFalse(ChildLocationFeatures.readiness(points.map { it.copy(accuracyMeters = 140.0) }).ready)
    }
}
