package com.ai.assistance.operit.voice.unified

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedVoicePrototypeControllerTest {
    @Test
    fun twentyCyclesUseOneCaptureIdentity() {
        val input = FakeUnifiedAudioInput()
        val controller = UnifiedVoicePrototypeController(input, prerollSeconds = 1)

        assertTrue(controller.start())
        repeat(20) {
            input.emit(floatArrayOf(it.toFloat()))
            controller.onWakeDetected()
            controller.onResponseStarted()
            controller.onBargeIn()
            controller.onResponseStarted()
            controller.onResponseFinished()
            controller.onSessionEnded()
            assertTrue(controller.continuity()!!.isContinuous)
        }

        assertEquals(1, input.startCalls)
        assertEquals(0, input.stopCalls)
        assertEquals(UnifiedVoiceState.HOTWORD_ARMED, controller.runtime.stateMachine.state)

        controller.stop()
        assertEquals(1, input.stopCalls)
    }

    private class FakeUnifiedAudioInput : UnifiedAudioInput {
        override val sampleRate = 16_000
        override val frameSize = 512
        var startCalls = 0
        var stopCalls = 0
        private var callback: ((FloatArray) -> Unit)? = null
        private var identity: CaptureIdentity? = null

        override fun start(onFrame: (FloatArray) -> Unit): Boolean {
            startCalls++
            callback = onFrame
            identity = CaptureIdentity(creationId = 1, audioSessionId = 77)
            return true
        }

        override fun stop() {
            stopCalls++
            callback = null
            identity = null
        }

        override fun captureIdentity(): CaptureIdentity? = identity

        fun emit(frame: FloatArray) {
            callback?.invoke(frame)
        }
    }
}
