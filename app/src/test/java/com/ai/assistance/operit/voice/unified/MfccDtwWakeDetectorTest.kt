package com.ai.assistance.operit.voice.unified

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MfccDtwWakeDetectorTest {
    @Test
    fun waitsForVadEndThenMatchesWithoutOwningCapture() {
        val feature = FloatArray(39) { i -> if (i == 0) 1f else 0f }
        val detector = MfccDtwWakeDetector(
            speechGate = SpeechGate { frame -> frame.firstOrNull() == 1f },
            featureEncoder = { feature.copyOf() },
            sampleRate = 1_000,
            minSegmentMs = 2,
            maxSegmentMs = 20,
            endSilenceMs = 2,
        )
        detector.loadProfile(
            WakeProfile(
                version = 1,
                backendId = MfccDtwWakeDetector.BACKEND_ID,
                sampleRate = 1_000,
                profileData = listOf(feature),
                threshold = 0.99f,
                createdAt = 0L,
            )
        )

        assertFalse(detector.process(floatArrayOf(1f)).detected)
        assertFalse(detector.process(floatArrayOf(1f)).detected)
        assertFalse(detector.process(floatArrayOf(0f)).detected)
        assertTrue(detector.process(floatArrayOf(0f)).detected)
    }
}
