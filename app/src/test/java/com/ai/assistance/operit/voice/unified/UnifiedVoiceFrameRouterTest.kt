package com.ai.assistance.operit.voice.unified

import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedVoiceFrameRouterTest {
    @Test
    fun onlyStateSelectedConsumerReceivesPcm() {
        val runtime = UnifiedAudioRuntime(sampleRate = 4, prerollSeconds = 1)
        val wake = FakeWakeDetector()
        var wakes = 0
        var sessionFrames = 0
        var speakingFrames = 0
        val router = UnifiedVoiceFrameRouter(
            runtime = runtime,
            wakeDetector = wake,
            onWakeDetected = { wakes++ },
            onSessionFrame = { sessionFrames++ },
            onSpeakingFrame = { speakingFrames++ },
        )

        wake.detectNext = true
        runtime.onFrame(floatArrayOf(1f))
        assertEquals(1, wakes)
        assertEquals(1, wake.frames)

        runtime.stateMachine.onWakeDetected()
        runtime.onFrame(floatArrayOf(2f))
        assertEquals(1, sessionFrames)
        assertEquals(1, wake.frames)

        runtime.stateMachine.onResponseStarted()
        runtime.onFrame(floatArrayOf(3f))
        assertEquals(1, speakingFrames)
        assertEquals(1, wake.frames)

        runtime.stateMachine.onBargeIn()
        runtime.onFrame(floatArrayOf(4f))
        assertEquals(2, sessionFrames)

        runtime.stateMachine.onSessionEnded()
        runtime.onFrame(floatArrayOf(5f))
        assertEquals(2, wake.frames)

        router.close()
        runtime.close()
    }

    private class FakeWakeDetector : WakeDetector {
        var frames = 0
        var detectNext = false
        override fun loadProfile(profile: WakeProfile) = Unit
        override fun process(pcm: FloatArray): WakeResult {
            frames++
            val detected = detectNext
            detectNext = false
            return WakeResult(detected = detected, score = if (detected) 1f else 0f)
        }
        override fun reset() = Unit
    }
}
