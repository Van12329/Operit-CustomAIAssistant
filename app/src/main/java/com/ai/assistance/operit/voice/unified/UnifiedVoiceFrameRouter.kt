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
    private val speechRecognizer: StreamingSpeechRecognizer? = null,
) : AutoCloseable {
    private val subscription = runtime.bus.subscribe { frame ->
        when (runtime.stateMachine.state) {
            UnifiedVoiceState.HOTWORD_ARMED -> {
                if (!wakeDetector.isReady()) return@subscribe
                val result = wakeDetector.process(frame)
                if (result.detected) {
                    // The preroll subscriber is registered by UnifiedAudioRuntime before this router,
                    // so the snapshot includes the current wake frame without any recorder handoff.
                    val preroll = runtime.preroll.snapshotLast()
                    onSessionPreroll(preroll)
                    speechRecognizer?.startSession(preroll)
                    onWakeDetected(result)
                }
            }
            UnifiedVoiceState.SESSION_LISTENING -> {
                speechRecognizer?.acceptPcm(frame)
                onSessionFrame(frame)
            }
            UnifiedVoiceState.SPEAKING -> onSpeakingFrame(frame)
            UnifiedVoiceState.SUSPENDED_BY_CALL -> Unit
        }
    }

    fun startSpeechRecognition(preroll: FloatArray = floatArrayOf()): Boolean {
        return speechRecognizer?.startSession(preroll) ?: false
    }

    fun finishSpeechRecognition() {
        speechRecognizer?.finishSession()
    }

    fun cancelSpeechRecognition() {
        speechRecognizer?.cancelSession()
    }

    override fun close() {
        subscription.close()
        wakeDetector.reset()
        speechRecognizer?.close()
    }
}
