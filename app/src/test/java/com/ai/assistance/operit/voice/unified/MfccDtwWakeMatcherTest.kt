package com.ai.assistance.operit.voice.unified

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MfccDtwWakeMatcherTest {
    @Test
    fun identicalFeatureSequenceMatches() {
        val dim = 3
        val template = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
        )
        val result = MfccDtwWakeMatcher(featureDim = dim).match(
            features = template.copyOf(),
            templates = listOf(template),
            threshold = 0.99f,
        )
        assertTrue(result.detected)
    }

    @Test
    fun oppositeFeatureSequenceDoesNotMatchStrictThreshold() {
        val dim = 3
        val template = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
        val candidate = floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f)
        val result = MfccDtwWakeMatcher(featureDim = dim).match(
            features = candidate,
            templates = listOf(template),
            threshold = 0.9f,
        )
        assertFalse(result.detected)
    }

    @Test
    fun durationMismatchIsRejectedBeforeSimilarityCanTrigger() {
        val dim = 3
        val frame = floatArrayOf(1f, 0f, 0f)
        val template = FloatArray(dim * 8) { frame[it % dim] }
        val candidate = FloatArray(dim * 2) { frame[it % dim] }
        val result = MfccDtwWakeMatcher(featureDim = dim).match(
            features = candidate,
            templates = listOf(template),
            threshold = 0.84f,
        )
        assertFalse(result.detected)
        assertEquals(0, result.hits)
    }

    @Test
    fun consistentEnrollmentCanLowerThresholdOnlyToHistoricalFloor() {
        val dim = 3
        val template = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
        )
        val result = MfccDtwWakeMatcher(featureDim = dim).match(
            features = template.copyOf(),
            templates = listOf(template.copyOf(), template.copyOf()),
            threshold = 0.865f,
        )
        assertEquals(0.865f, result.threshold, 0.0001f)
        assertTrue(result.detected)
    }
}
