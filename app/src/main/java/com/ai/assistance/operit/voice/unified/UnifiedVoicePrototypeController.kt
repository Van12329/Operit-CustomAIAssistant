package com.ai.assistance.operit.voice.unified

/**
 * Owns the prototype capture lifecycle. State transitions never call start/stop.
 *
 * Diagnostics are injected so the lifecycle core stays independent from Android logging and
 * remains directly JVM-testable before it is embedded into Operit services/UI.
 */
class UnifiedVoicePrototypeController(
    private val input: UnifiedAudioInput,
    prerollSeconds: Int = 3,
    private val diagnostics: (String) -> Unit = {},
) {
    val runtime = UnifiedAudioRuntime(input.sampleRate, prerollSeconds)

    private var continuityGuard: CaptureContinuityGuard? = null

    fun start(): Boolean {
        if (!input.start(runtime::onFrame)) return false
        val identity = requireNotNull(input.captureIdentity()) { "capture started without identity" }
        continuityGuard = CaptureContinuityGuard(identity)
        reportContinuity("START")
        return true
    }

    fun onWakeDetected() {
        runtime.stateMachine.onWakeDetected()
        reportContinuity("WAKE_TO_SESSION")
    }

    fun onResponseStarted() {
        runtime.stateMachine.onResponseStarted()
        reportContinuity("LISTENING_TO_SPEAKING")
    }

    fun onBargeIn() {
        runtime.stateMachine.onBargeIn()
        reportContinuity("BARGE_IN")
    }

    fun onResponseFinished() {
        runtime.stateMachine.onResponseFinished()
        reportContinuity("SPEAKING_TO_LISTENING")
    }

    fun onSessionEnded() {
        runtime.stateMachine.onSessionEnded()
        reportContinuity("SESSION_TO_WAKE")
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

    private fun reportContinuity(event: String) {
        val snapshot = continuity()
        if (snapshot == null) {
            diagnostics("CONTINUITY_FAIL event=$event reason=no_capture_identity")
            return
        }
        val level = if (snapshot.isContinuous) "PASS" else "FAIL"
        diagnostics(
            "CONTINUITY_$level event=$event state=${runtime.stateMachine.state} " +
                "expected=${snapshot.expected} current=${snapshot.current}",
        )
    }
}
