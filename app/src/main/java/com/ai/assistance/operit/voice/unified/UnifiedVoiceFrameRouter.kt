package com.ai.assistance.operit.voice.unified

/**
 * Routes the single PCM stream according to the active voice state.
 *
 * Wake is intentionally a pure PCM consumer. It has no Android/microphone lifecycle access.
 */
class UnifiedVoiceFrameRouter(
    private val runtime: UnifiedAudioRuntime,
    private val wakeDetector: WakeDetector,
    private val onWakeDetected: (WakeResult) -> Unit,
    private val onSessionPreroll: (FloatArray) -> Unit = {},
    private val onSessionFrame: (FloatArray) -> Unit = {},
    private val onSpeakingFrame: (FloatArray) -> Unit = {},
) : AutoCloseable {
    private val subscription = runtime.bus.subscribe { frame ->
        when (runtime.stateMachine.state) {
            UnifiedVoiceState.HOTWORD_ARMED -> {
                val result = wakeDetector.process(frame)
                if (result.detected) {
                    // The preroll subscriber is registered by UnifiedAudioRuntime before this router,
                    // so the snapshot includes the current wake frame without any recorder handoff.
                    onSessionPreroll(runtime.preroll.snapshotLast())
                    onWakeDetected(result)
                }
            }
            UnifiedVoiceState.SESSION_LISTENING -> onSessionFrame(frame)
            UnifiedVoiceState.SPEAKING -> onSpeakingFrame(frame)
            UnifiedVoiceState.SUSPENDED_BY_CALL -> Unit
        }
    }

    override fun close() {
        subscription.close()
        wakeDetector.reset()
    }
}
