package com.ai.assistance.operit.voice.unified

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
}
