package com.ai.assistance.operit.voice.unified

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedVoiceRuntimeTest {
    @Test
    fun fullCycleChangesStateWithoutTouchingCaptureIdentity() {
        val capture = CaptureIdentity(creationId = 1, audioSessionId = 42)
        val guard = CaptureContinuityGuard(capture)
        val runtime = UnifiedAudioRuntime(sampleRate = 4, prerollSeconds = 1)

        runtime.onFrame(floatArrayOf(1f, 2f))
        assertEquals(UnifiedVoiceState.HOTWORD_ARMED, runtime.stateMachine.state)

        runtime.stateMachine.onWakeDetected()
        runtime.onFrame(floatArrayOf(3f, 4f))
        runtime.stateMachine.onResponseStarted()
        runtime.onFrame(floatArrayOf(5f))
        runtime.stateMachine.onBargeIn()
        runtime.onFrame(floatArrayOf(6f))
        runtime.stateMachine.onSessionEnded()

        assertEquals(UnifiedVoiceState.HOTWORD_ARMED, runtime.stateMachine.state)
        assertTrue(guard.check(capture).isContinuous)
        assertEquals(listOf(3f, 4f, 5f, 6f), runtime.preroll.snapshotLast().toList())
    }

    @Test
    fun continuityGuardDetectsRecreatedRecorderAndChangedSession() {
        val guard = CaptureContinuityGuard(CaptureIdentity(creationId = 7, audioSessionId = 100))

        assertFalse(guard.check(CaptureIdentity(creationId = 8, audioSessionId = 100)).isContinuous)
        assertFalse(guard.check(CaptureIdentity(creationId = 7, audioSessionId = 101)).isContinuous)
        assertTrue(guard.check(CaptureIdentity(creationId = 7, audioSessionId = 100)).isContinuous)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotWakeWhileSessionIsAlreadyActive() {
        val state = UnifiedVoiceStateMachine()
        state.onWakeDetected()
        state.onWakeDetected()
    }
}
