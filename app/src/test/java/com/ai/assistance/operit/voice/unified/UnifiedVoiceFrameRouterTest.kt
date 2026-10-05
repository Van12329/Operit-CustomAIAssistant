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
        var preroll = floatArrayOf()
        val router = UnifiedVoiceFrameRouter(
            runtime = runtime,
            wakeDetector = wake,
            onWakeDetected = { wakes++ },
            onSessionPreroll = { preroll = it },
            onSessionFrame = { sessionFrames++ },
            onSpeakingFrame = { speakingFrames++ },
        )

        wake.detectNext = true
        runtime.onFrame(floatArrayOf(1f))
        assertEquals(1, wakes)
        assertEquals(1, wake.frames)
        assertEquals(listOf(1f), preroll.toList())

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


    @Test
    fun streamingSttStartsWithPrerollThenConsumesOnlySessionFrames() {
        val runtime = UnifiedAudioRuntime(sampleRate = 4, prerollSeconds = 1)
        val wake = FakeWakeDetector()
        val stt = FakeStreamingSpeechRecognizer()
        val router = UnifiedVoiceFrameRouter(
            runtime = runtime,
            wakeDetector = wake,
            onWakeDetected = { runtime.stateMachine.onWakeDetected() },
            speechRecognizer = stt,
        )

        runtime.onFrame(floatArrayOf(1f))
        wake.detectNext = true
        runtime.onFrame(floatArrayOf(2f))
        assertEquals(listOf(1f, 2f), stt.preroll.toList())
        assertEquals(0, stt.frames.size)

        runtime.onFrame(floatArrayOf(3f))
        runtime.onFrame(floatArrayOf(4f))
        assertEquals(listOf(listOf(3f), listOf(4f)), stt.frames.map { it.toList() })

        router.close()
        assertEquals(1, stt.closed)
        runtime.close()
    }

    @Test
    fun sttCanRestartForNextTurnWithoutRestartingCaptureOrWake() {
        val runtime = UnifiedAudioRuntime(sampleRate = 4, prerollSeconds = 1)
        val wake = FakeWakeDetector()
        val stt = FakeStreamingSpeechRecognizer()
        val router = UnifiedVoiceFrameRouter(
            runtime = runtime,
            wakeDetector = wake,
            onWakeDetected = { runtime.stateMachine.onWakeDetected() },
            speechRecognizer = stt,
        )

        wake.detectNext = true
        runtime.onFrame(floatArrayOf(1f))
        assertEquals(1, stt.starts)

        runtime.stateMachine.onResponseStarted()
        router.finishSpeechRecognition()
        runtime.onFrame(floatArrayOf(2f))
        assertEquals(0, stt.frames.size)

        runtime.stateMachine.onResponseFinished()
        router.startSpeechRecognition()
        runtime.onFrame(floatArrayOf(3f))
        assertEquals(2, stt.starts)
        assertEquals(listOf(listOf(3f)), stt.frames.map { it.toList() })
        assertEquals(1, wake.frames)

        router.close()
        runtime.close()
    }

    @Test
    fun enrollmentSuppressesWakeAndSttWithoutStoppingRawRuntime() {
        val runtime = UnifiedAudioRuntime(sampleRate = 4, prerollSeconds = 1)
        val wake = FakeWakeDetector()
        val stt = FakeStreamingSpeechRecognizer()
        val router = UnifiedVoiceFrameRouter(
            runtime = runtime,
            wakeDetector = wake,
            onWakeDetected = {},
            speechRecognizer = stt,
        )
        var rawFrames = 0
        val rawSubscription = runtime.bus.subscribe { rawFrames++ }

        router.setEnrollmentActive(true)
        wake.detectNext = true
        runtime.onFrame(floatArrayOf(1f))
        assertEquals(1, rawFrames)
        assertEquals(0, wake.frames)
        assertEquals(0, stt.starts)

        router.setEnrollmentActive(false)
        runtime.onFrame(floatArrayOf(2f))
        assertEquals(2, rawFrames)
        assertEquals(1, wake.frames)

        rawSubscription.close()
        router.close()
        runtime.close()
    }

    private class FakeStreamingSpeechRecognizer : StreamingSpeechRecognizer {
        var preroll = floatArrayOf()
        val frames = mutableListOf<FloatArray>()
        var closed = 0
        var starts = 0
        private var active = false

        override fun startSession(preroll: FloatArray): Boolean {
            starts++
            active = true
            this.preroll = preroll.copyOf()
            return true
        }
        override fun acceptPcm(pcm: FloatArray) { if (active) frames += pcm.copyOf() }
        override fun finishSession() { active = false }
        override fun cancelSession() { active = false }
        override fun close() { closed++ }
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
