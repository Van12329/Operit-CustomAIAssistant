package com.ai.assistance.operit.voice.unified

import android.util.Log

/**
 * Owns the prototype capture lifecycle. State transitions never call start/stop.
 */
class UnifiedVoicePrototypeController(
    private val input: UnifiedAudioInput,
    prerollSeconds: Int = 3,
) {
    val runtime = UnifiedAudioRuntime(input.sampleRate, prerollSeconds)

    private var continuityGuard: CaptureContinuityGuard? = null

    fun start(): Boolean {
        if (!input.start(runtime::onFrame)) return false
        val identity = requireNotNull(input.captureIdentity()) { "capture started without identity" }
        continuityGuard = CaptureContinuityGuard(identity)
        logContinuity("START")
        return true
    }

    fun onWakeDetected() {
        runtime.stateMachine.onWakeDetected()
        logContinuity("WAKE_TO_SESSION")
    }

    fun onResponseStarted() {
        runtime.stateMachine.onResponseStarted()
        logContinuity("LISTENING_TO_SPEAKING")
    }

    fun onBargeIn() {
        runtime.stateMachine.onBargeIn()
        logContinuity("BARGE_IN")
    }

    fun onResponseFinished() {
        runtime.stateMachine.onResponseFinished()
        logContinuity("SPEAKING_TO_LISTENING")
    }

    fun onSessionEnded() {
        runtime.stateMachine.onSessionEnded()
        logContinuity("SESSION_TO_WAKE")
    }

    fun continuity(): CaptureContinuitySnapshot? {
        val current = input.captureIdentity() ?: return null
        return continuityGuard?.check(current)
    }

    fun stop() {
        input.stop()
        runtime.close()
        continuityGuard = null
    }

    private fun logContinuity(event: String) {
        val snapshot = continuity()
        if (snapshot == null) {
            Log.e(TAG, "CONTINUITY_FAIL event=$event reason=no_capture_identity")
            return
        }
        val level = if (snapshot.isContinuous) "PASS" else "FAIL"
        Log.i(
            TAG,
            "CONTINUITY_$level event=$event state=${runtime.stateMachine.state} " +
                "expected=${snapshot.expected} current=${snapshot.current}",
        )
    }

    private companion object {
        const val TAG = "CAA-UnifiedVoice"
    }
}
